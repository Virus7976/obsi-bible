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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Pending
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
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
import app.obsibible.data.Mark
import app.obsibible.data.Marks
import app.obsibible.obsidian.Clip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The Bible tab: the chapter, with listen, search, version and the chapter pill. */
@Composable
fun BibleTab(openSearch: () -> Unit, openVersions: () -> Unit, onBack: () -> Unit = {}) {
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
    val allMarks by app.marks.all.collectAsState()
    val chapterMarks = remember(allMarks, s.bible, ref.book, ref.chapter) { allMarks.filter { it.sameChapter(ref.book, ref.chapter) }.map { it.inBible(s.bible) } }
    var openMark by remember(ref.chapterRef, s.bible) { mutableStateOf<String?>(null) }
    var noteFor by remember { mutableStateOf<String?>(null) }
    var clipping by remember { mutableStateOf<Pair<Clip, Int>?>(null) }
    var clipMark by remember { mutableStateOf<String?>(null) }
    // Scrolled away from the verse being read: offer a button to go back to it.
    var away by remember { mutableStateOf(false) }
    // Playback waits while a note or the Obsidian sheet is open, then goes on by itself.
    var pausedForNote by remember { mutableStateOf(false) }
    val writing = noteFor != null || clipping != null
    LaunchedEffect(writing) {
        if (writing) {
            if (s.pauseWhileNoting && app.audio.ui.value.playing) { app.audio.pause(); pausedForNote = true }
        } else if (pausedForNote) {
            pausedForNote = false
            app.audio.toggle()
        }
    }
    val open = chapterMarks.firstOrNull { it.id == openMark }
    val pendingMark by app.openMark.collectAsState()
    LaunchedEffect(pendingMark, chapterMarks) {
        val id = pendingMark ?: return@LaunchedEffect
        if (chapterMarks.any { it.id == id }) { openMark = id; selected = emptySet(); app.openMark.value = null }
    }

    var retry by remember { mutableStateOf(0) }
    LaunchedEffect(ref.chapterRef, s.bible, bibleVersion, retry) {
        if (app.bibles.isOnline(s.bible)) chapter = null // show the loading line, not the last chapter under a new name
        chapter = withContext(Dispatchers.IO) { app.bibles.chapter(s.bible, ref.chapterRef) }
    }
    // The audio went on to the next chapter by itself: turn the page with it, but only when the page
    // showed the chapter that ended. Opening another chapter never pulls the reader back to the audio.
    val turn by app.audio.turnedTo.collectAsState()
    LaunchedEffect(turn) {
        val t = turn ?: return@LaunchedEffect
        app.audio.turnedTo.value = null
        if (audio.bible == s.bible && t.from == ref.chapterRef) app.settings.update { it.copy(lastRef = t.to.key) }
    }

    fun go(r: Ref?) { r ?: return; app.settings.update { it.copy(lastRef = r.key) } }

    val reading by app.reading.collectAsState()
    // Full screen: the tabs are gone, so the play bar keeps clear of the phone's own buttons.
    Column(Modifier.fillMaxSize().then(if (reading) Modifier.navigationBarsPadding() else Modifier)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(26.dp)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { sheet = true }) { Icon(Icons.AutoMirrored.Outlined.VolumeUp, "Listen", Modifier.size(26.dp)) }
            IconButton(onClick = openSearch) { Icon(Icons.Outlined.Search, "Search", Modifier.size(26.dp)) }
            IconButton(onClick = { textSheet = true }) { Icon(Icons.Outlined.Pending, "Text size", Modifier.size(26.dp)) }
            Spacer(Modifier.width(6.dp))
            Row(
                Modifier.clip(CircleShape).background(scheme.surfaceContainer).clickable(onClick = openVersions).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Language, null, Modifier.size(18.dp))
                Spacer(Modifier.width(5.dp))
                Text(abbreviation, style = MaterialTheme.typography.titleSmall, fontSize = 16.sp)
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
                    // Online Bibles must show their copyright. It sits small at the end of the chapter.
                    // The ESV links to esv.org as Crossway asks; API.Bible's free plan asks for a link to api.bible.
                    val uri = androidx.compose.ui.platform.LocalUriHandler.current
                    val credit = remember(s.bible) {
                        val service = app.bibles.serviceOf(s.bible)
                        val info = app.bibles.installed.value.firstOrNull { it.id == s.bible }
                        val cr = info?.copyright.orEmpty().lineSequence().firstOrNull().orEmpty().take(120)
                        when (service) {
                            "Crossway" -> "Scripture quotations are from the ESV® Bible, © 2001 by Crossway. esv.org" to "https://www.esv.org"
                            "API.Bible" -> listOf(cr, "via api.bible").filter { it.isNotBlank() }.joinToString(" · ") to "https://api.bible"
                            "YouVersion" -> listOf(cr, "via YouVersion").filter { it.isNotBlank() }.joinToString(" · ") to "https://www.bible.com"
                            else -> "" to ""
                        }
                    }
                    AndroidView(
                        factory = { ctx -> PageView(ctx).also { page = it } },
                        modifier = Modifier.fillMaxSize().clipToBounds(),
                        update = { v ->
                            v.onVerseTap = { verse ->
                                if (openMark != null) openMark = null
                                else selected = if (verse in selected) selected - verse else selected + verse
                            }
                            v.onNoteTap = { note = it }
                            v.credit = credit.first
                            v.onCreditTap = { uri.openUri(credit.second) }
                            v.onAway = { away = it }
                            v.onReading = { app.reading.value = it }
                            v.onMarkTap = { id -> selected = emptySet(); openMark = if (openMark == id) null else id }
                            v.onNoteIconTap = { id -> selected = emptySet(); openMark = id; noteFor = id }
                            v.onResize = { id, v1, c1, v2, c2 ->
                                app.marks.get(id)?.let { m ->
                                    val moved = m.copy(bible = s.bible, v1 = v1, c1 = c1, v2 = v2, c2 = c2, updated = System.currentTimeMillis())
                                    app.marks.put(moved.copy(quote = Marks.quote(ch.verses().toMap(), moved)))
                                }
                            }
                            // The key comes from the chapter itself, never from the pill: while the next
                            // chapter loads, the old text must not be filed under the new name.
                            val shown = Ref(ch.book, ch.number)
                            v.show(ch, "${s.bible}/${shown.key}/${colors.text}/${s.readerFontSize}/$bibleVersion/${credit.first.hashCode()}", s.readerFontSize, s.lineHeight, colors, padH, with(density) { 64.dp.roundToPx() }, with(density) { 120.dp.roundToPx() })
                            v.setSelected(selected)
                            v.setMarks(chapterMarks, openMark)
                            val following = audio.active && audio.bible == s.bible && audio.ref == shown
                            v.setFollow(if (following) audio.verse else 0, if (following) audio.word else null, following && audio.playing && s.followAudio)
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
                app.bibles.isOnline(s.bible) -> {
                    val err by app.bibles.onlineError.collectAsState()
                    Column(Modifier.fillMaxSize().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        if (err == null) { CircularProgressIndicator(); Spacer(Modifier.height(14.dp)); Text("Loading ${ref.chapterRef.label}", color = scheme.onSurfaceVariant) }
                        else {
                            Text(err!!, textAlign = TextAlign.Center, color = scheme.onSurfaceVariant)
                            Spacer(Modifier.height(14.dp))
                            TextButton(onClick = { retry++ }) { Text("Try again") }
                        }
                    }
                }
                else -> Text("${ref.label} is not in this Bible.", Modifier.padding(32.dp).align(Alignment.Center), color = scheme.onSurfaceVariant)
            }
            // Shows only while the verse being read is out of sight.
            val followingHere = audio.active && audio.bible == s.bible && audio.ref == ref.chapterRef && s.followAudio
            var verseOffScreen by remember { mutableStateOf(false) }
            LaunchedEffect(away, followingHere) {
                while (away && followingHere) {
                    verseOffScreen = page?.followVisible()?.not() ?: false
                    kotlinx.coroutines.delay(250)
                }
                verseOffScreen = false
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = away && followingHere && verseOffScreen,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
                enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.85f),
                exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.85f),
            ) {
                Row(
                    Modifier.clip(CircleShape).background(scheme.inverseSurface).clickable { page?.resync(); away = false }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(androidx.compose.material.icons.Icons.Rounded.MyLocation, null, Modifier.size(20.dp), tint = scheme.inverseOnSurface)
                    Spacer(Modifier.width(8.dp))
                    Text("Back to verse ${audio.verse}", color = scheme.inverseOnSurface, style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        HorizontalDivider(color = scheme.outlineVariant)
        if (open != null) {
            val m = open
            MarkBar(
                label = m.label,
                hint = if (m.hasColor) "Drag the ends to change it." else if (m.hasNote) "Note" else null,
                color = m.color.takeIf { it >= 0 },
                onColor = { c ->
                    app.marks.put(m.copy(color = c, updated = System.currentTimeMillis()))
                    app.settings.update { it.copy(lastColor = c) }
                },
                onNoColor = if (m.hasColor) ({
                    if (m.hasNote) app.marks.put(m.copy(color = -1, updated = System.currentTimeMillis())) else { app.marks.remove(m.id); openMark = null }
                }) else null,
                actions = listOf(
                    BarAction(BarIcons.note, "Note") { noteFor = m.id },
                    BarAction(BarIcons.obsidian, "Obsidian") { clipping = clipFor(app, m) to m.color; clipMark = m.id },
                    BarAction(BarIcons.copy, "Copy") { copyText(context, markText(app, m, abbreviation)); openMark = null },
                    BarAction(BarIcons.share, "Share") { shareText(context, markText(app, m, abbreviation)); openMark = null },
                    BarAction(BarIcons.delete, "Delete") { app.marks.remove(m.id); openMark = null },
                ),
                onClose = { openMark = null },
            )
        } else if (selected.isNotEmpty()) {
            val ch = chapter
            val touched = chapterMarks.any { m -> selected.any { m.covers(it) } && m.hasColor }
            MarkBar(
                label = Ref(ref.book, ref.chapter, selected.min(), if (selected.size > 1) selected.max() else 0).label,
                hint = "Pick a colour to highlight.",
                color = null,
                onColor = { c ->
                    if (ch != null) {
                        val (list, added) = Marks.paint(app.marks.all.value, s.bible, ref.book, ref.chapter, selected, c, ch.verses().toMap())
                        app.marks.set(list)
                        app.settings.update { it.copy(lastColor = c) }
                        selected = emptySet()
                        openMark = added.singleOrNull()?.id
                    }
                },
                onNoColor = if (touched) ({ app.marks.set(Marks.erase(app.marks.all.value, s.bible, ref.book, ref.chapter, selected)); selected = emptySet() }) else null,
                actions = listOf(
                    BarAction(BarIcons.play, "Play") { app.audio.play(s.bible, ref.chapterRef, selected.min()); selected = emptySet() },
                    BarAction(BarIcons.note, "Note") {
                        if (ch != null) {
                            val m0 = Mark(bible = s.bible, book = ref.book, chapter = ref.chapter, v1 = selected.min(), v2 = selected.max(), color = -1)
                            val m = m0.copy(quote = Marks.quote(ch.verses().toMap(), m0))
                            app.marks.put(m)
                            selected = emptySet()
                            openMark = m.id
                            noteFor = m.id
                        }
                    },
                    BarAction(BarIcons.obsidian, "Obsidian") {
                        if (ch != null) {
                            val m = Mark(bible = s.bible, book = ref.book, chapter = ref.chapter, v1 = selected.min(), v2 = selected.max(), color = -1)
                            clipping = clipFor(app, m) to -1
                            clipMark = null
                        }
                    },
                    BarAction(BarIcons.copy, "Copy") { copyVerses(context, app, s.bible, ref, selected, abbreviation); selected = emptySet() },
                    BarAction(BarIcons.share, "Share") { shareVerses(context, app, s.bible, ref, selected, abbreviation); selected = emptySet() },
                ),
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
    noteFor?.let { id ->
        val m = allMarks.firstOrNull { it.id == id }
        if (m == null) noteFor = null else NoteSheet(
            m,
            onSave = { text, tags ->
                val now = app.marks.get(id)
                if (now != null) {
                    if (text.isBlank() && tags.isEmpty() && !now.hasColor) { app.marks.remove(id); if (openMark == id) openMark = null }
                    else if (text != now.note || tags != now.tags) app.marks.put(now.copy(note = text, tags = tags, updated = System.currentTimeMillis()))
                }
            },
            onDismiss = { noteFor = null },
        )
    }
    clipping?.let { (clip, color) ->
        ClipSheet(clip, color, onSent = {
            clipMark?.let { id -> app.marks.get(id)?.let { app.marks.put(it.copy(clipped = System.currentTimeMillis())) } }
            clipping = null; selected = emptySet()
        }, onDismiss = { clipping = null })
    }
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

private fun versesText(app: app.obsibible.ObsiBibleApp, bible: String, ref: Ref, verses: Set<Int>, abbr: String): String {
    val ch = app.bibles.chapter(bible, ref.chapterRef) ?: return ""
    val text = ch.verses().filter { it.first in verses }.joinToString(" ") { if (verses.size > 1) "${it.first} ${it.second}" else it.second }
    val sorted = verses.sorted()
    val label = Ref(ref.book, ref.chapter, sorted.first(), if (sorted.size > 1) sorted.last() else 0).label
    return "$text\n$label $abbr"
}

private fun markText(app: app.obsibible.ObsiBibleApp, m: Mark, abbr: String): String {
    val c = clipFor(app, m)
    return "${c.text}\n${m.label} $abbr"
}

private fun copyText(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Verse", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
            Slider(s.lineHeight, { v -> app.settings.update { it.copy(lineHeight = v) } }, valueRange = 1.1f..2.0f,
                colors = SliderDefaults.colors(thumbColor = scheme.onSurface, activeTrackColor = scheme.onSurface, inactiveTrackColor = scheme.surfaceContainerHighest))
        }
    }
}
