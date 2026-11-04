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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Bibles read over the internet with the user's own free keys. Keys stay on this phone.
 * The ESV comes from Crossway. API.Bible carries many Bibles; The Message shows there only
 * when the key is licensed for it.
 */
@Composable
fun OnlineBibles() {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val uri = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var esv by remember { mutableStateOf(s.esvKey) }
    var ab by remember { mutableStateOf(s.apiBibleKey) }
    var finding by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<RemoteBible>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Text("Online", style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    Text("Read the ESV, The Message and more with your own free key. Keys stay on this phone.", color = scheme.onSurfaceVariant)

    Spacer(Modifier.height(18.dp))
    Text("ESV", style = MaterialTheme.typography.titleLarge)
    Text("Text and Crossway's audio. Up to 500 verses stay on the phone for offline use.", color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        esv, { esv = it.trim(); app.settings.update { st -> st.copy(esvKey = esv) } }, Modifier.fillMaxWidth(),
        label = { Text("ESV key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    )
    LinkRow("Get a free key at api.esv.org") { uri.openUri("https://api.esv.org/account/create-application/") }

    Spacer(Modifier.height(18.dp))
    Text("API.Bible", style = MaterialTheme.typography.titleLarge)
    Text("Many Bibles. The Message shows only if your key is allowed to use it.", color = scheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        ab, { ab = it.trim(); app.settings.update { st -> st.copy(apiBibleKey = ab) } }, Modifier.fillMaxWidth(),
        label = { Text("API.Bible key") }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
    )
    LinkRow("Get a free key at scripture.api.bible") { uri.openUri("https://scripture.api.bible/signup") }

    s.onlineBibles.forEach { e ->
        val p = e.split('|')
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.getOrElse(1) { "" }, style = MaterialTheme.typography.titleMedium)
                Text(p.getOrElse(2) { "" }, color = scheme.onSurfaceVariant)
            }
            IconButton(onClick = { app.settings.update { st -> st.copy(onlineBibles = st.onlineBibles - e) } }) { Icon(Icons.Rounded.Close, "Remove") }
        }
    }
    if (ab.isNotBlank()) {
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.clip(CircleShape).background(scheme.surfaceContainer).clickable {
                error = null
                finding = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { ApiBible.bibles(ab) } }
                        .onSuccess { found = it }.onFailure { error = it.message ?: "Could not reach API.Bible." }
                    finding = false
                }
            }.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Add, null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (finding) "Looking…" else "Add a Bible from API.Bible", style = MaterialTheme.typography.titleMedium)
        }
    }
    error?.let { Spacer(Modifier.height(10.dp)); Text(it, color = scheme.error) }

    found?.let { list ->
        val have = s.onlineBibles.map { it.substringBefore('|') }.toSet()
        QuickSwitcher(
            "Add a Bible",
            list.filter { it.id !in have }.map {
                Choice(it.id, "${it.abbreviation} · ${it.name}", it.language, if (it.name.contains("Message", true)) "The Message" else it.language)
            },
            null,
            onPick = { c ->
                val b = list.first { it.id == c.id }
                found = null
                scope.launch {
                    val cr = withContext(Dispatchers.IO) { ApiBible.copyright(ab, b.id) }
                    val entry = listOf(b.id, b.abbreviation, b.name, cr).joinToString("|") { it.replace('|', '/') }
                    app.settings.update { st -> st.copy(onlineBibles = st.onlineBibles + entry, bible = ApiBible.PREFIX + b.id) }
                }
            },
            onDismiss = { found = null },
            hint = "Find a Bible, such as Message",
        )
    }
}

@Composable
private fun LinkRow(text: String, onClick: () -> Unit) {
    Row(Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
