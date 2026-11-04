package app.obsibible.obsidian

import app.obsibible.data.TemplateProperty

/** One thing the app knows about a verse, and the property types it can go into. */
class DataField(val key: String, val label: String, val types: Set<String>)

enum class PropertyRule { Ask, Data, Fixed, Empty }

/** The Bible data a template property can be filled with, as ObsiTube's video data. */
object BibleData {
    private val words = setOf("text", "multitext")
    private val dates = words + setOf("date", "datetime")
    private val numbers = words + "number"

    val fields = listOf(
        DataField("reference", "Reference, such as John 3:16", words),
        DataField("book", "Book", words),
        DataField("chapter", "Chapter", numbers),
        DataField("verses", "Verses", words),
        DataField("translation", "Translation, such as ESV", words),
        DataField("translationName", "Translation, full name", words),
        DataField("text", "Verse text", words),
        DataField("note", "Your note", words),
        DataField("link", "Link back to the verse", words),
        DataField("links", "Links you added", words),
        DataField("tags", "Tags you added", words),
        DataField("date", "Date you send it", dates),
        DataField("color", "Highlight colour", words),
        DataField("copyright", "Copyright line", words),
        DataField("site", "Site", words),
    )

    private val single = Regex("^\\{\\{\\s*([A-Za-z]+)\\s*(\\|[^{}]*)?\\}\\}$")

    /** Automatic takes anything. A checkbox takes no Bible data. */
    fun forType(type: String): List<DataField> = if (type.isBlank()) fields else fields.filter { type in it.types }

    /** The field behind a value that is one data tag and nothing else. */
    fun fieldOf(value: String): DataField? =
        single.find(value.trim())?.groupValues?.get(1)?.let { key -> fields.firstOrNull { it.key == key } }

    /** What to store for a field. A date in a text box reads better as a plain day. */
    fun valueFor(field: DataField, type: String): String =
        if (field.key == "date" && type != "date" && type != "datetime") "{{date|date:\"YYYY-MM-DD\"}}" else "{{${field.key}}}"

    fun ruleOf(p: TemplateProperty): PropertyRule = when {
        p.ask -> PropertyRule.Ask
        p.value.isBlank() -> PropertyRule.Empty
        fieldOf(p.value) != null -> PropertyRule.Data
        else -> PropertyRule.Fixed
    }
}
