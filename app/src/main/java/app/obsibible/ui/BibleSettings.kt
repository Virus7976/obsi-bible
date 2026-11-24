package app.obsibible.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.obsi.core.ui.ListRow
import app.obsi.core.ui.RowDivider
import app.obsi.core.ui.Section
import app.obsi.core.ui.SettingsEntry
import app.obsibible.data.BiblePrefs

/** Obsi Bible's own pages in the shared Settings, shown first in its menu. */
val BibleSettingsPages = listOf(
    SettingsEntry("bibles", "Bibles and keys", "The ESV, The Message, online Bibles", Icons.AutoMirrored.Rounded.MenuBook),
    SettingsEntry("listening", "Listening", "Voice, next chapter, pause while you write", Icons.Rounded.Headphones),
    SettingsEntry("bibletext", "Bible text", "Red letters, the highlights file", Icons.Rounded.TextFields),
)

/** One of [BibleSettingsPages], as its own screen. */
@Composable
fun BibleSettingsScreen(id: String, onBack: () -> Unit, openSetup: () -> Unit) {
    val app = LocalApp.current
    val s by app.prefs.state.collectAsState()
    var voices by remember { mutableStateOf(false) }
    fun set(block: (BiblePrefs) -> BiblePrefs) = app.prefs.update(block)
    Column(Modifier.fillMaxSize()) {
        TopBack(BibleSettingsPages.firstOrNull { it.id == id }?.title ?: "Settings", onBack = onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (id) {
                "bibles" -> {
                    Section("Setup guide", footer = "Add the ESV, The Message and better voices. The guide has a link for every key.") {
                        ListRow("Open the setup guide", icon = Icons.AutoMirrored.Rounded.MenuBook, onClick = openSetup)
                    }
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        OnlineBibles(openSetup)
                    }
                }
                "listening" -> {
                    Section("Voice") {
                        ListRow("Voice", voiceLabel(s.audioSource), Icons.Rounded.RecordVoiceOver, onClick = { voices = true })
                        RowDivider()
                        SwitchRow("Keep going to the next chapter", null, s.autoContinue) { v -> set { it.copy(autoContinue = v) } }
                        SwitchRow("Pause while you write a note", null, s.pauseWhileNoting) { v -> set { it.copy(pauseWhileNoting = v) } }
                        SwitchRow("Scroll with the audio", null, s.followAudio) { v -> set { it.copy(followAudio = v) } }
                        SwitchRow("Microsoft voices read online Bibles", "The words go to Microsoft.", s.cloudForOnline) { v -> set { it.copy(cloudForOnline = v) } }
                    }
                    Section(
                        "Microsoft voices",
                        footer = "Natural voices from Microsoft Azure Speech. Make a free Speech resource at portal.azure.com, then paste its key and region here. The key stays on this phone.",
                    ) {
                        var key by remember { mutableStateOf(s.cloudKey) }
                        var region by remember { mutableStateOf(s.cloudRegion) }
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                key, { key = it.trim(); set { st -> st.copy(cloudKey = key) } }, Modifier.fillMaxWidth(), label = { Text("Key") }, singleLine = true,
                                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            )
                            OutlinedTextField(region, { region = it.trim().lowercase(); set { st -> st.copy(cloudRegion = region) } }, Modifier.fillMaxWidth(), label = { Text("Region, such as eastus") }, singleLine = true)
                        }
                    }
                }
                "bibletext" -> {
                    Section("Text") {
                        SwitchRow("Jesus' words in red", null, s.redLetters) { v -> set { it.copy(redLetters = v) } }
                    }
                    Section("Highlights file", footer = "One Markdown file per book in your vault, with every highlight and note. It updates by itself.") {
                        SwitchRow("Save every highlight to a file", null, s.highlightsFile) { v -> set { it.copy(highlightsFile = v) } }
                        var hf by remember { mutableStateOf(s.highlightsFolder) }
                        OutlinedTextField(
                            hf, { hf = it; set { st -> st.copy(highlightsFolder = it.trim().trim('/').ifEmpty { "Bible/Highlights" }) } },
                            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), label = { Text("Folder in the vault") }, singleLine = true, enabled = s.highlightsFile,
                        )
                        val synced by app.highlightsSynced.collectAsState()
                        val error by app.highlightsError.collectAsState()
                        error?.let { Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        if (error == null && synced > 0) Text(
                            "Up to date.", Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (voices) VoicePicker(s.bible) { voices = false }
}

@Composable
private fun SwitchRow(title: String, about: String?, on: Boolean, onChange: (Boolean) -> Unit) =
    ListRow(title, about, onClick = { onChange(!on) }) { Switch(on, onChange) }

private fun voiceLabel(source: String): String {
    val (kind, id) = source.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return when (kind) {
        "narrator" -> app.obsibible.audio.Narrators.byId(id)?.name?.let { "$it (recorded)" } ?: "Recorded"
        "cloud" -> "Microsoft · $id"
        else -> if (id.isBlank()) "Phone voice" else "Phone voice · $id"
    }
}
