package app.obsibible.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.ObsiBibleApp
import app.obsibible.audio.CloudSpeech
import app.obsibible.bible.ApiBible
import app.obsibible.bible.EsvApi
import app.obsibible.bible.Ref
import app.obsibible.bible.RemoteBible
import app.obsibible.bible.YouVersion
import app.obsibible.obsidian.Sender
import app.obsibible.obsidian.VaultIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Every place a key comes from, in one list, so each link is kept in one spot. */
object SetupLinks {
    const val ESV_SIGNUP = "https://api.esv.org/account/create-application/"
    const val ESV_KEYS = "https://api.esv.org/account/"
    const val ESV_TERMS = "https://api.esv.org/terms/"
    const val API_BIBLE_SIGNUP = "https://api.bible/sign-up"
    const val API_BIBLE_HOME = "https://api.bible"
    const val API_BIBLE_DOCS = "https://docs.api.bible/quick-start/licensing-and-access"
    const val YV_START = "https://platform.youversion.com/platform/get-started"
    const val YV_APPS = "https://platform.youversion.com/platform/apps"
    const val YV_DOCS = "https://developers.youversion.com"
    const val AZURE_SPEECH = "https://portal.azure.com/#create/Microsoft.CognitiveServicesSpeechServices"
    const val AZURE_FREE = "https://azure.microsoft.com/free/"
    const val AZURE_KEYS_HELP = "https://learn.microsoft.com/azure/ai-services/multi-service-resource#get-the-keys-for-your-resource"
    const val OBSIDIAN = "https://obsidian.md/download"
    const val OBSIDIAN_TEMPLATES = "https://help.obsidian.md/plugins/templates"
}

/** Adds an online Bible to the user's list and makes it the one in use. */
fun addOnlineBible(app: ObsiBibleApp, id: String, abbreviation: String, name: String, copyright: String) {
    val entry = listOf(id, abbreviation, name, copyright).joinToString("|") { it.replace('|', '/').replace('\n', ' ') }
    val bibleId = if (id.startsWith(YouVersion.PREFIX)) id else ApiBible.PREFIX + id
    app.settings.update { s -> s.copy(onlineBibles = s.onlineBibles.filterNot { it.substringBefore('|') == id } + entry, bible = bibleId) }
}

private fun isMessage(b: RemoteBible) = b.abbreviation.equals("MSG", true) || b.name.contains("The Message", true)

/**
 * The setup guide. It opens once on first start, and again from Settings or Versions.
 * Each page says why, gives the exact steps with a link for each, and checks the key.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val app = LocalApp.current
    val scheme = MaterialTheme.colorScheme
    val pages = listOf("Welcome", "ESV", "The Message", "Voices", "Obsidian", "Done")
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    fun finish() { app.settings.update { it.copy(onboarded = true) }; onDone() }

    Column(Modifier.fillMaxSize().background(scheme.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(pages[pager.currentPage], style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (pager.currentPage < pages.lastIndex) TextButton(onClick = ::finish) { Text("Close") }
        }
        // Step dots, tap one to jump to it.
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            pages.indices.forEach { i ->
                Box(
                    Modifier.weight(1f).height(4.dp).clip(CircleShape)
                        .background(if (i <= pager.currentPage) scheme.onSurface else scheme.surfaceContainerHighest)
                        .clickable { scope.launch { pager.animateScrollToPage(i) } },
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { page ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp)) {
                when (page) {
                    0 -> WelcomePage()
                    1 -> EsvPage()
                    2 -> MessagePage()
                    3 -> VoicesPage()
                    4 -> ObsidianPage()
                    else -> DonePage()
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pager.currentPage > 0) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Text("Back") }
            Spacer(Modifier.weight(1f))
            if (pager.currentPage in 1 until pages.lastIndex) TextButton(onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Text("Skip") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { if (pager.currentPage == pages.lastIndex) finish() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
                modifier = Modifier.height(50.dp),
            ) { Text(if (pager.currentPage == pages.lastIndex) "Start reading" else if (pager.currentPage == 0) "Set up" else "Next", fontSize = 16.sp) }
        }
    }
}

// ---------- pages ----------

@Composable
private fun WelcomePage() {
    val scheme = MaterialTheme.colorScheme
    Text("Set up your Bibles", style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(12.dp))
    Text("The BSB already works offline, with three recorded voices. Add more Bibles with free keys. This takes about five minutes.", color = scheme.onSurfaceVariant, fontSize = 17.sp)
    Spacer(Modifier.height(24.dp))
    Overview("ESV", "Text and Crossway's recording. Free key from Crossway.")
    Overview("The Message", "Through API.Bible or YouVersion, if your key is allowed to use it.")
    Overview("Better voices", "Natural Microsoft voices for Bibles on the phone. Free tier.")
    Overview("Obsidian", "Save highlights and notes into your vault.")
    Spacer(Modifier.height(18.dp))
    Text("Every key stays on this phone. You can skip any step and come back from Settings.", color = scheme.onSurfaceVariant)
}

@Composable
private fun Overview(title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(Modifier.padding(top = 7.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurface))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun EsvPage() {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    PageHead("ESV", "The English Standard Version, with Crossway's own audio.")
    Steps(
        "Make a free account and an app at api.esv.org." to SetupLinks.ESV_SIGNUP,
        "Name the app anything, such as \"My Bible\". Say it is for personal, non-commercial use." to null,
        "Copy the key from your account page." to SetupLinks.ESV_KEYS,
        "Paste it below, then tap Check." to null,
    )
    KeyCheck(
        label = "ESV key", value = s.esvKey, onValue = { v -> app.settings.update { it.copy(esvKey = v) } },
        check = { key -> EsvApi.chapter(key, Ref("JHN", 1)); "The key works. The ESV is now in your Bibles." },
    )
    Note("Crossway lets the app keep up to 500 verses for offline use. The audio streams, so it needs internet.")
    LinkRow("Crossway's terms", SetupLinks.ESV_TERMS)
}

@Composable
private fun MessagePage() {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    PageHead("The Message", "There is no free open copy. Two services can give it to you with a free key. Try one, or both.")
    Note("The Message has no audio here. Its licence does not allow a computer voice.")

    Spacer(Modifier.height(18.dp))
    Text("Way 1 · API.Bible", style = MaterialTheme.typography.titleLarge)
    Steps(
        "Sign up for the free Starter plan." to SetupLinks.API_BIBLE_SIGNUP,
        "The free plan lets you use up to 3 licensed Bibles. Ask for The Message as one of them." to SetupLinks.API_BIBLE_DOCS,
        "Copy your API key from your account." to SetupLinks.API_BIBLE_HOME,
        "Paste it below, then tap Check." to null,
    )
    var abFound by remember { mutableStateOf<List<RemoteBible>>(emptyList()) }
    KeyCheck(
        label = "API.Bible key", value = s.apiBibleKey, onValue = { v -> app.settings.update { it.copy(apiBibleKey = v) } },
        check = { key ->
            val list = ApiBible.bibles(key)
            abFound = list
            val msg = list.firstOrNull(::isMessage)
            if (msg != null) "The key works, and it has The Message." else "The key works, but The Message is not on it yet. ${list.size} other Bibles are."
        },
    )
    abFound.firstOrNull(::isMessage)?.let { b ->
        val added = s.onlineBibles.any { it.substringBefore('|') == b.id }
        AddButton(if (added) "The Message is added" else "Add The Message", done = added) {
            val cr = withContext(Dispatchers.IO) { ApiBible.copyright(s.apiBibleKey, b.id) }
            addOnlineBible(app, b.id, b.abbreviation, b.name, cr)
        }
    }

    Spacer(Modifier.height(26.dp))
    Text("Way 2 · YouVersion", style = MaterialTheme.typography.titleLarge)
    Steps(
        "Make a free YouVersion Platform account." to SetupLinks.YV_START,
        "Create an app, and copy its app key." to SetupLinks.YV_APPS,
        "Paste it below, then tap Check." to null,
    )
    var yvFound by remember { mutableStateOf<List<RemoteBible>>(emptyList()) }
    KeyCheck(
        label = "YouVersion app key", value = s.youVersionKey, onValue = { v -> app.settings.update { it.copy(youVersionKey = v) } },
        check = { key ->
            val list = YouVersion.bibles(key)
            yvFound = list
            if (list.any(::isMessage)) "The key works, and it has The Message." else "The key works, but The Message is not on it. ${list.size} other Bibles are."
        },
    )
    yvFound.firstOrNull(::isMessage)?.let { b ->
        val id = YouVersion.PREFIX + b.id
        val added = s.onlineBibles.any { it.substringBefore('|') == id }
        AddButton(if (added) "The Message is added" else "Add The Message", done = added) {
            addOnlineBible(app, id, b.abbreviation, b.name, b.copyright)
        }
    }
    LinkRow("YouVersion developer docs", SetupLinks.YV_DOCS)
    Spacer(Modifier.height(10.dp))
    Text("Other Bibles on these keys can be added in Versions.", color = scheme.onSurfaceVariant)
}

@Composable
private fun VoicesPage() {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    PageHead("Better voices", "Microsoft's neural voices sound close to a real reader. They work for every Bible on the phone, such as the BSB.")
    Steps(
        "Make a free Azure account. The free tier covers about 500,000 letters a month." to SetupLinks.AZURE_FREE,
        "Create a Speech resource. Pick the Free F0 tier and any region near you." to SetupLinks.AZURE_SPEECH,
        "Open the resource, then Keys and Endpoint. Copy Key 1 and the region." to SetupLinks.AZURE_KEYS_HELP,
        "Paste both below, then tap Check." to null,
    )
    var region by remember { mutableStateOf(s.cloudRegion) }
    OutlinedTextField(region, { region = it.trim().lowercase(); app.settings.update { st -> st.copy(cloudRegion = region) } }, Modifier.fillMaxWidth(), label = { Text("Region, such as eastus") }, singleLine = true)
    Spacer(Modifier.height(10.dp))
    KeyCheck(
        label = "Speech key", value = s.cloudKey, onValue = { v -> app.settings.update { it.copy(cloudKey = v) } },
        check = { key ->
            if (app.settings.value.cloudRegion.isBlank()) throw IllegalStateException("Type the region first.")
            val n = CloudSpeech.voices(key, app.settings.value.cloudRegion).size
            "The key works. $n voices are ready. Pick one in the audio sheet."
        },
    )
    Note("The phone's own voice works offline and needs no key.")
}

@Composable
private fun ObsidianPage() {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val pick = rememberVaultPicker { VaultIndex.forget() }
    val folder = remember(s.vaultTree) { if (s.vaultTree.isBlank()) null else Sender.folderName(context, s.vaultTree) }
    PageHead("Obsidian", "Send highlights and notes to your vault. Each note links back to its verse.")
    Steps(
        "Get Obsidian, if it is not on this phone." to SetupLinks.OBSIDIAN,
        "Pick your vault folder below. Notes are then saved straight into it." to null,
        "Optional: put note templates in a Templates folder. You can pick one each time you send." to SetupLinks.OBSIDIAN_TEMPLATES,
    )
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainer).clickable(onClick = pick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(folder ?: "No vault folder picked", style = MaterialTheme.typography.titleMedium)
            Text(if (folder != null) "Notes are saved here." else "Tap to pick it.", color = scheme.onSurfaceVariant)
        }
        if (folder != null) Icon(Icons.Rounded.Check, "Picked", tint = Color(0xFF4ADE80)) else Text("Pick", fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DonePage() {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val installed by app.bibles.installed.collectAsState()
    val scheme = MaterialTheme.colorScheme
    PageHead("You are set", "Here is what is ready.")
    Ready("Bibles", installed.joinToString(" · ") { it.abbreviation }, true)
    Ready("ESV", if (s.esvKey.isNotBlank()) "Key added" else "Not set up", s.esvKey.isNotBlank())
    val msg = s.onlineBibles.any { it.split('|').getOrNull(2)?.contains("Message", true) == true }
    Ready("The Message", if (msg) "Added" else "Not set up", msg)
    Ready("Microsoft voices", if (s.cloudKey.isNotBlank()) "Key added" else "Not set up", s.cloudKey.isNotBlank())
    Ready("Obsidian vault", if (s.vaultTree.isNotBlank()) "Picked" else "Not picked", s.vaultTree.isNotBlank())
    Spacer(Modifier.height(16.dp))
    Text("Open this guide again any time from Settings or Versions.", color = scheme.onSurfaceVariant)
}

@Composable
private fun Ready(title: String, detail: String, ok: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(if (ok) Color(0xFF4ADE80).copy(alpha = 0.18f) else scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            if (ok) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Color(0xFF4ADE80))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, color = scheme.onSurfaceVariant)
        }
    }
}

// ---------- parts ----------

@Composable
private fun PageHead(title: String, text: String) {
    Text(title, style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 17.sp)
    Spacer(Modifier.height(18.dp))
}

/** Numbered steps. A step with a link opens it when tapped. */
@Composable
private fun Steps(vararg steps: Pair<String, String?>) {
    val uri = LocalUriHandler.current
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        steps.forEachIndexed { i, (text, link) ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (link != null) Modifier.clickable { uri.openUri(link) } else Modifier).padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Text("${i + 1}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(text, fontSize = 16.sp)
                    if (link != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(link.removePrefix("https://").substringBefore('#').trimEnd('/'), color = LinkColor, fontSize = 14.sp)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(14.dp), tint = LinkColor)
                    }
                }
            }
        }
    }
}

/** A key field with a Check button that tries the key for real and says what it found. */
@Composable
private fun KeyCheck(label: String, value: String, onValue: (String) -> Unit, check: suspend (String) -> String) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(value) }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            text, { text = it.trim(); onValue(text); result = null }, Modifier.weight(1f),
            label = { Text(label) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
        )
        Spacer(Modifier.width(10.dp))
        OutlinedButton(
            onClick = {
                busy = true
                scope.launch {
                    result = runCatching { withContext(Dispatchers.IO) { check(text) } }
                        .fold({ true to it }, { false to (it.message ?: "That did not work.") })
                    busy = false
                }
            },
            enabled = text.isNotBlank() && !busy, modifier = Modifier.height(56.dp),
        ) { if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else Text("Check") }
    }
    AnimatedVisibility(result != null) {
        val (ok, msg) = result ?: (true to "")
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Rounded.Check else Icons.Rounded.ErrorOutline, null, Modifier.size(20.dp), tint = if (ok) Color(0xFF4ADE80) else scheme.error)
            Spacer(Modifier.width(8.dp))
            Text(msg, color = if (ok) scheme.onSurface else scheme.error)
        }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun AddButton(label: String, done: Boolean, onAdd: suspend () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    Button(
        onClick = { if (!done) scope.launch { onAdd() } }, enabled = !done,
        colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
        modifier = Modifier.padding(bottom = 8.dp),
    ) {
        if (done) { Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
        Text(label)
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainer).padding(14.dp),
    )
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LinkRow(text: String, url: String) {
    val uri = LocalUriHandler.current
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { uri.openUri(url) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = LinkColor)
        Spacer(Modifier.width(6.dp))
        Icon(Icons.AutoMirrored.Rounded.OpenInNew, null, Modifier.size(16.dp), tint = LinkColor)
    }
}
