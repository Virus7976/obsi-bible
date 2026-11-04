package app.obsibible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.obsibible.bible.ApiBible
import app.obsibible.bible.RemoteBible
import app.obsibible.bible.YouVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bibles read over the internet with the user's own free keys. Keys stay on this phone.
 * The setup guide explains each key step by step; this is the short form.
 */
@Composable
fun OnlineBibles(openSetup: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var esv by remember { mutableStateOf(s.esvKey) }
    var ab by remember { mutableStateOf(s.apiBibleKey) }
    var yv by remember { mutableStateOf(s.youVersionKey) }
    var finding by remember { mutableStateOf<String?>(null) }
    var found by remember { mutableStateOf<Pair<String, List<RemoteBible>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val keyStatus by app.keyScan.status.collectAsState()

    Text("Online", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text("Read the ESV, The Message and more with your own free keys. Keys stay on this phone.", color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(14.dp))
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainer).clickable(onClick = openSetup).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Setup guide", style = MaterialTheme.typography.titleMedium)
            Text("Step by step, with a link for every key.", color = scheme.onSurfaceVariant)
        }
    }

    Spacer(Modifier.height(18.dp))
    Text("ESV", style = MaterialTheme.typography.titleLarge)
    Text("Text and Crossway's audio. Up to 500 verses stay on the phone for offline use.", color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    KeyField("ESV key", esv) { esv = it; app.settings.update { st -> st.copy(esvKey = it) } }
    LinkRow("Get a free key at api.esv.org") { uri.openUri(SetupLinks.ESV_SIGNUP) }

    fun find(source: String, load: () -> List<RemoteBible>) {
        error = null
        finding = source
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { load() } }
                .onSuccess { found = source to it }.onFailure { error = it.message ?: "Could not reach $source." }
            finding = null
        }
    }

    Spacer(Modifier.height(18.dp))
    Text("API.Bible", style = MaterialTheme.typography.titleLarge)
    Text("Many Bibles. The Message shows if your key is allowed to use it.", color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    KeyField("API.Bible key", ab) { ab = it; app.settings.update { st -> st.copy(apiBibleKey = it) } }
    LinkRow("Get a free key at api.bible") { uri.openUri(SetupLinks.API_BIBLE_SIGNUP) }
    keyStatus["API.Bible"]?.let { KeyStatus(it) { app.keyScan.rescan(scope, "API.Bible") } }
    if (ab.isNotBlank()) AddRow(if (finding == "API.Bible") "Looking…" else "Add a Bible from API.Bible") { find("API.Bible") { ApiBible.bibles(ab) } }

    Spacer(Modifier.height(18.dp))
    Text("YouVersion", style = MaterialTheme.typography.titleLarge)
    Text("The YouVersion Platform. Which Bibles you get depends on your key.", color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    KeyField("YouVersion app key", yv) { yv = it; app.settings.update { st -> st.copy(youVersionKey = it) } }
    LinkRow("Get a free key at platform.youversion.com") { uri.openUri(SetupLinks.YV_START) }
    keyStatus["YouVersion"]?.let { KeyStatus(it) { app.keyScan.rescan(scope, "YouVersion") } }
    if (yv.isNotBlank()) AddRow(if (finding == "YouVersion") "Looking…" else "Add a Bible from YouVersion") { find("YouVersion") { YouVersion.bibles(yv) } }

    if (s.onlineBibles.isNotEmpty()) {
        Spacer(Modifier.height(14.dp))
        Text("Added", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
    }
    s.onlineBibles.forEach { e ->
        val p = e.split('|')
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.getOrElse(1) { "" }, style = MaterialTheme.typography.titleMedium)
                Text(p.getOrElse(2) { "" } + if (p[0].startsWith(YouVersion.PREFIX)) " · YouVersion" else " · API.Bible", color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = { app.settings.update { st -> st.copy(onlineBibles = st.onlineBibles - e) } }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = scheme.error) }

    found?.let { (source, list) ->
        val yvSource = source == "YouVersion"
        val have = s.onlineBibles.map { it.substringBefore('|') }.toSet()
        QuickSwitcher(
            "Add a Bible",
            list.filter { (if (yvSource) YouVersion.PREFIX + it.id else it.id) !in have }.map {
                Choice(it.id, "${it.abbreviation} · ${it.name}", it.language, if (it.name.contains("Message", true)) "The Message" else it.language)
            },
            null,
            onPick = { c ->
                val b = list.first { it.id == c.id }
                found = null
                scope.launch {
                    if (yvSource) addOnlineBible(app, YouVersion.PREFIX + b.id, b.abbreviation, b.name, b.copyright)
                    else addOnlineBible(app, b.id, b.abbreviation, b.name, withContext(Dispatchers.IO) { ApiBible.copyright(ab, b.id) })
                }
            },
            onDismiss = { found = null },
            hint = "Find a Bible, such as Message",
        )
    }
}

@Composable
private fun KeyField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, { onChange(it.trim()) }, Modifier.fillMaxWidth(),
        label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    )
}

@Composable
private fun KeyStatus(text: String, recheck: () -> Unit) {
    val bad = !text.startsWith("Your") && !text.startsWith("Checking")
    val good = text.contains("Message is added") || text.contains("It has The Message")
    Text(text, color = when { bad -> MaterialTheme.colorScheme.error; good -> MaterialTheme.colorScheme.onSurface; else -> MaterialTheme.colorScheme.onSurfaceVariant },
        modifier = Modifier.padding(vertical = 4.dp))
    if (!text.startsWith("Checking")) androidx.compose.material3.TextButton(onClick = recheck) { Text("Check again") }
}

@Composable
private fun AddRow(text: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Spacer(Modifier.height(6.dp))
    Row(
        Modifier.clip(CircleShape).background(scheme.surfaceContainer).clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = LinkColor)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = LinkColor)
    }
}
