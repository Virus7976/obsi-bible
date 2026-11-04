package app.obsibible.obsidian

import app.obsibible.data.ClipTemplate
import app.obsibible.data.TemplateProperty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/**
 * Turns a note from the vault into a clip template. A property with a value keeps it. An empty one
 * is matched to Bible data by its name, and if nothing fits it is asked for at every save.
 */
object VaultTemplate {
    private class Field(val name: String, val value: String, val list: Boolean)

    private val blockMarks = setOf("|", ">", "|-", ">-")
    private val head = Regex("^([^\\s#-][^:]*?)\\s*:(?:\\s+(.*))?$")
    private val datedTag = Regex("\\{\\{\\s*(date|time)\\s*:\\s*([^}|]+?)\\s*\\}\\}")
    private val bodyVars = Regex("\\{\\{\\s*(content|text|quote)\\b")

    private fun unquote(v: String): String {
        val t = v.trim()
        return if (t.length >= 2 && (t.first() == '"' || t.first() == '\'') && t.last() == t.first()) t.substring(1, t.length - 1) else t
    }

    private fun fields(frontmatter: String): List<Field> {
        val out = ArrayList<Field>()
        var key: String? = null
        var list = false
        var block = false
        val items = ArrayList<String>()
        fun flush() {
            key?.let { out += Field(it, items.filter { i -> i.isNotBlank() }.joinToString(if (block) " " else ","), list) }
            items.clear()
            list = false
            block = false
        }
        for (line in frontmatter.lines()) {
            if (line.isBlank() || line.trimStart().startsWith("#")) continue
            val m = head.find(line)
            when {
                m != null -> {
                    flush()
                    key = unquote(m.groupValues[1])
                    val v = m.groupValues[2].trim()
                    when {
                        v == "[]" -> list = true
                        v in blockMarks -> block = true
                        v.startsWith("[") && !v.startsWith("[[") -> {
                            list = true
                            items += TemplateNote.splitList(v.removeSurrounding("[", "]")).map(::unquote)
                        }
                        v.isNotEmpty() -> items += unquote(v)
                    }
                }
                key != null && block -> items += line.trim()
                key != null && line.trim().startsWith("-") -> {
                    list = true
                    items += unquote(line.trim().removePrefix("-"))
                }
            }
        }
        flush()
        return out
    }

    /** A note's short property values, by lower-case name. Long text is not worth offering again. */
    fun values(frontmatter: String): Map<String, List<String>> = fields(frontmatter.replace("\r\n", "\n"))
        .mapNotNull { f ->
            val list = (if (f.list) TemplateNote.splitList(f.value) else listOf(f.value.trim()))
                .filter { it.isNotBlank() && it.length <= 60 && !it.contains("{{") }.take(20)
            if (list.isEmpty()) null else f.name.lowercase() to list
        }.toMap()

    /** Obsidian's own {{date}}, {{time}} and {{date:FORMAT}} in the form the clip template language reads. */
    fun clean(s: String): String = s
        .replace(datedTag) { "{{date|date:\"" + it.groupValues[2] + "\"}}" }
        .replace(Regex("\\{\\{\\s*date\\s*\\}\\}"), Regex.escapeReplacement("{{date|date:\"YYYY-MM-DD\"}}"))
        .replace(Regex("\\{\\{\\s*time\\s*\\}\\}"), Regex.escapeReplacement("{{date|date:\"HH:mm\"}}"))

    private fun typeOf(name: String, value: String, list: Boolean, vaultTypes: Map<String, String>): String {
        when (val set = vaultTypes.entries.firstOrNull { it.key.equals(name, true) }?.value) {
            "tags", "aliases", "multitext" -> return "multitext"
            "text", "number", "date", "datetime", "checkbox" -> return set
        }
        return when {
            list -> "multitext"
            value.contains("{{date|date:\"HH:mm") -> "datetime"
            value.startsWith("{{date") -> "date"
            else -> ""
        }
    }

    /** The Bible data an empty property most likely wants, going by its name. */
    fun guess(name: String, type: String): String? {
        val n = name.lowercase().replace(Regex("[^a-z0-9]"), "")
        return when (n) {
            "reference", "ref", "verse", "verses", "passage", "scripture", "title" -> if (n == "verses") "{{verses}}" else "{{reference}}"
            "book" -> "{{book}}"
            "chapter" -> "{{chapter}}"
            "translation", "version", "bible" -> "{{translation}}"
            "created", "clipped", "saved", "added", "date", "dateadded", "dateclipped" -> "{{date}}"
            "url", "source", "link", "sourceurl" -> "{{link}}"
            "text", "quote" -> "{{text}}"
            "note", "notes" -> "{{note}}"
            "color", "colour", "highlight" -> "{{color}}"
            "copyright" -> "{{copyright}}"
            "site", "platform" -> "{{site}}"
            else -> null
        }
    }

    /**
     * [previous] is the template made from this same note earlier. Your choices for each property
     * stay, new properties are set up, and properties the note no longer has go away.
     */
    fun read(text: String, path: String, vaultTypes: Map<String, String>, previous: ClipTemplate? = null): ClipTemplate {
        val (fm, body) = splitFrontmatter(text.replace("\r\n", "\n"))
        val props = fields(fm).map { f ->
            previous?.properties?.firstOrNull { it.name.equals(f.name, true) }?.let { return@map it }
            val value = clean(f.value)
            val type = typeOf(f.name, value, f.list, vaultTypes)
            val guessed = if (value.isBlank()) guess(f.name, type) else null
            TemplateProperty(
                UUID.randomUUID().toString(), f.name, guessed ?: value, type,
                ask = value.isBlank() && guessed == null && !f.name.equals("tags", true),
            )
        }
        val written = clean(body.trim())
        // A note without the verse in it would lose the point of the note.
        val content = when {
            written.isBlank() -> "{{content}}"
            bodyVars.containsMatchIn(written) -> written
            else -> "$written\n\n{{content}}"
        }
        val base = previous ?: ClipTemplate(
            id = UUID.randomUUID().toString().take(12),
            name = path.substringAfterLast('/').removeSuffix(".md"),
        )
        return base.copy(source = path, noteContentFormat = content, properties = props)
    }

    private fun block(name: String, value: String, type: String): String {
        val key = if (Regex("^[A-Za-z0-9_ -]+$").matches(name)) name else "\"$name\""
        fun q(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return when {
            type == "multitext" && value.isBlank() -> "$key: []"
            type == "multitext" -> "$key:\n" + TemplateNote.splitList(value).joinToString("\n") { "  - " + q(it) }
            value.isBlank() -> "$key:"
            value.startsWith("{{") || type in setOf("date", "datetime", "number", "checkbox") -> "$key: $value"
            else -> "$key: " + q(value)
        }
    }

    /**
     * The vault note with this template's changes in it, or null when it already matches.
     * Bible data and Ask me stay in the app, so those properties sit empty in the note, and a
     * line that still means the same is left exactly as it was written.
     */
    fun writeBack(text: String, t: ClipTemplate, read: ClipTemplate): String? {
        val (fm, body) = splitFrontmatter(text.replace("\r\n", "\n"))
        val blocks = ArrayList<Pair<String, ArrayList<String>>>()
        for (line in fm.lines()) {
            val m = if (line.isBlank() || line.trimStart().startsWith("#")) null else head.find(line)
            if (m != null) blocks += unquote(m.groupValues[1]) to arrayListOf(line)
            else if (blocks.isNotEmpty() && line.isNotBlank()) blocks.last().second += line
        }
        val have = fields(fm).associateBy { it.name.lowercase() }
        val lines = t.properties.joinToString("\n") { p ->
            val old = have[p.name.lowercase()]
            val raw = blocks.firstOrNull { it.first.equals(p.name, true) }?.second?.joinToString("\n")
            val fixed = BibleData.ruleOf(p) == PropertyRule.Fixed && !p.ask
            val same = old != null && clean(old.value) == p.value
            when {
                raw != null && (same || (old?.value.orEmpty().isBlank() && !fixed)) -> raw
                else -> block(p.name, if (fixed) p.value else "", p.type)
            }
        }
        val newBody = if (t.noteContentFormat == read.noteContentFormat) body else t.noteContentFormat
        if (lines.trim() == fm.trim() && newBody == body) return null
        val top = if (t.properties.isEmpty()) "" else "---\n$lines\n---\n"
        return top + (if (newBody.isBlank()) "\n" else "\n$newBody")
    }

    /** The vault's types.json with the types set in the app, or null when nothing changed. */
    fun writeTypes(json: String, t: ClipTemplate): String? {
        val all = root(json) ?: return null
        val old = (all["types"] as? JsonObject)?.toMutableMap() ?: return null
        var changed = false
        for (p in t.properties) {
            if (p.type.isBlank() || p.name.equals("tags", true) || p.name.equals("aliases", true)) continue
            val key = old.keys.firstOrNull { it.equals(p.name, true) } ?: p.name
            if ((old[key] as? JsonPrimitive)?.contentOrNull != p.type) { old[key] = JsonPrimitive(p.type); changed = true }
        }
        if (!changed) return null
        val pretty = Json { prettyPrint = true; prettyPrintIndent = "  " }
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(all + ("types" to JsonObject(old))))
    }

    private fun root(json: String): JsonObject? = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()

    /** Property name to type, from the vault's .obsidian/types.json. */
    fun types(json: String): Map<String, String> =
        (root(json)?.get("types") as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()

    /** The folder set in Obsidian's Templates plugin, from .obsidian/templates.json. */
    fun folder(json: String): String = (root(json)?.get("folder") as? JsonPrimitive)?.contentOrNull.orEmpty().trim('/')

    /** The frontmatter and the rest of a note. */
    fun splitFrontmatter(text: String): Pair<String, String> {
        val t = text.removePrefix("\uFEFF")
        if (!t.startsWith("---")) return "" to t
        val end = Regex("\n---[ \t]*(\n|$)").find(t, 3) ?: return "" to t
        return t.substring(4.coerceAtMost(t.length), end.range.first + 1) to t.substring(end.range.last + 1).trimStart('\n')
    }
}
