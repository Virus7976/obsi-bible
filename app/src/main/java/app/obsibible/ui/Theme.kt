@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package app.obsibible.ui

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.obsibible.R
import app.obsibible.data.AppSettings

/**
 * One locked look: near-black page, white text, grey details, flat grey cards and pills.
 * Taken from Jake's reference screenshots. Light and OLED are the same look with the page swapped.
 */
object Ink {
    val page = Color(0xFF121212)
    val card = Color(0xFF242424)
    val pill = Color(0xFF3A3A3A)
    val text = Color(0xFFF5F5F5)
    val muted = Color(0xFF9A9A9A)
    val faint = Color(0xFF6E6E6E)
    val line = Color(0xFF2E2E2E)
    val dot = Color(0xFF4CD964)

    val pageLight = Color(0xFFFFFFFF)
    val cardLight = Color(0xFFF1F1F1)
    val pillLight = Color(0xFFE2E2E2)
    val textLight = Color(0xFF121212)
    val mutedLight = Color(0xFF6B6B6B)
}

/** [mode] is light, dark or oled. */
fun buildScheme(mode: String): ColorScheme = if (mode == "light") {
    lightColorScheme(
        primary = Ink.textLight, onPrimary = Ink.pageLight,
        primaryContainer = Ink.pillLight, onPrimaryContainer = Ink.textLight,
        secondary = Ink.mutedLight, onSecondary = Ink.pageLight,
        secondaryContainer = Ink.pillLight, onSecondaryContainer = Ink.textLight,
        tertiary = Ink.dot, onTertiary = Color.White,
        background = Ink.pageLight, onBackground = Ink.textLight,
        surface = Ink.pageLight, onSurface = Ink.textLight,
        surfaceVariant = Ink.cardLight, onSurfaceVariant = Ink.mutedLight,
        surfaceContainerLowest = Ink.pageLight,
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Ink.cardLight,
        surfaceContainerHigh = Color(0xFFEAEAEA),
        surfaceContainerHighest = Ink.pillLight,
        outline = Color(0xFFBDBDBD), outlineVariant = Color(0xFFDDDDDD),
        error = Color(0xFFB3261E), onError = Color.White,
        inverseSurface = Ink.textLight, inverseOnSurface = Ink.pageLight, inversePrimary = Ink.text,
    )
} else {
    val page = if (mode == "oled") Color.Black else Ink.page
    darkColorScheme(
        primary = Ink.text, onPrimary = page,
        primaryContainer = Ink.pill, onPrimaryContainer = Ink.text,
        secondary = Ink.muted, onSecondary = page,
        secondaryContainer = Ink.pill, onSecondaryContainer = Ink.text,
        tertiary = Ink.dot, onTertiary = Color.Black,
        background = page, onBackground = Ink.text,
        surface = page, onSurface = Ink.text,
        surfaceVariant = Ink.card, onSurfaceVariant = Ink.muted,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF1A1A1A),
        surfaceContainer = Ink.card,
        surfaceContainerHigh = Color(0xFF2C2C2C),
        surfaceContainerHighest = Ink.pill,
        outline = Color(0xFF4A4A4A), outlineVariant = Ink.line,
        error = Color(0xFFFF6B5E), onError = Color.Black,
        inverseSurface = Ink.text, inverseOnSurface = page, inversePrimary = Ink.textLight,
    )
}

// ---------- fonts ----------

private fun variable(res: Int, weights: List<Int>, style: FontStyle = FontStyle.Normal) = weights.map { w ->
    Font(
        resId = res,
        weight = FontWeight(w),
        style = style,
        variationSettings = FontVariation.Settings(FontWeight(w), style),
    )
}

/** The interface font. A clean geometric sans. */
val SansFont = FontFamily(variable(R.font.figtree, listOf(400, 500, 600, 700)))

/** The Bible text. */
val SerifFont = FontFamily(variable(R.font.newsreader, listOf(400, 500, 600, 700)))

private fun TextStyle.sans() = copy(fontFamily = SansFont)

private fun typography(): Typography {
    val d = Typography()
    return d.copy(
        displayLarge = d.displayLarge.sans(), displayMedium = d.displayMedium.sans(), displaySmall = d.displaySmall.sans(),
        headlineLarge = d.headlineLarge.sans().copy(fontWeight = FontWeight.Bold),
        headlineMedium = d.headlineMedium.sans().copy(fontWeight = FontWeight.Bold),
        headlineSmall = d.headlineSmall.sans().copy(fontWeight = FontWeight.Bold),
        titleLarge = d.titleLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        titleMedium = d.titleMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        titleSmall = d.titleSmall.sans().copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = d.bodyLarge.sans(), bodyMedium = d.bodyMedium.sans(), bodySmall = d.bodySmall.sans(),
        labelLarge = d.labelLarge.sans().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = d.labelMedium.sans().copy(fontWeight = FontWeight.SemiBold),
        labelSmall = d.labelSmall.sans(),
    )
}

// ---------- motion ----------

/** How bouncy our own animations are. Calm, to suit reading. */
data class Spring(val damping: Float, val stiffness: Float) {
    fun <T> spec(): FiniteAnimationSpec<T> = spring(damping, stiffness)
}

val LocalSpring = staticCompositionLocalOf { Spring(0.9f, 420f) }

val LocalSettings = staticCompositionLocalOf { AppSettings() }

@Composable
fun ObsiBibleTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val systemDark = isSystemInDarkTheme()
    val mode = when (settings.appearance) {
        "light", "dark", "oled" -> settings.appearance
        else -> if (systemDark) "dark" else "light"
    }
    // The clock and the phone's buttons follow the app's theme, not the phone's.
    val view = androidx.compose.ui.platform.LocalView.current
    if (!view.isInEditMode) androidx.compose.runtime.SideEffect {
        var c = view.context
        while (c is android.content.ContextWrapper && c !is android.app.Activity) c = c.baseContext
        (c as? android.app.Activity)?.window?.let { w ->
            val bars = androidx.core.view.WindowInsetsControllerCompat(w, view)
            bars.isAppearanceLightStatusBars = mode == "light"
            bars.isAppearanceLightNavigationBars = mode == "light"
        }
    }
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
    CompositionLocalProvider(LocalSettings provides settings) {
        MaterialExpressiveTheme(
            colorScheme = buildScheme(mode),
            motionScheme = MotionScheme.standard(),
            shapes = shapes,
            typography = typography(),
            content = content,
        )
    }
}
