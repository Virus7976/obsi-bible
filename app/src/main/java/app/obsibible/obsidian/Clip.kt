package app.obsibible.obsidian

import app.obsibible.bible.Ref
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What is being sent: the verses, the words, and what the user wrote about them. */
data class Clip(
    val ref: Ref,
    val translation: String,
    val translationName: String,
    val text: String,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val color: String = "",
    val copyright: String = "",
    val at: Long = System.currentTimeMillis(),
    /** Links added before sending: [[Note]] or a verse link. */
    val links: List<String> = emptyList(),
)

/**
 * The values a verse note can use. Variables look like {{reference}}. Every note links back to its
 * verse with an obsibible:// link, so a tap in Obsidian opens the verse in the app.
 */
object NoteBuilder {
    fun link(ref: Ref) = "obsibible://ref/${ref.key}"

    fun values(c: Clip): Map<String, String> {
        val r = c.ref
        val verses = when {
            r.verse <= 0 -> ""
            r.verseEnd > r.verse -> "${r.verse}-${r.verseEnd}"
            else -> r.verse.toString()
        }
        val tags = c.tags.map { it.trim().removePrefix("#").replace(' ', '-') }.filter { it.isNotEmpty() }
        return mapOf(
            "reference" to r.label,
            "book" to r.bookInfo.name,
            "chapter" to r.chapter.toString(),
            "verses" to verses,
            "translation" to c.translation,
            "translationName" to c.translationName,
            "text" to c.text.trim(),
            "note" to c.note.trim(),
            "link" to link(r),
            "tags" to tags.joinToString(" ") { "#$it" },
            "tagsYaml" to (listOf("bible") + tags).distinct().joinToString(", "),
            "date" to SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(c.at)),
            "time" to SimpleDateFormat("HH:mm", Locale.US).format(Date(c.at)),
            "color" to c.color,
            "copyright" to c.copyright,
            "title" to r.label,
            "links" to c.links.joinToString(" · "),
        )
    }

    /** A file name Obsidian and Android both accept: no : / \ ? * " < > | # ^ [ ]. */
    fun safeName(s: String): String =
        s.replace(':', '.').replace(Regex("[/\\\\?*\"<>|#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Verse" }
}
