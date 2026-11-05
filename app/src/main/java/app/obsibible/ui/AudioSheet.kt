package app.obsibible.ui

import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.FastRewind
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.audio.CloudSpeech
import app.obsibible.audio.Narrators
import app.obsibible.audio.SLEEP_END_OF_CHAPTER
import app.obsibible.audio.Timings
import app.obsibible.audio.VoiceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val Speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

fun speedLabel(x: Float): String = (if (x == x.toInt().toFloat()) x.toInt().toString() else x.toString().trimEnd('0')) + "x"

/** The audio sheet, laid out like the reference: title, credit, three buttons, seek bar, speed and timer. */
@Composable
fun AudioSheet(idleTitle: String, onStart: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val engine = app.audio
    val u by engine.ui.collectAsState()
    val scheme = MaterialTheme.colorScheme
    var voices by remember { mutableStateOf(false) }
    var sleep by remember { mutableStateOf(false) }
    var drag by remember { mutableStateOf<Float?>(null) }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 28.dp).padding(bottom = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            val idle = remember(u.active, app.settings.state.collectAsState().value.audioSource) { engine.describe(app.settings.value.bible) }
            val voiceName = if (u.active) u.voiceName else idle.first
            val bibleTitle = if (u.active) u.title else idle.third
            val credit = if (u.active) u.credit else idle.second
            Text(
                if (u.active) u.ref?.label ?: idleTitle else idleTitle, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, maxLines = 2,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.clickable { voices = true }.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(listOf(voiceName, bibleTitle).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Pick a voice" }, color = scheme.onSurfaceVariant, fontSize = 16.sp)
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Change voice", Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
            }
            if (credit.isNotBlank()) Text(credit, color = scheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 13.sp)
            // Playing one chapter while reading another: offer the one on the page.
            if (u.active && u.ref?.label != null && u.ref?.label != idleTitle) {
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(scheme.surfaceContainerHighest)
                        .clickable { onStart() }.padding(horizontal = 18.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.PlayArrow, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Play $idleTitle", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
            u.error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = scheme.error, fontSize = 14.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(30.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                IconButton(onClick = { engine.previousVerse() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.FastRewind, "Previous verse", Modifier.size(48.dp)) }
                Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                    IconButton(onClick = { if (u.active) engine.toggle() else onStart() }, Modifier.size(84.dp)) {
                        Icon(if (u.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (u.playing) "Pause" else "Play", Modifier.size(76.dp))
                    }
                    if (u.loading) CircularProgressIndicator(Modifier.size(84.dp), strokeWidth = 2.dp, color = scheme.onSurfaceVariant)
                }
                IconButton(onClick = { engine.nextVerse() }, Modifier.size(64.dp)) { Icon(Icons.Rounded.FastForward, "Next verse", Modifier.size(48.dp)) }
            }
            Spacer(Modifier.height(24.dp))
            val frac = if (u.duration > 0) (u.position / u.duration).toFloat().coerceIn(0f, 1f) else 0f
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (!u.active) "00:00" else if (u.recorded) Timings.clock(u.position) else "Verse ${u.verse}", fontSize = 15.sp, modifier = Modifier.width(72.dp))
                ThinSeekBar(drag ?: frac, onMove = { drag = it }, onDone = { drag?.let { engine.seek(it) }; drag = null }, modifier = Modifier.weight(1f))
                Text(
                    if (!u.active) "" else if (u.recorded) Timings.clock(u.duration) else "of ${u.verses.lastOrNull() ?: 0}", fontSize = 15.sp,
                    modifier = Modifier.width(72.dp), textAlign = TextAlign.End,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    speedLabel(u.speed), fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { engine.setSpeed(Speeds[(Speeds.indexOf(u.speed) + 1).mod(Speeds.size)]) }.padding(8.dp),
                )
                Spacer(Modifier.weight(1f))
                // A clear button for the voice list, so the choice is easy to find.
                Row(
                    Modifier.clip(androidx.compose.foundation.shape.CircleShape).background(scheme.surfaceContainerHighest)
                        .clickable { voices = true }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.RecordVoiceOver, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Voice", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
                Spacer(Modifier.weight(1f))
                if (u.sleepMinutes != 0) Text(
                    if (u.sleepMinutes == SLEEP_END_OF_CHAPTER) "End of chapter" else Timings.clock(u.sleepLeft / 1000.0),
                    color = scheme.onSurfaceVariant, fontSize = 15.sp,
                )
                IconButton(onClick = { sleep = true }) { Icon(Icons.Outlined.Timer, "Sleep timer", Modifier.size(30.dp), tint = if (u.sleepMinutes != 0) scheme.onSurface else scheme.onSurface) }
            }
        }
    }

    if (voices) VoicePicker(u.bible.ifBlank { app.settings.value.bible }) { voices = false }
    if (sleep) {
        val opts = listOf(0 to "Off", 5 to "5 minutes", 10 to "10 minutes", 15 to "15 minutes", 30 to "30 minutes", 45 to "45 minutes", 60 to "1 hour", SLEEP_END_OF_CHAPTER to "End of chapter")
        QuickSwitcher(
            "Sleep timer", opts.map { Choice(it.first.toString(), it.second) }, u.sleepMinutes.toString(),
            onPick = { engine.setSleep(it.id.toInt()); sleep = false }, onDismiss = { sleep = false },
        )
    }
}

/** Every voice in one list: the recorded narrators, the phone's voices and Microsoft's voices. */
@Composable
fun VoicePicker(bible: String, onDone: () -> Unit) {
    val app = LocalApp.current
    val s = app.settings.value
    var phone by remember { mutableStateOf<List<VoiceInfo>?>(null) }
    var cloud by remember { mutableStateOf<List<VoiceInfo>>(emptyList()) }
    var cloudError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        phone = withContext(Dispatchers.IO) { app.audio.phone.voices() }
        if (s.cloudKey.isNotBlank() && s.cloudRegion.isNotBlank()) {
            runCatching { withContext(Dispatchers.IO) { CloudSpeech.voices(s.cloudKey, s.cloudRegion) } }
                .onSuccess { cloud = it }.onFailure { cloudError = it.message }
        }
    }
    val choices = buildList {
        Narrators.forBible(bible).forEach { add(Choice("narrator:${it.id}", it.name, if (it.streamOnly) "Streams with your ESV key" else "Recorded · needs internet the first time", "Recorded")) }
        add(Choice("phone:", "Phone default", "Works offline", "Phone voice"))
        phone.orEmpty().forEach { add(Choice("phone:${it.id}", it.name, it.detail, "Phone voice")) }
        if (!app.bibles.allowsCloudVoice(bible, s.cloudForOnline)) return@buildList
        cloud.forEach { add(Choice("cloud:${it.id}", it.name, it.detail, "Microsoft voices")) }
        if (s.cloudKey.isBlank()) add(Choice("settings", "Microsoft voices", "Add your key in Settings to use them", "Microsoft voices"))
        cloudError?.let { add(Choice("settings", "Microsoft voices", it, "Microsoft voices")) }
    }
    val picked = s.audioSource.let { if (it.startsWith("phone:") && it.removePrefix("phone:").isBlank()) "phone:" else it }
    QuickSwitcher(
        "Voice", choices, picked,
        onPick = { c ->
            app.audio.phone.stopPreview()
            if (c.id == "settings") { onDone(); return@QuickSwitcher }
            if (c.id.startsWith("phone:")) app.settings.update { it.copy(phoneVoice = c.id.removePrefix("phone:")) }
            if (c.id.startsWith("cloud:")) app.settings.update { it.copy(cloudVoice = c.id.removePrefix("cloud:")) }
            app.audio.setSource(c.id)
            onDone()
        },
        onDismiss = { app.audio.phone.stopPreview(); onDone() },
        hint = "Find a voice",
        trailing = { c ->
            if (c.id.startsWith("phone:") && c.id.length > 6) IconButton(onClick = { app.audio.phone.preview(c.id.removePrefix("phone:"), "In the beginning was the Word, and the Word was with God.") }) {
                Icon(Icons.Rounded.VolumeUp, "Hear it", Modifier.size(22.dp))
            }
        },
    )
}

/** A thin seek line with a round dot, like the reference. Tap or drag anywhere on it. */
@Composable
fun ThinSeekBar(value: Float, onMove: (Float) -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val track = scheme.surfaceContainerHighest
    val fill = scheme.onSurface
    var width by remember { mutableStateOf(1f) }
    val move by androidx.compose.runtime.rememberUpdatedState(onMove)
    val done by androidx.compose.runtime.rememberUpdatedState(onDone)
    androidx.compose.foundation.Canvas(
        modifier.height(36.dp).padding(horizontal = 12.dp)
            .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    move((down.position.x / width).coerceIn(0f, 1f))
                    do {
                        val ev = awaitPointerEvent()
                        ev.changes.forEach { if (it.pressed) { move((it.position.x / width).coerceIn(0f, 1f)); it.consume() } }
                    } while (ev.changes.any { it.pressed })
                    done()
                }
            },
    ) {
        val y = size.height / 2
        val stroke = 4.dp.toPx()
        drawLine(track, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke, cap = StrokeCap.Round)
        val x = size.width * value.coerceIn(0f, 1f)
        drawLine(fill, Offset(0f, y), Offset(x, y), strokeWidth = stroke, cap = StrokeCap.Round)
        drawCircle(fill, 9.dp.toPx(), Offset(x, y))
    }
}
