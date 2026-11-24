package app.obsibible.ui

import app.obsi.core.ui.WhatsNewAfterUpdate
import app.obsi.core.ui.ObsiOnLeave
import app.obsi.core.ui.StatsScreen
import kotlinx.coroutines.launch
import androidx.compose.animation.core.LinearEasing
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.SideEffect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.Crossfade
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.StickyNote2
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import app.obsibible.bible.Ref
import app.obsi.core.ui.MarginaliaTheme
import app.obsi.core.ui.FloatingTabs
import app.obsi.core.ui.TabItem
import app.obsi.core.ui.TemplateEditorScreen
import app.obsi.core.update.UpdateState
import app.obsi.core.ui.SettingsScreen as ObsiSettingsScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalApp = staticCompositionLocalOf<ObsiBibleApp> { error("no app") }

/** The tabs along the bottom. The Bible itself opens on top of them, like an article in ObsiRead. */
enum class Tab(val label: String, val icon: ImageVector, val on: ImageVector) {
    Home("Home", Icons.Outlined.Home, Icons.Filled.Home),
    Notes("Notes", Icons.AutoMirrored.Outlined.StickyNote2, Icons.AutoMirrored.Filled.StickyNote2),
    Search("Search", Icons.Outlined.Search, Icons.Filled.Search),
    You("You", Icons.Outlined.AccountCircle, Icons.Filled.AccountCircle),
}

/** Screens that open on top of the tabs. */
sealed interface Screen {
    data object Tabs : Screen
    data object Settings : Screen
    data object Search : Screen
    data object Versions : Screen
    data object Setup : Screen
    data object Stats : Screen
    data class Template(val id: String) : Screen
    /** One of Obsi Bible's own settings pages. */
    data class BiblePage(val id: String) : Screen
    /** The Bible reader, on top of the tabs, with no tab bar. */
    data object Reader : Screen
}

@Composable
fun AppRoot(app: ObsiBibleApp) {
    val settings by app.settings.state.collectAsState()
    CompositionLocalProvider(LocalApp provides app) {
        MarginaliaTheme(settings) {
            // After an update: what changed, one time.
            WhatsNewAfterUpdate()
            ObsiOnLeave()
            val stack = remember { mutableStateListOf<Screen>(Screen.Tabs) }
            var tab by remember { mutableStateOf(Tab.Home) }
            val holder = rememberSaveableStateHolder()
            fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
            fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            fun openRef(r: Ref) {
                app.prefs.update { it.copy(lastRef = r.key) }
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                stack.add(Screen.Reader)
            }
            BackHandler(enabled = stack.size > 1) { pop() }
            BackHandler(enabled = stack.size == 1 && tab != Tab.Home) { tab = Tab.Home }

            // A verse link from Obsidian opens in the reader.
            val linked by app.openRef.collectAsState()
            LaunchedEffect(linked) { linked?.let { Ref.fromKey(it) }?.let { openRef(it); app.openRef.value = null } }

            // The setup guide opens by itself once, on the first start.
            LaunchedEffect(Unit) { if (!app.settings.value.onboarded) push(Screen.Setup) }
            UpdatePrompt(app)

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        val depth = stack.size
                        var lastDepth by remember { mutableStateOf(depth) }
                        val forward = depth >= lastDepth
                        SideEffect { lastDepth = depth }
                        AnimatedContent(
                            targetState = stack.last(),
                            transitionSpec = {
                                val ease = tween<IntOffset>(260, easing = FastOutSlowInEasing)
                                val fade = tween<Float>(200)
                                // The screen in front is solid and moves; the one behind drifts a little and dims.
                                if (forward) ((slideInHorizontally(ease) { it / 3 } + fadeIn(tween(90))) togetherWith (slideOutHorizontally(ease) { -it / 12 } + fadeOut(tween(260, easing = LinearEasing), 0.4f))).apply { targetContentZIndex = 1f }
                                else ((slideInHorizontally(ease) { -it / 12 } + fadeIn(tween(260), 0.4f)) togetherWith slideOutHorizontally(ease) { it / 3 }).apply { targetContentZIndex = -1f }
                            },
                            label = "screen",
                        ) { screen ->
                            holder.SaveableStateProvider(screen.toString()) {
                              Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                                when (screen) {
                                    Screen.Tabs -> Tabs(tab, onTab = { tab = it }, push = ::push, openRef = ::openRef)
                                    Screen.Reader -> ReaderScreen(push = ::push, onBack = ::pop)
                                    Screen.Settings -> ObsiSettingsScreen(
                                        onBack = ::pop,
                                        onEditTemplate = { push(Screen.Template(it)) },
                                        appPages = BibleSettingsPages,
                                        onAppPage = { push(Screen.BiblePage(it)) },
                                    )
                                    is Screen.BiblePage -> BibleSettingsScreen(screen.id, onBack = ::pop, openSetup = { push(Screen.Setup) })
                                    Screen.Search -> SearchScreen(onOpen = ::openRef, onBack = ::pop, autofocus = true)
                                    Screen.Versions -> VersionsScreen(onBack = ::pop, openSetup = { push(Screen.Setup) })
                                    Screen.Setup -> SetupScreen(onDone = ::pop)
                                    Screen.Stats -> StatsScreen(onBack = ::pop)
                                    is Screen.Template -> TemplateEditorScreen(screen.id, onBack = ::pop)
                                }
                              }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A new version was found: say so once, with what changed. The download shows in the notifications. */
@Composable
private fun UpdatePrompt(app: ObsiBibleApp) {
    val update by app.updates.state.collectAsState()
    var later by remember { mutableStateOf<String?>(null) }
    val info = (update as? UpdateState.Available)?.info ?: return
    if (later == info.version) return
    AlertDialog(
        onDismissRequest = { later = info.version },
        title = { Text("Update ready: ${info.version}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(cleanNotes(info.notes).ifBlank { "A new version is ready." })
            }
        },
        confirmButton = { TextButton(onClick = { later = info.version; app.updates.installFlow(info) }) { Text("Install") } },
        dismissButton = { TextButton(onClick = { later = info.version }) { Text("Later") } },
    )
}

/** Release notes without the markdown heading, with plain bullets. */
fun cleanNotes(notes: String): String {
    val lines = notes.lines().filter { it.isNotBlank() && !it.trim().startsWith("#") }
        .map { it.trim().replace(Regex("^[-*]\\s+"), "• ") }
    // Keep whole lines only, so a long list never stops in the middle of a word.
    val out = ArrayList<String>()
    var size = 0
    for (l in lines) {
        if (size + l.length > 1500 && out.isNotEmpty()) break
        out += l
        size += l.length + 1
    }
    return out.joinToString("\n")
}

@Composable
private fun Tabs(tab: Tab, onTab: (Tab) -> Unit, push: (Screen) -> Unit, openRef: (Ref) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        val tabState = rememberSaveableStateHolder()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Crossfade(tab, animationSpec = tween(120), label = "tab") { t ->
                tabState.SaveableStateProvider(t.name) {
                    when (t) {
                        Tab.Home -> HomeTab(openRef = openRef, openSearch = { onTab(Tab.Search) }, openNotes = { onTab(Tab.Notes) }, openStats = { push(Screen.Stats) })
                        Tab.Search -> SearchScreen(onOpen = openRef, onBack = null, autofocus = false)
                        Tab.You -> YouTab(openSettings = { push(Screen.Settings) }, openRef = openRef)
                        Tab.Notes -> NotesTab(openRef = openRef)
                    }
                }
            }
        }
        // The keyboard covers the tabs anyway. Hiding them stops the space being counted twice.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val typing = WindowInsets.isImeVisible
        androidx.compose.animation.AnimatedVisibility(
            visible = !typing,
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)),
        ) { BottomTabs(tab, onTab) }
    }
}

/** The Bible reader as its own screen. While you read, the phone's own bars hide too; a swipe from the edge shows them. */
@Composable
private fun ReaderScreen(push: (Screen) -> Unit, onBack: () -> Unit) {
    val appRef = LocalApp.current
    val hostView = androidx.compose.ui.platform.LocalView.current
    val fullScreen by appRef.reading.collectAsState()
    LaunchedEffect(fullScreen) {
        val window = (hostView.context as? android.app.Activity)?.window ?: return@LaunchedEffect
        val bars = androidx.core.view.WindowCompat.getInsetsController(window, hostView)
        bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullScreen) bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            appRef.reading.value = false
            (hostView.context as? android.app.Activity)?.window?.let { w ->
                androidx.core.view.WindowCompat.getInsetsController(w, hostView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }
    }
    app.obsi.core.ui.ReaderTheme(app.obsi.core.ui.LocalSettings.current.readerTheme) {
        BibleTab(openSearch = { push(Screen.Search) }, openVersions = { push(Screen.Versions) }, onBack = onBack)
    }
}

/** The tabs: the floating bar every Obsi app has. A dot on You when an update is waiting. */
@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    val update by LocalApp.current.updates.state.collectAsState()
    val haptic = LocalHapticFeedback.current
    FloatingTabs(
        tabs = Tab.entries.map { t -> TabItem(t.name, t.label, if (t == tab) t.on else t.icon, badge = if (t == Tab.You && update is UpdateState.Available) "" else null) },
        selected = tab.name,
        onSelect = { id -> val t = Tab.valueOf(id); if (t != tab) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onTab(t) },
        modifier = Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** A title row with a back arrow, used on screens that open on top. */
@Composable
fun TopBack(title: String, onBack: () -> Unit, subtitle: String? = null) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(28.dp)) }
        Column {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PlayCircle(onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHighest).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.PlayArrow, "Listen", Modifier.size(26.dp))
    }
}
