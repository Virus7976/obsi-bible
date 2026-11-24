package app.obsibible.obsidian

import app.obsi.core.data.AppSettings
import app.obsi.core.obsidian.ObsiFolder
import app.obsi.core.data.ClipTemplate
import app.obsi.core.data.ObsidianText
import app.obsi.core.data.TemplateProperty
import app.obsi.core.obsidian.BuiltNote
import app.obsi.core.obsidian.BuiltProperty
import app.obsi.core.obsidian.MergeResult
import app.obsi.core.obsidian.NoteLists
import app.obsi.core.obsidian.Template
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Builds a verse note from a template, the same way the other Obsi apps build theirs: properties at the
 * top, each filled with Bible data, a fixed value or an answer; then the content. The note is saved by the
 * shared save sheet, so templates, vaults and folders work as in ObsiRead.
 */
object VerseNote {
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
    private fun withTags(base: List<TemplateProperty>, tags: List<String>): List<TemplateProperty> {
        val out = base.toMutableList()
        fun clean(t: String) = t.trim().removePrefix("#").trim('"')
        val add = tags.map(::clean).filter { it.isNotBlank() }
        if (add.isEmpty()) return out
        val i = out.indexOfFirst { it.name.equals("tags", ignoreCase = true) }
        if (i < 0) out += TemplateProperty("auto:tags", "tags", add.joinToString(","), "multitext")
        else {
            val have = NoteLists.split(out[i].value).map(::clean).filter { it.isNotBlank() }
            out[i] = out[i].copy(value = (have + add).distinct().joinToString(","))
        }
        return out
    }

    fun build(
        template: ClipTemplate,
        clip: Clip,
        settings: AppSettings,
        overrides: Map<String, String> = emptyMap(),
        name: String? = null,
        folder: String? = null,
        vault: String? = null,
    ): BuiltNote {
        val vars = variables(clip)
        val props = withTags(template.properties, settings.autoTags + clip.tags).map { p ->
            val raw = overrides[p.id] ?: Template.render(p.value, vars)
            BuiltProperty(p, p.type.ifBlank { settings.propertyTypes[p.name] ?: inferType(raw) }, raw)
        }
        var content = Template.render(template.noteContentFormat, vars)
        // Links go where the template puts {{links}} or {{content}}; else at the end.
        val placesLinks = Regex("\\{\\{\\s*(links|content)\\b").containsMatchIn(template.noteContentFormat)
        if (clip.links.isNotEmpty() && !placesLinks) content = content.trimEnd() + "\n\n" + clip.links.joinToString(" · ")
        content = content.replace(Regex("\n{3,}"), "\n\n").trim() + "\n"
        val noteName = NoteBuilder.safeName(name ?: Template.render(template.noteNameFormat, vars).trim().ifBlank { clip.ref.label })
        val noteFolder = (folder ?: Template.render(template.path, vars)).trim().trim('/')
        val md = buildString {
            if (props.isNotEmpty()) {
                append("---\n")
                props.forEach { append(yaml(it)) }
                append("---\n\n")
            }
            append(content)
        }
        return BuiltNote(vault ?: settings.vault.ifBlank { template.vault }, noteFolder, noteName, template.behavior, props, content, md)
    }

    /** A note that exists gets the verse added once, at the end, and the properties it is missing. */
    fun merge(existing: String, note: BuiltNote): MergeResult {
        val (fm0, body0) = ObsidianText.splitFrontmatter(existing)
        var fm = fm0
        var addedProps = 0
        val have = readValues(existing)
        for (p in note.properties) {
            if (p.value.isBlank() || p.property.name.lowercase() in have) continue
            fm = fm.trimEnd('\n') + (if (fm.isBlank()) "" else "\n") + yaml(p).trimEnd('\n')
            addedProps++
        }
        val firstLine = note.content.trim().lineSequence().firstOrNull().orEmpty()
        val already = firstLine.isNotBlank() && body0.contains(firstLine)
        val body = if (already) body0 else body0.trimEnd() + "\n\n" + note.content.trim() + "\n"
        val text = if (fm.isBlank()) body else "---\n" + fm.trim('\n') + "\n---\n" + body.trimStart('\n').let { "\n$it" }
        return MergeResult(text, if (already) 0 else 1, addedProps)
    }

    /** The property values a note has, by lower-case name. A list is joined with commas. */
    fun readValues(text: String): Map<String, String> {
        val fm = ObsidianText.splitFrontmatter(text).first
        val out = LinkedHashMap<String, String>()
        var key: String? = null
        val items = ArrayList<String>()
        fun flush() {
            val k = key ?: return
            if (items.isNotEmpty()) out[k] = items.joinToString(",")
            items.clear()
        }
        val head = Regex("^([^\\s#-][^:]*?)\\s*:\\s*(.*)$")
        for (line in fm.lines()) {
            val item = Regex("^\\s+-\\s*(.*)$").find(line)
            if (item != null && key != null) { items += item.groupValues[1].trim().trim('"', '\''); continue }
            val m = head.find(line) ?: continue
            flush()
            key = m.groupValues[1].trim().lowercase()
            val v = m.groupValues[2].trim().trim('"', '\'')
            if (v.isNotEmpty()) out[key!!] = v
        }
        flush()
        return out
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

    fun yaml(p: BuiltProperty): String {
        val key = if (Regex("^[A-Za-z0-9_ -]+$").matches(p.property.name)) p.property.name else quote(p.property.name)
        val v = p.value.trim()
        return when (p.type) {
            "multitext", "tags", "aliases" -> {
                val items = NoteLists.split(v)
                if (items.isEmpty()) "$key:\n" else "$key:\n" + items.joinToString("") { "  - ${quote(it)}\n" }
            }
            "date" -> if (v.isEmpty()) "$key:\n" else "$key: ${Template.formatDate(v, "YYYY-MM-DD") ?: v}\n"
            "datetime" -> if (v.isEmpty()) "$key:\n" else "$key: ${Template.formatDate(v, "YYYY-MM-DD[T]HH:mm:ss") ?: v}\n"
            "number" -> if (v.toDoubleOrNull() != null) "$key: $v\n" else "$key:\n"
            "checkbox" -> "$key: ${v.equals("true", true) || v == "1" || v.equals("yes", true)}\n"
            else -> if (v.isEmpty()) "$key:\n" else "$key: ${quote(v)}\n"
        }
    }

    /** The shared template list with the two Bible templates added once, when it has none of its own. They save to Obsi/Bible. */
    fun seeded(s: AppSettings): AppSettings {
        if (s.templates.any { it.id == VERSE_ID || it.id == RUNNING_ID } || s.templates.isNotEmpty()) return s
        val folder = ObsiFolder.of(s)
        return s.copy(templates = BUILT_IN.map { it.copy(path = folder) }, selectedTemplateId = VERSE_ID)
    }

    /** The Bible templates still on the old "Bible" folder go to Obsi/Bible. True when one changed. */
    fun toObsiFolder(s: AppSettings): AppSettings = s.copy(templates = s.templates.map {
        if ((it.id == VERSE_ID || it.id == RUNNING_ID) && it.path.trim('/') == OLD_FOLDER) it.copy(path = ObsiFolder.of(s)) else it
    })

    /** Where Bible notes went before the shared Obsi folder. */
    const val OLD_FOLDER = "Bible"
    const val OLD_HIGHLIGHTS = "Bible/Highlights"
}
