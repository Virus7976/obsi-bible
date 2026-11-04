package app.obsibible.obsidian

import app.obsibible.data.AppSettings
import app.obsibible.data.ClipTemplate
import app.obsibible.data.TemplateProperty
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BuiltProperty(val property: TemplateProperty, val type: String, val value: String)

/**
 * Builds a note from a template the way ObsiTube does: properties at the top, each filled with
 * Bible data, a fixed value or an answer; then the content. Variables take filters: {{date|date:"YYYY-MM-DD"}}.
 */
object TemplateNote {
    const val VERSE_ID = "bible-verse"
    const val RUNNING_ID = "bible-running"

    val VERSE = ClipTemplate(
        id = VERSE_ID, name = "Bible verse", behavior = "create", noteNameFormat = "{{reference}}", path = "Bible",
        noteContentFormat = "{{content}}",
        properties = listOf(
            TemplateProperty("p-ref", "reference", "{{reference}}", "text"),
            TemplateProperty("p-book", "book", "{{book}}", "text"),
            TemplateProperty("p-ch", "chapter", "{{chapter}}", "number"),
            TemplateProperty("p-vs", "verses", "{{verses}}", "text"),
            TemplateProperty("p-tr", "translation", "{{translation}}", "text"),
            TemplateProperty("p-tags", "tags", "bible", "multitext"),
            TemplateProperty("p-created", "created", "{{date}}", "date"),
        ),
    )

    val RUNNING = ClipTemplate(
        id = RUNNING_ID, name = "Running note", behavior = "append-specific", noteNameFormat = "Bible highlights", path = "Bible",
        noteContentFormat = "## {{reference}} · {{translation}}\n{{content}}\n\n{{date|date:\"YYYY-MM-DD\"}}",
    )

    val BUILT_IN = listOf(VERSE, RUNNING)

    /** The values a template can use, with "content" as the callout, the note and the links. */
    fun variables(c: Clip): Map<String, String> {
        val v = NoteBuilder.values(c).toMutableMap()
        val iso = Instant.ofEpochMilli(c.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        v["date"] = iso
        v["time"] = iso
        v["tags"] = c.tags.map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.joinToString(",")
        v["site"] = "Obsi Bible"
        v["quote"] = c.text.trim().lines().joinToString("\n") { "> $it" }
        // Every line of the verse stays inside the callout.
        val callout = "> [!bible] [${v["reference"]}](${v["link"]}) · ${v["translation"]}\n" + v["quote"]
        v["content"] = listOf(callout, c.note.trim(), c.links.joinToString(" · ")).filter { it.isNotBlank() }.joinToString("\n\n")
        return v
    }

    /** Joins tags into the template's tags property. A value already there is kept. */
    fun withAutos(base: List<TemplateProperty>, autoTags: List<String>): List<TemplateProperty> {
        val out = base.toMutableList()
        fun clean(t: String) = t.trim().removePrefix("#").trim('"')
        val add = autoTags.map(::clean).filter { it.isNotBlank() }
        if (add.isEmpty()) return out
        val i = out.indexOfFirst { it.name.equals("tags", ignoreCase = true) }
        if (i < 0) out += TemplateProperty("auto:tags", "tags", add.joinToString(","), "multitext")
        else {
            val have = splitList(out[i].value).map(::clean).filter { it.isNotBlank() }
            out[i] = out[i].copy(value = (have + add).distinct().joinToString(","))
        }
        return out
    }

    fun build(
        template: ClipTemplate,
        clip: Clip,
        settings: AppSettings,
        valueOverrides: Map<String, String> = emptyMap(),
    ): BuiltNote {
        val vars = variables(clip)
        // Tags typed in the sheet and the always-on tags join the template's tags property.
        val props = withAutos(template.properties, settings.autoTags + clip.tags).map { p ->
            val raw = valueOverrides[p.id] ?: Template.render(p.value, vars)
            BuiltProperty(p, p.type.ifBlank { inferType(raw) }, raw)
        }
        var content = Template.render(template.noteContentFormat, vars)
        // Links go where the template puts {{links}} or {{content}}; else at the end.
        val placesLinks = Regex("\\{\\{\\s*(links|content)\\b").containsMatchIn(template.noteContentFormat)
        if (clip.links.isNotEmpty() && !placesLinks) content = content.trimEnd() + "\n\n" + clip.links.joinToString(" · ")
        content = content.replace(Regex("\n{3,}"), "\n\n").trim() + "\n"
        val name = NoteBuilder.safeName(Template.render(template.noteNameFormat, vars).trim().ifBlank { clip.ref.label })
        val folder = Template.render(template.path, vars).trim().trim('/')
        val md = buildString {
            if (props.isNotEmpty()) {
                append("---\n")
                props.forEach { append(yaml(it)) }
                append("---\n")
            }
            append(content)
        }
        val mode = when (template.behavior) {
            "append-specific" -> "append"
            "prepend-specific" -> "prepend"
            "overwrite" -> "overwrite"
            else -> "create"
        }
        return BuiltNote(name, folder, md, append = mode == "append", body = content, mode = mode)
    }

    /** Obsidian's Automatic: the value decides the type. */
    fun inferType(value: String): String {
        val t = value.trim()
        return when {
            t.equals("true", true) || t.equals("false", true) -> "checkbox"
            Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(t) -> "date"
            Regex("^\\d{4}-\\d{2}-\\d{2}T.*").matches(t) && Template.parseDate(t) != null -> "datetime"
            Regex("^-?\\d+(\\.\\d+)?$").matches(t) -> "number"
            else -> "text"
        }
    }

    private fun quote(s: String) =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace(Regex("\\s*\\n\\s*"), " ").trim() + "\""

    fun splitList(value: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        for (c in value) {
            when {
                c == '[' -> { depth++; cur.append(c) }
                c == ']' -> { depth = (depth - 1).coerceAtLeast(0); cur.append(c) }
                (c == ',' || c == '\n') && depth == 0 -> { out += cur.toString(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString()
        return out.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun yaml(p: BuiltProperty): String {
        val key = if (Regex("^[A-Za-z0-9_ -]+$").matches(p.property.name)) p.property.name else quote(p.property.name)
        val v = p.value.trim()
        return when (p.type) {
            "multitext" -> {
                val items = splitList(v)
                if (items.isEmpty()) "$key:\n" else "$key:\n" + items.joinToString("") { "  - ${quote(it)}\n" }
            }
            "date" -> if (v.isEmpty()) "$key:\n" else "$key: ${Template.formatDate(v, "YYYY-MM-DD") ?: v}\n"
            "datetime" -> if (v.isEmpty()) "$key:\n" else "$key: ${Template.formatDate(v, "YYYY-MM-DD[T]HH:mm:ss") ?: v}\n"
            "number" -> if (v.toDoubleOrNull() != null) "$key: $v\n" else "$key:\n"
            "checkbox" -> "$key: ${v.equals("true", true) || v == "1" || v.equals("yes", true)}\n"
            else -> if (v.isEmpty()) "$key:\n" else "$key: ${quote(v)}\n"
        }
    }

    /** The template list with the built-in ones first added, and an old custom template kept. */
    fun seeded(s: AppSettings): AppSettings {
        if (s.templates.isNotEmpty()) return s
        val list = BUILT_IN.map { t ->
            when (t.id) {
                VERSE_ID -> t.copy(path = s.clipFolder, noteNameFormat = s.clipName.ifBlank { t.noteNameFormat })
                RUNNING_ID -> t.copy(path = s.clipFolder, noteNameFormat = s.clipFile.ifBlank { t.noteNameFormat })
                else -> t
            }
        }.toMutableList()
        var selected = if (s.clipAppend) RUNNING_ID else VERSE_ID
        if (s.clipTemplate.isNotBlank()) {
            list += ClipTemplate("my-template", "My template", "create", s.clipName.ifBlank { "{{reference}}" }, s.clipFolder, s.clipTemplate)
            if (!s.clipAppend) selected = "my-template"
        }
        return s.copy(templates = list, selectedTemplateId = selected)
    }
}
