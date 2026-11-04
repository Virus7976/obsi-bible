package app.obsibible.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.obsibible.bible.InstallState
import app.obsibible.bible.Ref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Bible tab: the chapter, with listen, search, version and the chapter pill. */
@Composable
fun BibleTab(openSearch: () -> Unit, openVersions: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val audio by app.audio.ui.collectAsState()
    val install by app.bibles.install.collectAsState()
    val bibleVersion by app.bibles.version.collectAsState()
    val installed by app.bibles.installed.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val ref = Ref.fromKey(s.lastRef) ?: Ref("JHN", 1)
    val focusVerse = Ref.fromKey(s.lastRef)?.verse ?: 0
    var chapter by remember { mutableStateOf<app.obsibible.bible.Chapter?>(null) }
    var selected by remember(ref.chapterRef, s.bible) { mutableStateOf(setOf<Int>()) }
    var sheet by remember { mutableStateOf(false) }
    var textSheet by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var page by remember { mutableStateOf<PageView?>(null) }
    val abbreviation = installed.firstOrNull { it.id == s.bible }?.abbreviation ?: s.bible

    LaunchedEffect(ref.chapterRef, s.bible, bibleVersion) {
        chapter = withContext(Dispatchers.IO) { app.bibles.chapter(s.bible, ref.chapterRef) }
    }
    // The audio moved on to the next chapter: turn the page with it.
    LaunchedEffect(audio.ref) {
        val a = audio.ref ?: return@LaunchedEffect
        if (audio.active && audio.bible == s.bible && a != ref.chapterRef) app.settings.update { it.copy(lastRef = a.key) }
    }

    fun go(r: Ref?) { r ?: return; app.settings.update { it.copy(lastRef = r.key) } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { sheet = true }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, "Listen", Modifier.size(28.dp)) }
            IconButton(onClick = openSearch) { Icon(Icons.Rounded.Search, "Search", Modifier.size(28.dp)) }
            IconButton(onClick = { textSheet = true }) { Icon(Icons.Outlined.MoreHoriz, "Text size", Modifier.size(28.dp)) }
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.clip(CircleShape).background(scheme.surfaceContainer).clickable(onClick = openVersions).padding(horizontal = 14.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Language, null, Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(abbreviation, style = MaterialTheme.typography.titleMedium)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val ch = chapter
            when {
                ch != null -> {
                    val density = LocalDensity.current
                    val colors = PageColors(
                        text = scheme.onBackground.toArgb(), muted = scheme.onSurfaceVariant.toArgb(),
                        faint = scheme.onSurfaceVariant.copy(alpha = 0.6f).toArgb(),
                        mark = scheme.onBackground.copy(alpha = 0.08f).toArgb(), markStrong = scheme.onBackground.copy(alpha = 0.22f).toArgb(),
                        underline = scheme.onSurfaceVariant.toArgb(),
                    )
                    val padH = with(density) { 28.dp.roundToPx() }
                    AndroidView(
                        factory = { ctx -> PageView(ctx).also { page = it } },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        update = { v ->
                            v.onVerseTap = { verse -> selected = if (verse in selected) selected - verse else selected + verse }
                            v.onNoteTap = { note = it }
                            // The key comes from the chapter itself, never from the pill: while the next
                            // chapter loads, the old text must not be filed under the new name.
                            val shown = Ref(ch.book, ch.number)
                            v.show(ch, "${s.bible}/${shown.key}/${colors.text}/${s.readerFontSize}/$bibleVersion", s.readerFontSize, s.lineHeight, colors, padH, with(density) { 40.dp.roundToPx() }, with(density) { 120.dp.roundToPx() })
                            v.setSelected(selected)
                            val following = audio.active && audio.bible == s.bible && audio.ref == shown
                            v.setFollow(if (following) audio.verse else 0, if (following) audio.word else null, following && audio.playing)
                        },
                    )
                    LaunchedEffect(ch, focusVerse) { if (focusVerse > 0) page?.scrollToVerse(focusVerse, false) }
                }
                install is InstallState.Working -> {
                    val w = install as InstallState.Working
                    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Getting the Bible ready", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(16.dp))
                        LinearProgressIndicator(progress = { w.done / w.total.toFloat() }, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Text("This happens once. After that it works offline.", color = scheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                }
                install is InstallState.Failed -> Text((install as InstallState.Failed).message, Modifier.padding(32.dp), color = scheme.error)
                else -> Text("${ref.label} is not in this Bible.", Modifier.padding(32.dp).align(Alignment.Center), color = scheme.onSurfaceVariant)
            }
        }

        HorizontalDivider(color = scheme.outlineVariant)
        if (selected.isNotEmpty()) {
            VerseActions(
                label = Ref(ref.book, ref.chapter, selected.min(), if (selected.size > 1) selected.max() else 0).label,
                onPlay = { app.audio.play(s.bible, ref.chapterRef, selected.min()); selected = emptySet() },
                onCopy = { copyVerses(context, app, s.bible, ref, selected, abbreviation); selected = emptySet() },
                onShare = { shareVerses(context, app, s.bible, ref, selected, abbreviation); selected = emptySet() },
                onClose = { selected = emptySet() },
            )
        } else {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                val playingHere = audio.active && audio.ref == ref.chapterRef && audio.bible == s.bible
                Box(
                    Modifier.size(58.dp).clip(CircleShape).background(scheme.surfaceContainer).clickable {
                        if (playingHere) app.audio.toggle() else {
                            val from = page?.topVerse()?.takeIf { it > 1 } ?: 0
                            app.audio.play(s.bible, ref.chapterRef, from)
                            if (!audio.active) sheet = true
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (playingHere && audio.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playingHere && audio.playing) "Pause" else "Play", Modifier.size(32.dp))
                }
                Spacer(Modifier.size(14.dp))
                Row(
                    Modifier.weight(1f).height(58.dp).clip(CircleShape).background(scheme.surfaceContainer),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { go(ref.previous()) }, enabled = ref.previous() != null) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous chapter", Modifier.size(30.dp)) }
                    Text(
                        ref.chapterRef.label, Modifier.weight(1f).clickable(onClick = openSearch).padding(vertical = 16.dp),
                        style = MaterialTheme.typography.titleMedium, fontSize = 18.sp, textAlign = TextAlign.Center,
                    )
                    IconButton(onClick = { go(ref.next()) }, enabled = ref.next() != null) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next chapter", Modifier.size(30.dp)) }
                }
            }
        }
    }

    if (sheet) AudioSheet(
        idleTitle = ref.chapterRef.label,
        onStart = { app.audio.play(s.bible, ref.chapterRef, page?.topVerse()?.takeIf { it > 1 } ?: 0) },
        onDismiss = { sheet = false },
    )
    if (textSheet) TextSizeSheet { textSheet = false }
    note?.let { n ->
        ModalBottomSheet(onDismissRequest = { note = null }, containerColor = scheme.surfaceContainer, dragHandle = { SheetHandle() }) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
                Text("Note", style = MaterialTheme.typography.titleMedium, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Text(n, fontFamily = SerifFont, fontSize = 19.sp, lineHeight = 28.sp)
            }
        }
    }
}

@Composable
private fun VerseActions(label: String, onPlay: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(26.dp)) }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1)
        Action(Icons.Rounded.PlayArrow, "Play", onPlay)
        Action(Icons.Outlined.ContentCopy, "Copy", onCopy)
        Action(Icons.Outlined.Share, "Share", onShare)
    }
}

@Composable
private fun Action(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(24.dp))
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun versesText(app: app.obsibible.ObsiBibleApp, bible: String, ref: Ref, verses: Set<Int>, abbr: String): String {
    val ch = app.bibles.chapter(bible, ref.chapterRef) ?: return ""
    val text = ch.verses().filter { it.first in verses }.joinToString(" ") { if (verses.size > 1) "${it.first} ${it.second}" else it.second }
    val sorted = verses.sorted()
    val label = Ref(ref.book, ref.chapter, sorted.first(), if (sorted.size > 1) sorted.last() else 0).label
    return "$text\n$label $abbr"
}

private fun copyVerses(context: Context, app: app.obsibible.ObsiBibleApp, bible: String, ref: Ref, verses: Set<Int>, abbr: String) {
    val cm = context.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("Verse", versesText(app, bible, ref, verses, abbr)))
}

private fun shareVerses(context: Context, app: app.obsibible.ObsiBibleApp, bible: String, ref: Ref, verses: Set<Int>, abbr: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, versesText(app, bible, ref, verses, abbr))
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Text size and line spacing, seen live on the page behind the sheet. */
@Composable
fun TextSizeSheet(onDismiss: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = scheme.surfaceContainer, dragHandle = { SheetHandle() }, scrimColor = androidx.compose.ui.graphics.Color.Transparent) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text("Text size", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("A", fontFamily = SerifFont, fontSize = 16.sp)
                Slider(s.readerFontSize, { v -> app.settings.update { it.copy(readerFontSize = v) } }, Modifier.weight(1f).padding(horizontal = 12.dp), valueRange = 15f..32f,
                    colors = SliderDefaults.colors(thumbColor = scheme.onSurface, activeTrackColor = scheme.onSurface, inactiveTrackColor = scheme.surfaceContainerHighest))
                Text("A", fontFamily = SerifFont, fontSize = 28.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text("Line spacing", style = MaterialTheme.typography.titleMedium)
            Slider(s.lineHeight, { v -> app.settings.update { it.copy(lineHeight = v) } }, valueRange = 1.3f..2.2f,
                colors = SliderDefaults.colors(thumbColor = scheme.onSurface, activeTrackColor = scheme.onSurface, inactiveTrackColor = scheme.surfaceContainerHighest))
        }
    }
}
