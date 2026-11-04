package app.obsibible.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.obsibible.data.AppSettings
import app.obsibible.data.ClipTemplate
import app.obsibible.obsidian.Sender
import app.obsibible.obsidian.TemplateNote
import app.obsibible.obsidian.TemplateSync
import app.obsibible.obsidian.VaultIndex
import app.obsibible.obsidian.VaultTemplates
import app.obsibible.update.UpdateNotice
import app.obsibible.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** One page of settings, with the words a search should find it by. */
private class SettingsPage(val id: String, val title: String, val hint: String, val icon: ImageVector, val words: String)

private val pages = listOf(
    SettingsPage("bibles", "Bibles and keys", "The ESV, The Message, the setup guide", Icons.AutoMirrored.Rounded.MenuBook,
        "bibles keys setup guide onboarding esv the message msg api.bible youversion key links versions online"),
    SettingsPage("look", "Look", "Theme, black, text size, app icon", Icons.Rounded.Palette,
        "look appearance theme dark light black amoled oled colour color icon app icon text size font line spacing reading"),
    SettingsPage("listening", "Listening", "Voice, next chapter, pause while you write", Icons.Rounded.Headphones,
        "listening audio voice speech narrator next chapter pause note follow scroll sync back microsoft azure key region phone voice"),
    SettingsPage("obsidian", "Obsidian", "Vault, highlights file, templates, tags", Icons.Rounded.Description,
        "obsidian vault folder template templates note name running note clip send export tags links properties highlights file"),
    SettingsPage("updates", "Updates", "Check for and install new versions", Icons.Rounded.SystemUpdate,
        "updates version install check notifications release"),
)

@Composable
fun SettingsScreen(onBack: () -> Unit, openSetup: () -> Unit = {}, openTemplate: (String) -> Unit = {}) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    var voices by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // null is the menu; otherwise the id of the open page
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    BackHandler(enabled = page != null) { page = null }
    val open = pages.firstOrNull { it.id == page }

    Column(Modifier.fillMaxSize()) {
        TopBack(open?.title ?: "Settings", onBack = { if (page != null) page = null else onBack() })
        if (open == null) {
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                placeholder = { Text("Search settings") }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") } },
                shape = RoundedCornerShape(28.dp),
            )
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(if (open == null && query.isBlank()) 6.dp else 28.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            when {
                open != null -> PageBody(open.id, s, openSetup, openTemplate) { voices = true }
                query.isBlank() -> pages.forEach { p -> MenuRow(p) { page = p.id } }
                else -> {
                    // While searching, every page that matches shows in full.
                    val hits = pages.filter { SettingsSearch.matches(query, it.title + " " + it.words) }
                    hits.forEach { p -> Group(p.title) { PageBody(p.id, s, openSetup, openTemplate) { voices = true } } }
                    if (hits.isEmpty()) Text("No setting matches \"$query\".", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
    if (voices) VoicePicker(s.bible) { voices = false }
}

@Composable
private fun MenuRow(p: SettingsPage, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surfaceContainer).clickable(onClick = onClick).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(p.icon, null, Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.title, style = MaterialTheme.typography.titleMedium)
            Text(p.hint, color = scheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
    }
}

@Composable
private fun PageBody(id: String, s: AppSettings, openSetup: () -> Unit, openTemplate: (String) -> Unit, openVoices: () -> Unit) {
    val app = LocalApp.current
    val set: ((AppSettings) -> AppSettings) -> Unit = { app.settings.update(it) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when (id) {
            "bibles" -> {
                Text("Add the ESV, The Message and better voices. The guide has a link for every key.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = openSetup) { Text("Open the setup guide") }
                OnlineBibles(openSetup)
            }
            "look" -> {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Choices(listOf("dark" to "Dark", "oled" to "Black", "light" to "Light", "auto" to "Phone"), s.appearance) { v -> set { it.copy(appearance = v) } }
                Text("Text size", style = MaterialTheme.typography.titleSmall)
                Slider(s.readerFontSize, { v -> set { it.copy(readerFontSize = v) } }, valueRange = 15f..32f)
                Text("Line spacing", style = MaterialTheme.typography.titleSmall)
                Slider(s.lineHeight, { v -> set { it.copy(lineHeight = v) } }, valueRange = 1.1f..2.0f)
                AppIconPicker()
            }
            "listening" -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice", style = MaterialTheme.typography.titleSmall)
                        Text(voiceLabel(s.audioSource), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = openVoices) { Text("Change") }
                }
                Toggle("Keep going to the next chapter", s.autoContinue) { v -> set { it.copy(autoContinue = v) } }
                Toggle("Pause while you write a note", s.pauseWhileNoting) { v -> set { it.copy(pauseWhileNoting = v) } }
                Toggle("Microsoft voices read online Bibles", s.cloudForOnline) { v -> set { it.copy(cloudForOnline = v) } }
                Toggle("Scroll with the audio", s.followAudio) { v -> set { it.copy(followAudio = v) } }
                Text("Microsoft voices", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Natural voices from Microsoft Azure Speech. Make a free Speech resource at portal.azure.com, then paste its key and region here. The key stays on this phone.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var key by remember { mutableStateOf(s.cloudKey) }
                var region by remember { mutableStateOf(s.cloudRegion) }
                OutlinedTextField(
                    key, { key = it.trim(); set { st -> st.copy(cloudKey = key) } }, Modifier.fillMaxWidth(), label = { Text("Key") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                OutlinedTextField(region, { region = it.trim().lowercase(); set { st -> st.copy(cloudRegion = region) } }, Modifier.fillMaxWidth(), label = { Text("Region, such as eastus") }, singleLine = true)
            }
            "obsidian" -> ObsidianPage(s, openTemplate)
            "updates" -> UpdateGroup()
        }
    }
}

private fun voiceLabel(source: String): String {
    val (kind, id) = source.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
    return when (kind) {
        "narrator" -> app.obsibible.audio.Narrators.byId(id)?.name?.let { "$it (recorded)" } ?: "Recorded"
        "cloud" -> "Microsoft · $id"
        else -> if (id.isBlank()) "Phone voice" else "Phone voice · $id"
    }
}

@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        content()
    }
}

@Composable
fun Choices(options: List<Pair<String, String>>, picked: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (v, label) ->
            FilterChip(selected = v == picked, onClick = { onPick(v) }, label = { Text(label) })
        }
    }
}

@Composable
fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(on, onChange, colors = SwitchDefaults.colors(checkedTrackColor = Ink.dot, checkedThumbColor = scheme.surface))
    }
}

@Composable
private fun UpdateGroup() {
    val app = LocalApp.current
    val up = app.updates
    val s by app.settings.state.collectAsState()
    val state by up.state.collectAsState()
    val notice by up.notice.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val context = LocalContext.current
    var canNotify by remember { mutableStateOf(UpdateNotice.canNotify(context)) }
    LifecycleResumeEffect(Unit) { canNotify = UpdateNotice.canNotify(context); onPauseOrDispose { } }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Version ${up.currentVersion}", style = MaterialTheme.typography.titleSmall)
        when (val u = state) {
            is UpdateState.Idle, is UpdateState.UpToDate -> {
                val ago = if (s.lastUpdateCheck > 0) android.text.format.DateUtils.getRelativeTimeSpanString(s.lastUpdateCheck, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS).toString() else null
                Text(
                    when {
                        u is UpdateState.UpToDate -> "You have the newest version."
                        ago != null -> "Last checked ${ago.replaceFirstChar { it.lowercase() }}."
                        else -> "Not checked yet."
                    },
                    color = muted,
                )
                Button(onClick = { up.check() }) { Text("Check for updates") }
            }
            is UpdateState.Checking -> {
                Text("Checking…", color = muted)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.Available -> {
                Text("Version ${u.info.version} is ready", style = MaterialTheme.typography.titleMedium)
                val notes = cleanNotes(u.info.notes)
                if (notes.isNotBlank()) Text(notes, style = MaterialTheme.typography.bodyMedium, color = muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { up.installFlow(u.info) }) { Text("Install") }
                    TextButton(onClick = { up.ignore(u.info) }) { Text("Skip this version") }
                }
            }
            is UpdateState.Downloading -> {
                Text("Downloading ${u.info.version}")
                if (u.total > 0) LinearProgressIndicator(progress = { (u.done.toFloat() / u.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.Ready -> Button(onClick = { up.install(u) }) { Text("Install now") }
            is UpdateState.Installing -> {
                Text("Installing ${u.info.version}")
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            is UpdateState.Failed -> {
                Text(u.message, color = MaterialTheme.colorScheme.error)
                Button(onClick = { up.check() }) { Text("Try again") }
            }
        }
        notice?.let { Text(it, color = muted) }
        Toggle("Check for updates on its own", s.autoCheckUpdates) { v -> app.settings.update { it.copy(autoCheckUpdates = v) } }
        if (!canNotify) {
            Text("Notifications are off, so the app can only tell you about updates while it is open.", color = muted)
            OutlinedButton(onClick = {
                context.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Turn on notifications") }
        }
    }
}

/** The vault, the highlights files, the note templates and the tags every note gets. */
@Composable
private fun ObsidianPage(s: AppSettings, openTemplate: (String) -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val muted = scheme.onSurfaceVariant
    val scope = rememberCoroutineScope()
    val pick = rememberVaultPicker { VaultIndex.forget() }
    val folder = remember(s.vaultTree) { if (s.vaultTree.isBlank()) null else Sender.folderName(context, s.vaultTree) }

    Text("Vault", style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(22.dp), tint = muted)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder ?: "Not picked", style = MaterialTheme.typography.titleSmall)
            Text(if (folder == null) "Without a folder, notes go through the Obsidian app." else "Notes are written straight into this folder.", color = muted, fontSize = 14.sp)
        }
        OutlinedButton(onClick = pick) { Text(if (folder == null) "Pick" else "Change") }
    }
    var vault by remember { mutableStateOf(s.vaultName) }
    OutlinedTextField(vault, { vault = it; app.settings.update { st -> st.copy(vaultName = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("Vault name in Obsidian") }, singleLine = true)

    Spacer(Modifier.height(6.dp))
    Text("Highlights file", style = MaterialTheme.typography.titleMedium)
    Toggle("Save every highlight to a file", s.highlightsFile) { v -> app.settings.update { it.copy(highlightsFile = v) } }
    var hf by remember { mutableStateOf(s.highlightsFolder) }
    OutlinedTextField(hf, { hf = it; app.settings.update { st -> st.copy(highlightsFolder = it.trim().trim('/').ifEmpty { "Bible/Highlights" }) } },
        Modifier.fillMaxWidth(), label = { Text("Folder, one file per book") }, singleLine = true, enabled = s.highlightsFile)

    Spacer(Modifier.height(6.dp))
    Text("Templates", style = MaterialTheme.typography.titleMedium)
    Text("A template sets a note's name, folder, properties and words. Tap one to change it.", color = muted, fontSize = 14.sp)
    s.templates.forEach { t -> TemplateRow(t, t.id == s.selectedTemplateId) { openTemplate(t.id) } }
    var fromVault by remember { mutableStateOf<List<VaultTemplates.Entry>?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val id = UUID.randomUUID().toString().take(12)
            app.settings.update { st -> st.copy(templates = st.templates + TemplateNote.VERSE.copy(id = id, name = "New template")) }
            openTemplate(id)
        }) {
            Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New template")
        }
        if (s.vaultTree.isNotBlank()) OutlinedButton(onClick = {
            scope.launch { fromVault = withContext(Dispatchers.IO) { VaultTemplates.list(context, s.vaultTree) } }
        }) { Text("From your vault") }
    }
    fromVault?.let { list ->
        if (list.isEmpty()) {
            LaunchedEffect(list) { android.widget.Toast.makeText(context, "No templates folder found in your vault.", android.widget.Toast.LENGTH_SHORT).show(); fromVault = null }
        } else QuickSwitcher(
            "Use a vault note", list.map { Choice(it.uri, it.name, it.path) }, null,
            onPick = { c ->
                fromVault = null
                val e = list.first { it.uri == c.id }
                scope.launch {
                    val id = withContext(Dispatchers.IO) { TemplateSync.use(app, e.uri, e.path) }
                    if (id != null) openTemplate(id) else android.widget.Toast.makeText(context, "Could not read that note.", android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { fromVault = null }, hint = "Find a template",
        )
    }

    Spacer(Modifier.height(6.dp))
    Text("Tags on every note", style = MaterialTheme.typography.titleMedium)
    val auto = remember { mutableStateListOf(*s.autoTags.toTypedArray()) }
    var typed by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { snapshotFlow { auto.toList() }.collect { list -> if (list != app.settings.value.autoTags) app.settings.update { it.copy(autoTags = list) } } }
    TagEditor(auto, typed) { typed = it }
}

@Composable
private fun TemplateRow(t: ClipTemplate, chosen: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val how = when (t.behavior) { "append-specific" -> "Adds to"; "prepend-specific" -> "Adds to the top of"; "overwrite" -> "Saves over"; else -> "New note" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainer).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (chosen) {
                    Spacer(Modifier.width(8.dp))
                    Text("Default", fontSize = 12.sp, color = scheme.surface, modifier = Modifier.clip(CircleShape).background(scheme.onSurface).padding(horizontal = 8.dp, vertical = 2.dp))
                }
            }
            val where = listOf(t.path, t.noteNameFormat).filter { it.isNotBlank() }.joinToString("/")
            Text(if (t.behavior == "create") "$how · $where" else "$how $where", color = scheme.onSurfaceVariant, fontSize = 13.sp, maxLines = 1)
            if (t.source.isNotBlank()) Text("From your vault: ${t.source}", color = LinkColor, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = scheme.onSurfaceVariant)
    }
}

/** Settings search: every word typed must start a word in the group's text, or appear inside it. */
object SettingsSearch {
    fun matches(query: String, text: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val hay = text.lowercase()
        val words = hay.split(Regex("[^a-z0-9.]+"))
        return q.split(Regex("\\s+")).all { w -> hay.contains(w) || words.any { it.startsWith(w) } }
    }
}
