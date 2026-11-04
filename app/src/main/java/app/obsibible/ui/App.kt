package app.obsibible.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryAddCheck
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.ObsiBibleApp
import app.obsibible.update.UpdateState

val LocalApp = staticCompositionLocalOf<ObsiBibleApp> { error("no app") }

/** The five tabs along the bottom. */
enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Bible("Bible", Icons.Outlined.Book),
    Plans("Plans", Icons.Outlined.LibraryAddCheck),
    Search("Search", Icons.Outlined.Search),
    You("You", Icons.Outlined.AccountCircle),
}

/** Screens that open on top of the tabs. */
sealed interface Screen {
    data object Tabs : Screen
    data object Settings : Screen
}

@Composable
fun AppRoot(app: ObsiBibleApp) {
    val settings by app.settings.state.collectAsState()
    CompositionLocalProvider(LocalApp provides app) {
        ObsiBibleTheme(settings) {
            val stack = remember { mutableStateListOf<Screen>(Screen.Tabs) }
            var tab by remember { mutableStateOf(Tab.Bible) }
            val holder = rememberSaveableStateHolder()
            BackHandler(enabled = stack.size > 1) { stack.removeAt(stack.lastIndex) }
            BackHandler(enabled = stack.size == 1 && tab != Tab.Bible) { tab = Tab.Bible }

            UpdatePrompt(app)

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                AnimatedContent(
                    targetState = stack.last(),
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen",
                ) { screen ->
                    holder.SaveableStateProvider(screen.toString()) {
                        when (screen) {
                            Screen.Tabs -> Tabs(tab, onTab = { tab = it }, openSettings = { stack.add(Screen.Settings) })
                            Screen.Settings -> SettingsScreen(onBack = { stack.removeAt(stack.lastIndex) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdatePrompt(app: ObsiBibleApp) {
    val update by app.updates.state.collectAsState()
    var later by remember { mutableStateOf<String?>(null) }
    val info = (update as? UpdateState.Available)?.info ?: return
    if (later == info.version) return
    AlertDialog(
        onDismissRequest = { later = info.version },
        title = { Text("Update ready: ${info.version}") },
        text = { Text(info.notes.trim().ifBlank { "A new version is ready." }.take(500)) },
        confirmButton = { TextButton(onClick = { later = info.version; app.updates.installFlow(info) }) { Text("Install") } },
        dismissButton = { TextButton(onClick = { later = info.version }) { Text("Later") } },
    )
}

@Composable
private fun Tabs(tab: Tab, onTab: (Tab) -> Unit, openSettings: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Bible -> BibleTab()
                Tab.You -> YouTab(openSettings)
                else -> EmptyTab(tab)
            }
        }
        // The reader has its own bottom bar, so the tabs sit under it like in the reference.
        BottomTabs(tab, onTab)
    }
}

@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(scheme.background).navigationBarsPadding()) {
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEach { t ->
                val on = t == tab
                val color = if (on) scheme.onBackground else scheme.onSurfaceVariant
                Column(
                    Modifier.weight(1f).clickable(remember { MutableInteractionSource() }, null) { onTab(t) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(t.icon, t.label, Modifier.size(28.dp), tint = color)
                    Text(t.label, color = color, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/** The reader layout: top bar, page, and the play button with the chapter pill. The text arrives in 0.2. */
@Composable
private fun BibleTab() {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(
                Modifier.padding(end = 8.dp).clip(MaterialTheme.shapes.extraLarge).background(scheme.surfaceContainer)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("BSB", style = MaterialTheme.typography.titleMedium) }
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp), verticalArrangement = Arrangement.Center) {
            Text(
                "Bibles come in the next version.",
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SerifFont, fontSize = 22.sp),
                color = scheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = scheme.outlineVariant, thickness = 1.dp)
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(MaterialTheme.shapes.extraLarge).background(scheme.surfaceContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.PlayArrow, "Play", Modifier.size(30.dp))
            }
            Spacer(Modifier.size(14.dp))
            Row(
                Modifier.weight(1f).height(56.dp).clip(MaterialTheme.shapes.extraLarge).background(scheme.surfaceContainer),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous chapter") }
                Text("John 1", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next chapter") }
            }
        }
    }
}

@Composable
private fun YouTab(openSettings: () -> Unit) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            IconButton(onClick = openSettings) { Icon(Icons.Outlined.Settings, "Settings", Modifier.size(28.dp)) }
        }
        Text("Highlights and notes show here.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun EmptyTab(tab: Tab) {
    Box(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp)) {
        Text(tab.label, style = MaterialTheme.typography.headlineMedium)
    }
}

/** A title row with a back arrow, used on screens that open on top. */
@Composable
fun TopBack(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(28.dp)) }
        Text(title, style = MaterialTheme.typography.headlineSmall)
    }
}
