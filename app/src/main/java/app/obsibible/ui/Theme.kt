package app.obsibible.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import app.obsi.core.ui.LocalSettings
import app.obsi.core.ui.readerFamily

/**
 * Obsi Bible uses the shared Obsi look: the theme, colours and fonts come from MarginaliaTheme and the
 * shared settings, like ObsiRead. These names keep the Bible screens short.
 */

/** The Bible text: the reader font picked in reading settings. */
val SerifFont: FontFamily
    @Composable get() = LocalSettings.current.let { readerFamily(it.readerFont, it.customFontPath) }

/** The interface font. */
val SansFont: FontFamily get() = app.obsi.core.ui.SansFont

/** Links and verse references. */
val LinkColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary

/** The theme's colours, by the names the Bible screens used before the shared look. */
object Ink {
    val page: Color @Composable get() = MaterialTheme.colorScheme.background
    val card: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainer
    val pill: Color @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest
    val text: Color @Composable get() = MaterialTheme.colorScheme.onSurface
    val muted: Color @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
    val faint: Color @Composable get() = MaterialTheme.colorScheme.outline
    val line: Color @Composable get() = MaterialTheme.colorScheme.outlineVariant
    val dot: Color @Composable get() = MaterialTheme.colorScheme.primary

    val pageLight: Color @Composable get() = page
    val cardLight: Color @Composable get() = card
    val pillLight: Color @Composable get() = pill
    val textLight: Color @Composable get() = text
    val mutedLight: Color @Composable get() = muted
}
