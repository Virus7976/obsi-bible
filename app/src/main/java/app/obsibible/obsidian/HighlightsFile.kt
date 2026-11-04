package app.obsibible.obsidian

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import app.obsibible.data.HighlightColors
import app.obsibible.data.Mark

/**
 * Every highlight and note, kept in the vault as one Markdown file per Bible book, such as
 * "Bible/Highlights/John.md". The file is written again from the app's marks, so an edit or a
 * delete in the app shows up there too. A mark can also be sent as its own note.
 */
object HighlightsFile {
    /** The words of a book's file. [abbr] gives the short name of a Bible, such as ESV. */
    fun build(book: String, marks: List<Mark>, abbr: (String) -> String): String {
        val sorted = marks.sortedWith(compareBy({ it.chapter }, { it.v1 }, { it.c1 }))
        val tags = (listOf("bible", "highlights") + sorted.flatMap { it.tags }).distinct()
        return buildString {
            append("---\n")
            append("book: \"").append(book).append("\"\n")
            append("highlights: ").append(sorted.size).append('\n')
            append("tags:\n")
            tags.forEach { append("  - ").append(it).append('\n') }
            append("---\n")
            append("# ").append(book).append(" highlights\n")
            append("\n_Made by Obsi Bible. Changes made here are written over; edit in the app._\n")
            var chapter = -1
            for (m in sorted) {
                if (m.chapter != chapter) {
                    chapter = m.chapter
                    append("\n## ").append(book).append(' ').append(chapter).append('\n')
                }
                val colour = HighlightColors.getOrNull(m.color)?.name?.let { " · $it" }.orEmpty()
                append("\n### ").append(m.label).append(" · ").append(abbr(m.bible)).append(colour).append('\n')
                if (m.quote.isNotBlank()) {
                    m.quote.trim().lines().forEach { append("> ").append(it).append('\n') }
                }
                append("> [Open in Obsi Bible](").append(NoteBuilder.link(m.ref)).append(")\n")
                if (m.note.isNotBlank()) append('\n').append(m.note.trim()).append('\n')
                if (m.tags.isNotEmpty()) append('\n').append(m.tags.joinToString(" ") { "#$it" }).append('\n')
            }
        }
    }

    private val lastWritten = HashMap<String, Int>()

    /**
     * Writes the file of every book that changed. Books with no marks left lose their file.
     * Returns null when it worked, else a plain reason.
     */
    fun sync(context: Context, treeUri: String, folder: String, marks: List<Mark>, abbr: (String) -> String): String? {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?: return "Cannot open the vault folder. Pick it again in Settings."
        if (!root.canWrite()) return "The app cannot write to the vault folder. Pick it again in Settings."
        var dir: DocumentFile = root
        for (part in folder.split('/').filter { it.isNotBlank() }) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part) ?: return "Could not make the folder $part."
        }
        val byBook = marks.filter { it.hasColor || it.hasNote }.groupBy { it.ref.bookInfo.name }
        return runCatching {
            for ((book, list) in byBook) {
                val text = build(book, list, abbr)
                val key = "$treeUri/$folder/$book"
                if (lastWritten[key] == text.hashCode()) continue
                val name = "${NoteBuilder.safeName(book)}.md"
                val file = dir.findFile(name) ?: dir.createFile("application/octet-stream", name) ?: error("no file")
                context.contentResolver.openOutputStream(file.uri, "wt")!!.bufferedWriter().use { it.write(text) }
                lastWritten[key] = text.hashCode()
            }
            // A book whose last highlight was deleted: its file says so. Nothing in the vault is ever deleted.
            dir.listFiles().forEach { f ->
                val n = f.name ?: return@forEach
                if (!n.endsWith(".md") || byBook.keys.any { NoteBuilder.safeName(it) + ".md" == n }) return@forEach
                val head = runCatching { context.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { r -> CharArray(400).let { b -> String(b, 0, r.read(b).coerceAtLeast(0)) } } }.getOrNull().orEmpty()
                if (head.contains("Made by Obsi Bible") && !head.contains("highlights: 0")) {
                    context.contentResolver.openOutputStream(f.uri, "wt")!!.bufferedWriter().use { it.write(build(n.removeSuffix(".md"), emptyList(), abbr)) }
                }
            }
            null
        }.getOrElse { "Could not write the highlights file." }
    }
}
