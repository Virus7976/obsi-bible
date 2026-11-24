package app.obsibible.data

import kotlinx.serialization.Serializable

/**
 * What only Obsi Bible sets. Kept on the phone in bible.json. The look, Obsidian, templates and updates are
 * in the shared settings. API keys live here too. The file is never backed up and never leaves the phone.
 */
@Serializable
data class BiblePrefs(
    /** The words of Jesus in red. */
    val redLetters: Boolean = true,
    // reading
    val bible: String = "BSB",
    val lastRef: String = "JHN.1",
    val recentSearches: List<String> = emptyList(),
    // audio: narrator:<id> | phone:<voice name> | cloud:<voice short name>
    val audioSource: String = "narrator:souer",
    val audioSpeed: Float = 1f,
    val autoContinue: Boolean = true,
    val pauseWhileNoting: Boolean = true,
    /** Microsoft's voices may read online Bibles too (the words go to Microsoft). */
    val cloudForOnline: Boolean = true,
    val followAudio: Boolean = true,
    val phoneVoice: String = "",
    // Microsoft (Azure Speech) voices, with the user's own key
    val cloudKey: String = "",
    val cloudRegion: String = "",
    val cloudVoice: String = "en-US-AndrewNeural",
    // online Bibles: one key per source, typed in by the user
    val esvKey: String = "",
    val youVersionKey: String = "",
    val apiBibleKey: String = "",
    // API.Bible Bibles the user added: "id|abbreviation|name|copyright"
    val onlineBibles: List<String> = emptyList(),
    // highlights
    val lastColor: Int = 0,
    /** Every highlight is kept in one Markdown file per book in this vault folder. */
    val highlightsFile: Boolean = true,
    /** Empty means the shared Obsi folder: Obsi/Bible/Highlights. */
    val highlightsFolder: String = "",
    /** 1 once the notes from before the shared Obsi folder were moved into it. */
    val foldersVersion: Int = 0,
    val clipFolder: String = "Bible",
    val clipAppend: Boolean = false,
    val clipFile: String = "Bible highlights",
    val clipName: String = "",
    val clipTemplate: String = "",
    val clipEntry: String = "",
    // The vault note used as the template for new notes ("" = built in), and its name.
    val clipVaultTemplate: String = "",
    val clipVaultTemplateName: String = "",
)

@Serializable
data class TemplateProperty(
    val id: String,
    val name: String,
    val value: String = "",
    val type: String = "",
    /** A box for it opens before the note saves. The value is what the box starts with. */
    val ask: Boolean = false,
)

@Serializable
data class ClipTemplate(
    val id: String,
    val name: String,
    /** create | overwrite | append-specific | prepend-specific */
    val behavior: String = "create",
    val noteNameFormat: String = "{{reference}}",
    val path: String = "Bible",
    val noteContentFormat: String = "{{content}}",
    val properties: List<TemplateProperty> = emptyList(),
    /** The vault note this came from (its path), and the document to read it again. */
    val source: String = "",
    val sourceUri: String = "",
)

/**
 * The marker colours. New colours go at the end, because saved highlights remember a colour by its place here.
 */
data class HighlightColor(val name: String, val ui: Long, val emoji: String)

val HighlightColors = listOf(
    HighlightColor("Yellow", 0xFFF2C14E, "🟡"),
    HighlightColor("Red", 0xFFFF6B6B, "🔴"),
    HighlightColor("Green", 0xFF5BD98C, "🟢"),
    HighlightColor("Blue", 0xFF6AA8FF, "🔵"),
    HighlightColor("Purple", 0xFFB58CFF, "🟣"),
    HighlightColor("Orange", 0xFFF0A04B, "🟠"),
)
