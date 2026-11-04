package app.obsibible.data

import kotlinx.serialization.Serializable

/**
 * Everything the user can set. Kept on the phone in settings.json.
 * API keys live here too. The file is never backed up and never leaves the phone.
 */
@Serializable
data class AppSettings(
    // look
    val appearance: String = "dark", // auto | light | dark | oled
    val readerFontSize: Float = 20f,
    val lineHeight: Float = 1.4f,
    /** Set once the 0.6 reader look moved old line spacing to the new default. */
    val lookV3: Boolean = false,
    // reading
    val bible: String = "BSB",
    val lastRef: String = "JHN.1",
    val recentSearches: List<String> = emptyList(),
    // audio: narrator:<id> | phone:<voice name> | cloud:<voice short name>
    val audioSource: String = "narrator:souer",
    val audioSpeed: Float = 1f,
    val autoContinue: Boolean = true,
    val pauseWhileNoting: Boolean = true,
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
    // false until the setup guide was finished or skipped once
    val onboarded: Boolean = false,
    // updates
    val autoCheckUpdates: Boolean = true,
    val updateRepo: String = "Virus7976/obsi-bible",
    val updateToken: String = "",
    val updateMirror: String = "",
    val ignoredVersion: String = "",
    val installMethod: String = "system", // system | shizuku | adb
    val lastUpdateCheck: Long = 0L,
    val notifiedVersion: String = "",
    val askedNotifications: Boolean = false,
    // highlights
    val lastColor: Int = 0,
    // Obsidian: the vault folder picked with Android's folder picker, and how notes are made
    val vaultTree: String = "",
    /** Every highlight is kept in one Markdown file per book in this vault folder. */
    val highlightsFile: Boolean = true,
    val highlightsFolder: String = "Bible/Highlights",
    val vaultName: String = "",
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
