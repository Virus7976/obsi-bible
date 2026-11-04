package app.obsibible.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.bible.Ref
import app.obsibible.data.HighlightColors
import app.obsibible.data.Mark
import app.obsibible.obsidian.Clip
import app.obsibible.update.UpdateState

private enum class Feed(val label: String, val icon: ImageVector?) {
    All("All", null), Highlights("Highlights", Icons.Outlined.Brush), Notes("Notes", Icons.AutoMirrored.Outlined.StickyNote2)
}

/** The You tab: everything you highlighted and wrote, newest first. */
@Composable
fun YouTab(openSettings: () -> Unit, openRef: (Ref) -> Unit) {
    val app = LocalApp.current
    val update by app.updates.state.collectAsState()
    val marks by app.marks.all.collectAsState()
    val installed by app.bibles.installed.collectAsState()
    var filter by remember { mutableStateOf(Feed.All) }
    var noteFor by remember { mutableStateOf<String?>(null) }
    var clipping by remember { mutableStateOf<Pair<Mark, Clip>?>(null) }
    val scheme = MaterialTheme.colorScheme
    val shown = remember(marks, filter) {
        marks.filter {
            when (filter) {
                Feed.All -> it.hasColor || it.hasNote
                Feed.Highlights -> it.hasColor
                Feed.Notes -> it.hasNote
            }
        }.sortedByDescending { it.updated }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Activity", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Box {
                IconButton(onClick = openSettings) { Icon(Icons.Outlined.Settings, "Settings", Modifier.size(28.dp)) }
                if (update is UpdateState.Available) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(9.dp).clip(CircleShape).background(Ink.dot))
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Feed.entries.forEach { f -> FeedChip(f, f == filter) { filter = f } }
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp)) {
                Text(
                    when (filter) { Feed.Notes -> "No notes yet."; Feed.Highlights -> "No highlights yet."; else -> "Nothing here yet." },
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(6.dp))
                Text("Tap a verse in the Bible to highlight it or write a note.", color = scheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(shown, key = { it.id }) { m ->
                    val abbr = installed.firstOrNull { it.id == m.bible }?.abbreviation ?: m.bible
                    MarkCard(
                        m, abbr,
                        onOpen = { app.openMark.value = m.id; openRef(m.ref) },
                        onNote = { noteFor = m.id },
                        onClip = { clipping = m to clipFor(app, m) },
                        onDelete = { app.marks.remove(m.id) },
                    )
                }
            }
        }
    }

    noteFor?.let { id ->
        val m = marks.firstOrNull { it.id == id }
        if (m == null) noteFor = null else NoteSheet(
            m,
            onSave = { text, tags ->
                app.marks.get(id)?.let { now ->
                    if (text.isBlank() && !now.hasColor) app.marks.remove(id)
                    else if (text != now.note || tags != now.tags) app.marks.put(now.copy(note = text, tags = tags, updated = System.currentTimeMillis()))
                }
            },
            onDismiss = { noteFor = null },
        )
    }
    clipping?.let { (m, clip) ->
        ClipSheet(clip, m.color, onSent = {
            app.marks.get(m.id)?.let { app.marks.put(it.copy(clipped = System.currentTimeMillis())) }
            clipping = null
        }, onDismiss = { clipping = null })
    }
}

@Composable
private fun FeedChip(f: Feed, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape)
            .then(if (on) Modifier.background(scheme.onSurface) else Modifier.border(1.5.dp, scheme.outline, CircleShape))
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (f.icon != null) {
            Icon(f.icon, null, Modifier.size(20.dp), tint = if (on) scheme.surface else scheme.onSurface)
            Spacer(Modifier.width(8.dp))
        }
        Text(f.label, color = if (on) scheme.surface else scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

private fun ago(t: Long): String {
    val d = System.currentTimeMillis() - t
    return when {
        d < DateUtils.MINUTE_IN_MILLIS -> "now"
        d < DateUtils.HOUR_IN_MILLIS -> "${d / DateUtils.MINUTE_IN_MILLIS}m"
        d < DateUtils.DAY_IN_MILLIS -> "${d / DateUtils.HOUR_IN_MILLIS}h"
        d < DateUtils.WEEK_IN_MILLIS -> "${d / DateUtils.DAY_IN_MILLIS}d"
        else -> "${d / DateUtils.WEEK_IN_MILLIS}w"
    }
}

/** One card in the feed, shaped like the reference: who did what, the verse with a bar, the reference. */
@Composable
fun MarkCard(m: Mark, abbr: String, onOpen: () -> Unit, onNote: () -> Unit, onClip: () -> Unit, onDelete: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val tint = if (m.hasColor) Color(HighlightColors[m.color.coerceIn(HighlightColors.indices)].ui) else scheme.onSurfaceVariant
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(scheme.surfaceContainer).clickable(onClick = onOpen).padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.size(46.dp).clip(CircleShape).border(2.5.dp, scheme.onSurface, CircleShape), contentAlignment = Alignment.Center) {
                if (m.hasColor && !m.hasNote) Box(Modifier.size(18.dp).clip(CircleShape).background(tint))
                else Icon(if (m.hasNote) Icons.AutoMirrored.Outlined.StickyNote2 else Icons.Outlined.Brush, null, Modifier.size(22.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                buildAnnotatedString {
                    append(if (m.hasNote) "You wrote a note on " else "You highlighted ")
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("${m.label} $abbr") }
                },
                Modifier.weight(1f).padding(top = 2.dp), fontSize = 18.sp, lineHeight = 24.sp,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(start = 8.dp, end = 6.dp)) {
                Text(ago(m.updated), color = scheme.onSurfaceVariant, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(10.dp).clip(CircleShape).background(tint))
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(Modifier.height(IntrinsicSize.Min).padding(start = 10.dp, end = 8.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(scheme.onSurface))
            Spacer(Modifier.width(18.dp))
            Column {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 11.sp, color = scheme.onSurfaceVariant, baselineShift = BaselineShift(0.5f))) { append("${m.v1} ") }
                        append(m.quote)
                    },
                    fontFamily = SerifFont, fontSize = 19.sp, lineHeight = 29.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                Text("${m.label} $abbr", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        if (m.hasNote) {
            Spacer(Modifier.height(12.dp))
            LinkedText(m.note, Modifier.padding(start = 10.dp, end = 8.dp), fontSize = 16.sp, maxLines = 6)
        }
        if (m.tags.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(m.tags.joinToString("  ") { "#$it" }, Modifier.padding(start = 10.dp), color = scheme.onSurfaceVariant, fontSize = 14.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClip) { Icon(BarIcons.obsidian, "Send to Obsidian", Modifier.size(22.dp), tint = if (m.clipped > 0) Color(0xFFA88BFA) else scheme.onSurface) }
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "${m.quote}\n${m.label} $abbr")
                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Icon(BarIcons.share, "Share", Modifier.size(22.dp)) }
            if (m.clipped > 0) Text("In Obsidian", color = scheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", Modifier.size(24.dp)) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(if (m.hasNote) "Edit note" else "Add a note") }, onClick = { menu = false; onNote() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

private enum class NoteSort(val label: String) { Book("By book"), Newest("Newest") }

/** The Notes tab: every highlight and note as a library, by book or newest, with tags to narrow it. */
@Composable
fun NotesTab(openRef: (Ref) -> Unit) {
    val app = LocalApp.current
    val marks by app.marks.all.collectAsState()
    val installed by app.bibles.installed.collectAsState()
    var sort by remember { mutableStateOf(NoteSort.Book) }
    var tag by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    val tags = remember(marks) { marks.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value } }
    val shown = remember(marks, tag) { marks.filter { (it.hasColor || it.hasNote) && (tag == null || tag in it.tags) } }
    val groups: List<Pair<String, List<Mark>>> = remember(shown, sort) {
        when (sort) {
            NoteSort.Book -> shown.sortedWith(compareBy({ it.ref.bookInfo.number }, { it.chapter }, { it.v1 }, { it.c1 }))
                .groupBy { it.ref.bookInfo.name }.toList()
            NoteSort.Newest -> listOf("" to shown.sortedByDescending { it.updated })
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Notes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("${shown.size}", color = scheme.onSurfaceVariant, fontSize = 16.sp)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NoteSort.entries.forEach { o -> SmallChip(o.label, o == sort) { sort = o } }
            if (tags.isNotEmpty()) Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(36.dp).background(scheme.outlineVariant))
            tags.forEach { (t, n) -> SmallChip("#$t $n", t == tag) { tag = if (tag == t) null else t } }
        }
        if (shown.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(32.dp)) {
                Text("No notes yet.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("Tap a verse in the Bible, then tap Note.", color = scheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                groups.forEach { (title, list) ->
                    if (title.isNotEmpty()) item(key = "h-$title") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                            Text("${list.size}", color = scheme.onSurfaceVariant)
                        }
                    }
                    items(list, key = { it.id }) { m ->
                        val abbr = installed.firstOrNull { it.id == m.bible }?.abbreviation ?: m.bible
                        NoteRow(m, abbr) { app.openMark.value = m.id; openRef(m.ref) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.clip(CircleShape)
            .then(if (on) Modifier.background(scheme.onSurface) else Modifier.border(1.5.dp, scheme.outline, CircleShape))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(label, color = if (on) scheme.surface else scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 15.sp) }
}

/** One note in the library: colour bar, reference, the words, and the note. */
@Composable
private fun NoteRow(m: Mark, abbr: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bar = if (m.hasColor) Color(HighlightColors[m.color.coerceIn(HighlightColors.indices)].ui) else scheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp).height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(bar))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${m.label} $abbr", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (m.clipped > 0) Icon(BarIcons.obsidian, "In Obsidian", Modifier.size(14.dp), tint = LinkColor)
            }
            Spacer(Modifier.height(2.dp))
            Text(m.quote, fontFamily = SerifFont, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = scheme.onSurface.copy(alpha = if (m.hasNote) 0.75f else 1f))
            if (m.hasNote) Text(app.obsibible.obsidian.NoteLinks.plain(m.note), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
