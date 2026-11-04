package app.obsibible.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.toPath

/** A plain strip behind the status bar, so a list that scrolls under it does not run into the clock. */
@Composable
fun androidx.compose.foundation.layout.BoxScope.StatusBarScrim() {
    Box(
        Modifier.align(Alignment.TopCenter).fillMaxWidth()
            .windowInsetsTopHeight(androidx.compose.foundation.layout.WindowInsets.statusBars)
            .background(MaterialTheme.colorScheme.background),
    )
}

/** "1 video", "3 videos". */

/** Runs [block] when [key] really changes. A screen that comes back with its saved state does not count. */
@Composable
fun OnChanged(key: String, block: suspend () -> Unit) {
    var last by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(key) }
    androidx.compose.runtime.LaunchedEffect(key) { if (key != last) { last = key; block() } }
}

/** The top row of an inner page: back arrow, title, and an optional count under it. */
@Composable
fun BackRow(title: String, onBack: () -> Unit, subtitle: String? = null) {
    androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A bottom sheet. The stretch effect at the ends of a scroll fights the sheet's own drag and looks like
 * a glitch, so it is off inside every sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SmoothSheet(
    onDismissRequest: () -> Unit,
    sheetState: androidx.compose.material3.SheetState,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.foundation.LocalOverscrollFactory provides null) { content() }
    }
}

/** The highlight colours as tappable dots. The picked one is larger and ringed. */
@Composable
fun ColorDots(picked: Int, modifier: Modifier = Modifier, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        app.obsibible.data.HighlightColors.forEachIndexed { i, c ->
            Box(
                Modifier.size(32.dp).clip(CircleShape)
                    .clickable(onClickLabel = c.name) { onPick(i) }
                    .semantics { contentDescription = c.name; selected = i == picked },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(if (i == picked) 24.dp else 20.dp).clip(CircleShape).background(Color(c.ui))
                        .then(if (i == picked) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
                )
            }
        }
    }
}

/** Shrinks a little while pressed and springs back. */
@Composable
fun Modifier.springPress(source: MutableInteractionSource, to: Float = 0.96f): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) to else 1f, LocalSpring.current.spec(), label = "press")
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** A shape that is halfway between two Material shapes. */
class MorphShape(private val morph: Morph, private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = morph.toPath(t.coerceIn(0f, 1f)).asComposePath()
        val b = path.getBounds()
        val m = Matrix()
        if (b.width > 0f && b.height > 0f) {
            m.scale(size.width / b.width, size.height / b.height)
            m.translate(-b.left, -b.top)
        }
        path.transform(m)
        return Outline.Generic(path)
    }
}

/** The big play button. It is a scalloped cookie while paused and relaxes into a soft pill while playing. */
@Composable
fun MorphingPlayButton(
    playing: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    container: Color = MaterialTheme.colorScheme.primary,
    content: Color = MaterialTheme.colorScheme.onPrimary,
) {
    val progress by animateFloatAsState(
        targetValue = if (playing) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessLow),
        label = "morph",
    )
    val morph = remember { Morph(MaterialShapes.Cookie9Sided, MaterialShapes.Pill) }
    val source = remember { MutableInteractionSource() }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = modifier
            .springPress(source, 0.9f)
            .size(size)
            .graphicsLayer {
                shape = MorphShape(morph, progress)
                clip = true
            }
            .background(container)
            .clickable(interactionSource = source, indication = null) {
                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.ContextClick)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = playing,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.6f)) togetherWith (fadeOut() + scaleOut(targetScale = 0.6f)) },
            label = "icon",
        ) { isPlaying ->
            Icon(
                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play",
                tint = content,
                modifier = Modifier.size(size * 0.5f),
            )
        }
    }
}

/** A wavy progress bar you can tap or drag to seek. The wave moves only while playing. */
@Composable
fun WavySeekBar(
    positionSec: Double,
    durationSec: Double,
    playing: Boolean,
    onSeek: (Double) -> Unit,
    modifier: Modifier = Modifier,
    /** While the source loads, the wave itself moves along. No second bar. */
    loading: Boolean = false,
) {
    var width by remember { mutableIntStateOf(1) }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val frac = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
    val shown = dragging ?: frac
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp)
            .onSizeChanged { width = it.width.coerceAtLeast(1) }
            .pointerInput(durationSec) {
                detectTapGestures { o -> if (durationSec > 0) onSeek(o.x / width * durationSec) }
            }
            .pointerInput(durationSec) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragging = (o.x / width).coerceIn(0f, 1f) },
                    onDragEnd = {
                        dragging?.let { if (durationSec > 0) onSeek(it * durationSec) }
                        dragging = null
                    },
                    onDragCancel = { dragging = null },
                    onHorizontalDrag = { change, _ ->
                        dragging = (change.position.x / width).coerceIn(0f, 1f)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
        else LinearWavyProgressIndicator(
            progress = { shown },
            modifier = Modifier.fillMaxWidth(),
            amplitude = { if (playing) 1f else 0f },
        )
    }
}

/** A corner radius that eases from soft to tight while pressed. Cheap shape morph. */
@Composable
fun pressedRadius(source: MutableInteractionSource, rest: Dp, pressed: Dp): Dp {
    val isPressed by source.collectIsPressedAsState()
    val r by animateDpAsState(if (isPressed) pressed else rest, LocalSpring.current.spec(), label = "radius")
    return r
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Box(modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.CenterStart),
        )
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }
    }
}

@Composable
fun animatedColor(target: Color) = animateColorAsState(target, LocalSpring.current.spec(), label = "color").value

val CircleClip = CircleShape
