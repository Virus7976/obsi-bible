package app.obsibible.obsidian

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import app.obsibible.bible.Ref

/** The names of the notes in the vault folder, for linking. Read with one fast query per folder. */
object VaultIndex {
    @Volatile private var cache: Pair<String, List<String>>? = null
    @Volatile private var at = 0L

    /** Note names (no .md), newest read cached for a minute. Empty when no folder is picked. */
    fun notes(context: Context, treeUri: String): List<String> {
        if (treeUri.isBlank()) return emptyList()
        cache?.let { (k, v) -> if (k == treeUri && System.currentTimeMillis() - at < 60_000) return v }
        val out = ArrayList<String>()
        runCatching {
            val tree = Uri.parse(treeUri)
            val stack = ArrayDeque<String>()
            stack += DocumentsContract.getTreeDocumentId(tree)
            var folders = 0
            while (stack.isNotEmpty() && folders < 400 && out.size < 5000) {
                val id = stack.removeFirst()
                folders++
                val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
                context.contentResolver.query(
                    kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                    null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1) ?: continue
                        if (name.startsWith(".")) continue
                        if (c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) stack += c.getString(0)
                        else if (name.endsWith(".md", ignoreCase = true)) out += name.dropLast(3)
                    }
                }
            }
        }
        val list = out.distinct().sortedBy { it.lowercase() }
        cache = treeUri to list
        at = System.currentTimeMillis()
        return list
    }

    fun forget() { cache = null }
}

/** A link inside a note: to a verse in the app, or to a note in the vault. */
sealed interface NoteLink {
    val start: Int
    val end: Int
    val text: String
    data class Verse(override val start: Int, override val end: Int, override val text: String, val ref: Ref) : NoteLink
    data class Note(override val start: Int, override val end: Int, override val text: String, val name: String) : NoteLink
}

object NoteLinks {
    fun verse(ref: Ref) = "[${ref.label}](${NoteBuilder.link(ref)})"
    fun note(name: String) = "[[$name]]"

    private val verseLink = Regex("\\[([^\\]]+)\\]\\(obsibible://ref/([^)\\s]+)\\)")
    private val wiki = Regex("\\[\\[([^\\]|#]+)(?:#[^\\]|]*)?(?:\\|([^\\]]+))?\\]\\]")

    /** The note with each link shown by its name only, for lists and search. */
    fun plain(text: String): String {
        val links = find(text)
        if (links.isEmpty()) return text
        val sb = StringBuilder()
        var i = 0
        for (l in links) {
            if (l.start < i) continue
            sb.append(text, i, l.start).append(l.text)
            i = l.end
        }
        return sb.append(text.substring(i)).toString()
    }

    /** Every link in [text], in order. */
    fun find(text: String): List<NoteLink> {
        val out = ArrayList<NoteLink>()
        verseLink.findAll(text).forEach { m ->
            Ref.fromKey(m.groupValues[2])?.let { out += NoteLink.Verse(m.range.first, m.range.last + 1, m.groupValues[1], it) }
        }
        wiki.findAll(text).forEach { m ->
            val name = m.groupValues[1].trim()
            out += NoteLink.Note(m.range.first, m.range.last + 1, m.groupValues[2].ifBlank { name }, name)
        }
        return out.sortedBy { it.start }
    }
}
