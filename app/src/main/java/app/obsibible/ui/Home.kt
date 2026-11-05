package app.obsibible.ui

import app.obsibible.obsidian.Sender
import app.obsibible.obsidian.Clip
import app.obsibible.data.Mark
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.IconButton
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.audio.Narrators
import app.obsibible.bible.BibleInfo
import app.obsibible.bible.Epub
import app.obsibible.bible.InstallState
import app.obsibible.bible.Ref
import app.obsibible.bible.Usfm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** Well-known verses, one per day in turn. */
private val DailyVerses = listOf(
    "JHN.3.16", "PSA.23.1", "PHP.4.13", "ROM.8.28", "JER.29.11", "PRO.3.5", "ISA.40.31", "MAT.11.28", "JOS.1.9", "ROM.12.2",
    "GAL.5.22", "PSA.46.10", "2CO.5.17", "HEB.11.1", "1PE.5.7", "PHP.4.6", "MAT.6.33", "EPH.2.8", "ISA.41.10", "PSA.119.105",
    "JHN.14.6", "ROM.5.8", "1JN.1.9", "LAM.3.22", "MIC.6.8", "COL.3.23", "2TI.1.7", "PSA.34.18", "JAS.1.5", "ZEP.3.17",
    "ROM.15.13", "PSA.37.4", "MAT.5.16", "HEB.12.1", "1CO.13.4", "DEU.31.6", "PSA.121.1", "JHN.16.33", "EPH.3.20", "ISA.26.3",
)

@Composable
fun HomeTab(openRef: (Ref) -> Unit, openSearch: () -> Unit, openNotes: () -> Unit = {}) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val version by app.bibles.version.collectAsState()
    val marks by app.marks.all.collectAsState()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val vRef = Ref.fromKey(DailyVerses[(today.dayOfYear - 1) % DailyVerses.size])!!
    val vText by produceState<String?>(null, vRef, s.bible, version) { value = withContext(Dispatchers.IO) { app.bibles.verseText(s.bible, vRef) } }
    val info = remember(s.bible, version) { app.bibles.info(s.bible) }
    val abbr = info?.abbreviation ?: s.bible
    val last = Ref.fromKey(s.lastRef) ?: Ref("JHN", 1)
    val book = last.bookInfo
    var sending by remember { mutableStateOf<Clip?>(null) }

    // Newest first. One older mark comes back each day to read again.
    val recent = remember(marks) { marks.sortedByDescending { it.updated }.take(3) }
    val review = remember(marks, today) {
        val old = marks.filter { it.created < System.currentTimeMillis() - 86_400_000L && it.id !in recent.map { r -> r.id } }.sortedBy { it.created }
        if (old.isEmpty()) null else old[(today.toEpochDay() % old.size).toInt()]
    }
    val clips by produceState(emptyMap<String, Clip>(), recent, review, version) {
        value = withContext(Dispatchers.IO) { (recent + listOfNotNull(review)).associate { it.id to clipFor(app, it) } }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Today", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.surfaceContainer).clickable(onClick = openSearch), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Search, "Search", Modifier.size(24.dp))
            }
        }

        // keep reading
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.surfaceContainer).clickable { openRef(last.chapterRef) }.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Keep reading", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(6.dp))
                    Text(last.chapterRef.label, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(2.dp))
                    Text("Chapter ${last.chapter} of ${book.chapters} · $abbr", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                PlayCircle { app.audio.play(s.bible, last.chapterRef); openRef(last.chapterRef) }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { last.chapter / book.chapters.coerceAtLeast(1).toFloat() },
                Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
                color = scheme.onSurface, trackColor = scheme.surfaceContainerHighest, drawStopIndicator = {},
            )
        }

        // verse of the day
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.surfaceContainer).clickable { openRef(vRef) }.padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp)) {
            Text("Verse of the day", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(14.dp))
            Text(vText ?: "…", fontFamily = SerifFont, fontSize = 24.sp, lineHeight = 36.sp)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${vRef.label} $abbr", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                val shareLine = "${vText.orEmpty()}\n${vRef.label} $abbr"
                IconButton(onClick = {
                    Sender.copy(context, shareLine)
                    Toast.makeText(context, "Copied the verse.", Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Rounded.ContentCopy, "Copy", Modifier.size(22.dp)) }
                IconButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, shareLine)
                    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Icon(Icons.Rounded.Share, "Share", Modifier.size(22.dp)) }
                IconButton(onClick = {
                    sending = Clip(vRef, abbr, info?.name ?: abbr, vText.orEmpty(), copyright = info?.copyright.orEmpty())
                }) { Icon(Icons.Rounded.Diamond, "Send to Obsidian", Modifier.size(22.dp)) }
            }
        }

        // read again
        review?.let { m ->
            Spacer(Modifier.height(28.dp))
            Text("Read again", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text("One of your highlights, a new one each day.", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            HomeMark(m, clips[m.id], abbr = clips[m.id]?.translation ?: m.bible) { openRef(m.ref) }
        }

        // recent
        if (recent.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text("See all", Modifier.clip(CircleShape).clickable(onClick = openNotes).padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelLarge)
            }
            Spacer(Modifier.height(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                recent.forEach { m -> HomeMark(m, clips[m.id], abbr = clips[m.id]?.translation ?: m.bible) { openRef(m.ref) } }
            }
        } else {
            Spacer(Modifier.height(28.dp))
            Text("Hold a verse in the Bible tab to highlight it or add a note. Your newest ones show here.", color = scheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(24.dp))
    }
    sending?.let { c -> ClipSheet(c, -1, onSent = { sending = null }, onDismiss = { sending = null }) }
}

/** A highlight or note on the Home tab: the words, the reference, and the note under it. */
@Composable
private fun HomeMark(m: Mark, clip: Clip?, abbr: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(scheme.surfaceContainer).clickable(onClick = onClick).padding(18.dp)) {
        Text("${m.label} $abbr", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        QuoteCard(clip?.text ?: m.quote.ifBlank { "…" }, if (m.hasColor) m.color else -1, maxLines = 4)
        if (m.hasNote) {
            Spacer(Modifier.height(10.dp))
            Text(m.note, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The Bibles on the phone, laid out like the reference: abbreviation, audio count, full name. */
@Composable
fun VersionsScreen(onBack: () -> Unit, openSetup: () -> Unit = {}) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val installed by app.bibles.installed.collectAsState()
    val install by app.bibles.install.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        error = null
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && i >= 0) c.getString(i) else null
                    } ?: "Bible.zip"
                    val isEpub = name.endsWith(".epub", true) || context.contentResolver.openInputStream(uri)!!.use { isEpubZip(it) }
                    if (isEpub) {
                        // The short name comes from the book's title: "The Message Remix" becomes MR.
                        val title = context.contentResolver.openInputStream(uri)!!.use { runCatching { Epub.read(it).title }.getOrDefault("") }
                            .ifBlank { name.substringBeforeLast('.') }
                        val id = shortName(title)
                        context.contentResolver.openInputStream(uri)!!.use { app.bibles.installEpub(it, BibleInfo(id, id, title, "")) }
                        id
                    } else {
                        val id = name.substringBefore('_').substringBefore('.').uppercase().filter { it.isLetterOrDigit() }.take(12).ifBlank { "MINE" }
                        val title = context.contentResolver.openInputStream(uri)!!.use { firstTitle(it) } ?: id
                        context.contentResolver.openInputStream(uri)!!.use { app.bibles.installZip(it, BibleInfo(id, id, title, "")) }
                        id
                    }
                }
            }.onSuccess { id -> app.settings.update { it.copy(bible = id) } }
                .onFailure { error = it.message ?: "That file could not be read." }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopBack("Versions", onBack, subtitle = if (installed.size == 1) "1 Bible" else "${installed.size} Bibles")
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            Text("Downloaded", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            installed.forEach { b ->
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { app.settings.update { it.copy(bible = b.id) }; onBack() }.padding(vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(b.abbreviation, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            val voices = Narrators.forBible(b.id).size
                            Spacer(Modifier.width(10.dp))
                            Row(Modifier.clip(CircleShape).background(scheme.surfaceContainer).padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Audio", Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (voices > 0) voices.toString() else "Voice", fontSize = 15.sp)
                            }
                            if (app.bibles.isOnline(b.id)) {
                                Spacer(Modifier.width(8.dp))
                                Text("Online", color = scheme.onSurfaceVariant, fontSize = 15.sp)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(b.name, color = scheme.onSurfaceVariant, fontSize = 18.sp)
                    }
                    if (b.id == s.bible) Icon(Icons.Rounded.Check, "In use", Modifier.size(26.dp))
                }
            }
            Spacer(Modifier.height(28.dp))
            OnlineBibles(openSetup)
            Spacer(Modifier.height(28.dp))
            Text("Add a Bible", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text("Pick an EPUB, or a zip of USFM files such as one from ebible.org. Only add Bibles you are allowed to use.", color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.clip(CircleShape).background(scheme.surfaceContainer).clickable { pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/epub+zip", "application/octet-stream")) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.FileOpen, null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Install from file", style = MaterialTheme.typography.titleMedium)
            }
            (install as? InstallState.Working)?.let { w ->
                Spacer(Modifier.height(16.dp))
                Text("Installing ${w.name}")
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { w.done / w.total.toFloat() }, Modifier.fillMaxWidth())
            }
            error?.let { Spacer(Modifier.height(12.dp)); Text(it, color = scheme.error) }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** The Bible's name from the first book's \id line ("\id GEN - World English Bible"). */
/** True when a zip is an EPUB: it has the EPUB contents file. */
private fun isEpubZip(input: java.io.InputStream): Boolean = runCatching {
    java.util.zip.ZipInputStream(input.buffered()).use { z ->
        while (true) { val e = z.nextEntry ?: return@use false; if (e.name == "META-INF/container.xml" || e.name == "mimetype") return@use true }
        @Suppress("UNREACHABLE_CODE") false
    }
}.getOrDefault(false)

/** A short name for a Bible from its title: "The Message Remix" gives MR. Words such as "The" are left out. */
fun shortName(title: String): String {
    val skip = setOf("the", "a", "an", "of", "and", "bible", "holy", "edition", "version")
    val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotBlank() && it.lowercase() !in skip }
    val s = when {
        words.isEmpty() -> "MINE"
        words.size == 1 -> words[0].take(4)
        else -> words.take(4).joinToString("") { it.take(1) }
    }
    return s.uppercase().filter { it.isLetterOrDigit() }.ifBlank { "MINE" }
}

private fun firstTitle(input: java.io.InputStream): String? {
    java.util.zip.ZipInputStream(input.buffered()).use { z ->
        while (true) {
            val e = z.nextEntry ?: return null
            if (!e.name.endsWith("sfm", true)) continue
            val buf = ByteArray(400)
            var n = 0
            while (n < buf.size) { val r = z.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            val head = String(buf, 0, n, Charsets.UTF_8)
            Usfm.bookId(head) ?: continue
            return Regex("\\\\id\\s+\\w+\\s*-?\\s*(.+)").find(head)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        }
    }
}
