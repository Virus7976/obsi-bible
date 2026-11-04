package app.obsibible.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.obsibible.data.AppSettings
import app.obsibible.update.UpdateState

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val set: ((AppSettings) -> AppSettings) -> Unit = { app.settings.update(it) }
    Column(Modifier.fillMaxSize()) {
        TopBack("Settings", onBack)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            Group("Look") {
                Choices(listOf("dark" to "Dark", "oled" to "Black", "light" to "Light", "auto" to "Phone"), s.appearance) { v -> set { it.copy(appearance = v) } }
                AppIconPicker()
            }
            UpdateGroup()
        }
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
private fun UpdateGroup() {
    val up = LocalApp.current.updates
    val state by up.state.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Group("Updates") {
        Text("Version ${up.currentVersion}", style = MaterialTheme.typography.titleSmall)
        when (val u = state) {
            is UpdateState.Idle, is UpdateState.UpToDate -> {
                Text(if (u is UpdateState.UpToDate) "You have the newest version." else "Not checked yet.", color = muted)
                Button(onClick = { up.check() }) { Text("Check for updates") }
            }
            is UpdateState.Checking -> Text("Checking…")
            is UpdateState.Available -> {
                Text("Version ${u.info.version} is ready", style = MaterialTheme.typography.titleMedium)
                if (u.info.notes.isNotBlank()) Text(u.info.notes.trim().take(600), style = MaterialTheme.typography.bodySmall, color = muted)
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
    }
}
