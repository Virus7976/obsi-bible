package app.obsibible.obsidian

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.io.File

/**
 * The tags used in the vault, with how often each is used. Reading every note is slow on Android,
 * so the last result is kept in a file and shown at once; a fresh read runs in the background.
 */
class VaultTags(private val context: Context) {
    private val file = File(context.filesDir, "vault-tags.json")
    private val _tags = MutableStateFlow(load())
    /** Tag to count, most used first. */
    val tags: StateFlow<Map<String, Int>> = _tags.asStateFlow()
    @Volatile private var readAt = 0L
    @Volatile private var readFor = ""

    private fun load(): Map<String, Int> = runCatching {
        (Json.parseToJsonElement(file.readText()) as JsonObject).mapNotNull { (k, v) -> (v as? JsonPrimitive)?.intOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    /** Reads the vault again, at most once every ten minutes for the same vault. Call it off the main thread. */
    fun refresh(treeUri: String, force: Boolean = false) {
        if (treeUri.isBlank()) return
        if (!force && readFor == treeUri && System.currentTimeMillis() - readAt < 10 * 60_000) return
        readFor = treeUri
        readAt = System.currentTimeMillis()
        val counts = HashMap<String, Int>()
        runCatching {
            val tree = Uri.parse(treeUri)
            val stack = ArrayDeque<String>()
            stack += DocumentsContract.getTreeDocumentId(tree)
            var notes = 0
            while (stack.isNotEmpty() && notes < 4000) {
                val id = stack.removeFirst()
                context.contentResolver.query(
                    DocumentsContract.buildChildDocumentsUriUsingTree(tree, id),
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) { stack += c.getString(0); continue }
                        if (!name.endsWith(".md", true)) continue
                        notes++
                        val text = runCatching {
                            context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)))?.use { s ->
                                val buf = ByteArray(64 * 1024)
                                val n = s.read(buf).coerceAtLeast(0)
                                String(buf, 0, n, Charsets.UTF_8)
                            }
                        }.getOrNull() ?: continue
                        for (t in find(text)) counts[t] = (counts[t] ?: 0) + 1
                    }
                }
            }
        }
        if (counts.isEmpty()) return
        val sorted = counts.entries.sortedByDescending { it.value }.associate { it.key to it.value }
        _tags.value = sorted
        runCatching { file.writeText(JsonObject(sorted.mapValues { JsonPrimitive(it.value) }).toString()) }
    }

    companion object {
        private val inline = Regex("(?<![\\p{L}\\p{N}_/&#\\[\\]])#([\\p{L}\\p{N}_/-]*[\\p{L}_/-][\\p{L}\\p{N}_/-]*)")
        private val fence = Regex("```.*?```", RegexOption.DOT_MATCHES_ALL)

        /** Tags in a note: in the frontmatter (tags: [a, b], tags: a, or a list) and #inline ones. */
        fun find(text: String): Set<String> {
            val out = LinkedHashSet<String>()
            fun add(raw: String) {
                val t = raw.trim().trim('"', '\'').removePrefix("#").trim()
                if (t.isNotEmpty() && !t.all { it.isDigit() } && !t.contains("{{")) out += t
            }
            val t = text.replace("\r\n", "\n").removePrefix("﻿")
            var body = t
            if (t.startsWith("---")) {
                val end = Regex("\n---[ \t]*(\n|$)").find(t, 3)
                if (end != null) {
                    val lines = t.substring(3, end.range.first).lines()
                    var i = 0
                    while (i < lines.size) {
                        val m = Regex("^(?:tags|tag)\\s*:\\s*(.*)$", RegexOption.IGNORE_CASE).find(lines[i])
                        if (m != null) {
                            val rest = m.groupValues[1].trim()
                            if (rest.isNotEmpty()) rest.removePrefix("[").removeSuffix("]").split(',', ' ').forEach(::add)
                            else while (i + 1 < lines.size && Regex("^\\s*-\\s+.*").matches(lines[i + 1])) { add(lines[i + 1].trim().removePrefix("-")); i++ }
                        }
                        i++
                    }
                    body = t.substring(end.range.last + 1)
                }
            }
            inline.findAll(fence.replace(body, " ")).forEach { add(it.groupValues[1]) }
            return out
        }
    }
}
