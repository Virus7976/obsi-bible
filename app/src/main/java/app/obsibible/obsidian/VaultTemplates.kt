package app.obsibible.obsidian

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Notes from the vault used as templates, as in ObsiTube. The folder is the one set in Obsidian's
 * Templates plugin (.obsidian/templates.json). Without that, any folder named "Templates" is used.
 */
object VaultTemplates {
    /** One template note. [uri] is the document to read. */
    data class Entry(val name: String, val path: String, val uri: String)

    private data class Kid(val id: String, val name: String, val dir: Boolean)

    private fun kids(context: Context, tree: Uri, id: String): List<Kid> {
        val out = ArrayList<Kid>()
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        context.contentResolver.query(
            uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c -> while (c.moveToNext()) out += Kid(c.getString(0), c.getString(1) ?: "", c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR) }
        return out
    }

    private fun readDoc(context: Context, tree: Uri, id: String): String? = runCatching {
        context.contentResolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** The template notes in the vault, by name. Empty when no vault folder is picked. */
    fun list(context: Context, treeUri: String): List<Entry> {
        if (treeUri.isBlank()) return emptyList()
        return runCatching {
            val tree = Uri.parse(treeUri)
            val root = DocumentsContract.getTreeDocumentId(tree)
            val top = kids(context, tree, root)
            // 1. The folder set in Obsidian's Templates plugin.
            val setting = top.firstOrNull { it.dir && it.name == ".obsidian" }
                ?.let { o -> kids(context, tree, o.id).firstOrNull { it.name == "templates.json" } }
                ?.let { readDoc(context, tree, it.id) }
                ?.let { Regex("\"folder\"\\s*:\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }
                ?.trim('/')?.takeIf { it.isNotBlank() }
            var folder: Pair<String, String>? = null
            if (setting != null) {
                var id = root
                var ok = true
                for (part in setting.split('/')) {
                    id = kids(context, tree, id).firstOrNull { it.dir && it.name == part }?.id ?: run { ok = false; id }
                    if (!ok) break
                }
                if (ok) folder = id to setting
            }
            // 2. A folder named Templates, at the top or one level down.
            if (folder == null) {
                top.firstOrNull { it.dir && it.name.equals("templates", true) }?.let { folder = it.id to it.name }
                    ?: top.filter { it.dir && !it.name.startsWith(".") }.firstNotNullOfOrNull { d ->
                        kids(context, tree, d.id).firstOrNull { it.dir && it.name.equals("templates", true) }?.let { it.id to "${d.name}/${it.name}" }
                    }?.let { folder = it }
            }
            val (fid, fpath) = folder ?: return@runCatching emptyList()
            val out = ArrayList<Entry>()
            val stack = ArrayDeque<Pair<String, String>>()
            stack += fid to fpath
            while (stack.isNotEmpty() && out.size < 300) {
                val (id, path) = stack.removeFirst()
                for (k in kids(context, tree, id)) {
                    if (k.name.startsWith(".")) continue
                    if (k.dir) stack += k.id to "$path/${k.name}"
                    else if (k.name.endsWith(".md", true)) out += Entry(k.name.dropLast(3), "$path/${k.name}", DocumentsContract.buildDocumentUriUsingTree(tree, k.id).toString())
                }
            }
            out.sortedBy { it.name.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun read(context: Context, uri: String): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private val bibleVars = Regex("\\{\\{\\s*(text|reference|note|link)\\s*\\}\\}")

    /** The Bible part added to a template that has no Bible variables of its own. */
    const val BIBLE_BLOCK = "> [!bible] [{{reference}}]({{link}}) · {{translation}}\n> {{text}}\n\n{{note}}"

    /**
     * A vault note made ready for [NoteBuilder.fill]: Obsidian's own {{date:FORMAT}} and
     * {{time:FORMAT}} are filled in, and a note with no Bible variables gets the verse added at the end.
     */
    fun prepare(text: String, at: Long = System.currentTimeMillis()): String {
        var t = text.replace("\r\n", "\n")
        t = Regex("\\{\\{\\s*(date|time)\\s*:\\s*([^}]+?)\\s*\\}\\}").replace(t) { m -> Regex.escapeReplacement(moment(m.groupValues[2], at)) }
        if (!bibleVars.containsMatchIn(t)) t = t.trimEnd() + "\n\n" + BIBLE_BLOCK + "\n"
        return t
    }

    /** Formats [at] with a moment.js pattern, the kind Obsidian uses ("YYYY-MM-DD", "dddd, MMMM Do"). */
    fun moment(pattern: String, at: Long): String {
        val d = Date(at)
        val tokens = Regex("\\[[^\\]]*\\]|YYYY|YY|MMMM|MMM|MM|M|Do|DD|D|dddd|ddd|HH|H|hh|h|mm|m|ss|s|A|a|.")
        val sb = StringBuilder()
        for (m in tokens.findAll(pattern)) {
            val k = m.value
            fun f(p: String) = SimpleDateFormat(p, Locale.US).format(d)
            sb.append(
                when (k) {
                    "YYYY" -> f("yyyy"); "YY" -> f("yy")
                    "MMMM" -> f("MMMM"); "MMM" -> f("MMM"); "MM" -> f("MM"); "M" -> f("M")
                    "DD" -> f("dd"); "D" -> f("d")
                    "Do" -> f("d").let { n -> n + ordinal(n.toInt()) }
                    "dddd" -> f("EEEE"); "ddd" -> f("EEE")
                    "HH" -> f("HH"); "H" -> f("H"); "hh" -> f("hh"); "h" -> f("h")
                    "mm" -> f("mm"); "m" -> f("m"); "ss" -> f("ss"); "s" -> f("s")
                    "A" -> f("a").uppercase(); "a" -> f("a").lowercase()
                    else -> if (k.startsWith("[")) k.removeSurrounding("[", "]") else k
                },
            )
        }
        return sb.toString()
    }

    private fun ordinal(n: Int) = if (n % 100 in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    private fun unq(s: String) = s.trim().trim('"', '\'').removePrefix("#")

    /** Adds [tags] to the note's tags property, keeping the ones already there. */
    fun mergeTags(md: String, tags: List<String>): String {
        val add = tags.map { unq(it).replace(' ', '-') }.filter { it.isNotEmpty() }
        if (add.isEmpty()) return md
        if (!md.startsWith("---\n")) return "---\ntags:\n" + add.distinct().joinToString("\n") { "  - $it" } + "\n---\n" + md
        val end = md.indexOf("\n---", 4)
        if (end < 0) return md
        val lines = md.substring(4, end).split('\n').toMutableList()
        val rest = md.substring(end)
        val have = ArrayList<String>()
        var i = lines.indexOfFirst { Regex("^tags\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (i >= 0) {
            val v = lines[i].substringAfter(':').trim()
            if (v.startsWith("[")) have += v.removeSurrounding("[", "]").split(',').map(::unq)
            else if (v.isNotEmpty()) have += v.split(Regex("[\\s,]+")).map(::unq)
            var j = i + 1
            while (j < lines.size && lines[j].trimStart().startsWith("-")) { have += unq(lines[j].trim().removePrefix("-")); j++ }
            repeat(j - i) { lines.removeAt(i) }
        } else {
            i = lines.size
            if (lines.lastOrNull()?.isBlank() == true && lines.size == 1) { lines.clear(); i = 0 }
        }
        val all = (have + add).filter { it.isNotEmpty() }.distinct()
        lines.addAll(i, listOf("tags:") + all.map { "  - $it" })
        return "---\n" + lines.joinToString("\n") + rest
    }
}
