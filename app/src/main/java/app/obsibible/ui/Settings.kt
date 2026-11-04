package app.obsibible.ui

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.obsibible.data.AppSettings
import app.obsibible.update.UpdateNotice
import app.obsibible.update.UpdateState

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val set: ((AppSettings) -> AppSettings) -> Unit = { app.settings.update(it) }
    var voices by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        TopBack("Settings", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(30.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            UpdateGroup()
            Group("Look") {
                Choices(listOf("dark" to "Dark", "oled" to "Black", "light" to "Light", "auto" to "Phone"), s.appearance) { v -> set { it.copy(appearance = v) } }
                AppIconPicker()
            }
            Group("Listening") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Voice", style = MaterialTheme.typography.titleSmall)
                        Text(voiceLabel(s.audioSource), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    OutlinedButton(onClick = { voices = true }) { Text("Change") }
                }
                Toggle("Keep going to the next chapter", s.autoContinue) { v -> set { it.copy(autoContinue = v) } }
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
        }
    }
    if (voices) VoicePicker(s.bible) { voices = false }
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
    Group("Updates") {
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
