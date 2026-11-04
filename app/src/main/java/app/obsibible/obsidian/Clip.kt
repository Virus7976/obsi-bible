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

/** A finished note: its file name, folder, and Markdown. */
data class BuiltNote(
    val name: String,
    val folder: String,
    val markdown: String,
    val append: Boolean,
    /** The part added to an existing note: the content without the properties. */
    val body: String = markdown,
    /** create | overwrite | append | prepend */
    val mode: String = if (append) "append" else "create",
)

/**
 * Fills a template with a clip. Variables look like {{reference}}. Every note links back to its
 * verse with an obsibible:// link, so a tap in Obsidian opens the verse in the app.
 */
object NoteBuilder {
    const val DEFAULT_NAME = "{{reference}}"

    val DEFAULT_TEMPLATE = """
        ---
        reference: "{{reference}}"
        book: "{{book}}"
        chapter: {{chapter}}
        verses: "{{verses}}"
        translation: {{translation}}
        tags: [{{tagsYaml}}]
        created: {{date}}
        ---
        > [!bible] [{{reference}}]({{link}}) · {{translation}}
        > {{text}}

        {{note}}
    """.trimIndent()

    /** One entry added to the end of a running note, such as "Bible highlights". */
    val DEFAULT_ENTRY = """
        ## {{reference}} · {{translation}}
        > {{text}}
        > [Open in Obsi Bible]({{link}}) · {{date}}

        {{note}}
    """.trimIndent()

    val VARIABLES = listOf(
        "reference", "book", "chapter", "verses", "translation", "text", "note",
        "link", "links", "tags", "date", "time", "title", "color", "copyright",
    )

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

    /** Puts the values in. A multi-line value inside a quote line ("> ") keeps every line quoted. */
    fun fill(template: String, values: Map<String, String>): String {
        val out = StringBuilder()
        for ((n, line) in template.lines().withIndex()) {
            if (n > 0) out.append('\n')
            val quote = Regex("^(\\s*>\\s?)").find(line)?.value
            out.append(
                Regex("\\{\\{\\s*([A-Za-z]+)\\s*\\}\\}").replace(line) { m ->
                    val v = values[m.groupValues[1]] ?: return@replace m.value
                    if (quote != null) v.replace("\n", "\n$quote") else v
                },
            )
        }
        // An empty note leaves no run of blank lines behind.
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trimEnd() + "\n"
    }

    /** A file name Obsidian and Android both accept: no : / \ ? * " < > | # ^ [ ]. */
    fun safeName(s: String): String =
        s.replace(':', '.').replace(Regex("[/\\\\?*\"<>|#^\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim().ifEmpty { "Verse" }

    /** Links go where the template puts {{links}}, or at the end when it has no such place. */
    private fun withLinks(md: String, template: String, links: List<String>): String =
        if (links.isEmpty() || template.contains(Regex("\\{\\{\\s*links\\s*\\}\\}"))) md
        else md.trimEnd() + "\n\n" + links.joinToString(" · ") + "\n"

    fun build(
        c: Clip,
        append: Boolean,
        folder: String,
        nameTemplate: String = DEFAULT_NAME,
        appendFile: String = "Bible highlights",
        template: String = "",
        entry: String = "",
    ): BuiltNote {
        val v = values(c)
        return if (append) {
            val e = entry.ifBlank { DEFAULT_ENTRY }
            BuiltNote(safeName(appendFile), folder.trim('/'), withLinks(fill(e, v), e, c.links), true)
        } else {
            val t = template.ifBlank { DEFAULT_TEMPLATE }
            // A template that places the tags itself ({{tags}} or {{tagsYaml}}) is left as written.
            val placesTags = Regex("\\{\\{\\s*tags(Yaml)?\\s*\\}\\}").containsMatchIn(t)
            val linked = withLinks(fill(t, v), t, c.links)
            val md = if (placesTags) linked else VaultTemplates.mergeTags(linked, c.tags)
            BuiltNote(safeName(fill(nameTemplate.ifBlank { DEFAULT_NAME }, v).trim()), folder.trim('/'), md, false)
        }
    }
}
