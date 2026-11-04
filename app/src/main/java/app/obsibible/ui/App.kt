package app.obsibible.ui

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
import app.obsibible.update.UpdateNotice
import app.obsibible.update.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalApp = staticCompositionLocalOf<ObsiBibleApp> { error("no app") }

/** The five tabs along the bottom. */
enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Outlined.Home),
    Bible("Bible", Icons.Outlined.Book),
    Notes("Notes", Icons.AutoMirrored.Outlined.StickyNote2),
    Search("Search", Icons.Outlined.Search),
    You("You", Icons.Outlined.AccountCircle),
}

/** Screens that open on top of the tabs. */
sealed interface Screen {
    data object Tabs : Screen
    data object Settings : Screen
    data object Search : Screen
    data object Versions : Screen
    data object Setup : Screen
    data class Template(val id: String) : Screen
}

@Composable
fun AppRoot(app: ObsiBibleApp) {
    val settings by app.settings.state.collectAsState()
    CompositionLocalProvider(LocalApp provides app) {
        ObsiBibleTheme(settings) {
            val stack = remember { mutableStateListOf<Screen>(Screen.Tabs) }
            var tab by remember { mutableStateOf(Tab.Bible) }
            val holder = rememberSaveableStateHolder()
            fun push(s: Screen) { if (stack.last() != s) stack.add(s) }
            fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
            fun openRef(r: Ref) {
                app.settings.update { it.copy(lastRef = r.key) }
                while (stack.size > 1) stack.removeAt(stack.lastIndex)
                tab = Tab.Bible
            }
            BackHandler(enabled = stack.size > 1) { pop() }
            BackHandler(enabled = stack.size == 1 && tab != Tab.Bible) { tab = Tab.Bible }

            // A verse link from Obsidian opens in the reader.
            val linked by app.openRef.collectAsState()
            LaunchedEffect(linked) { linked?.let { Ref.fromKey(it) }?.let { openRef(it); app.openRef.value = null } }

            // The setup guide opens by itself once, on the first start.
            LaunchedEffect(Unit) { if (!app.settings.value.onboarded) push(Screen.Setup) }
            AskForNotifications(app)
            UpdatePrompt(app, openSettings = { push(Screen.Settings) })

            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        AnimatedContent(targetState = stack.last(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "screen") { screen ->
                            holder.SaveableStateProvider(screen.toString()) {
                                when (screen) {
                                    Screen.Tabs -> Tabs(tab, onTab = { tab = it }, push = ::push, openRef = ::openRef)
                                    Screen.Settings -> SettingsScreen(onBack = ::pop, openSetup = { push(Screen.Setup) }, openTemplate = { push(Screen.Template(it)) })
                                    Screen.Search -> SearchScreen(onOpen = ::openRef, onBack = ::pop, autofocus = true)
                                    Screen.Versions -> VersionsScreen(onBack = ::pop, openSetup = { push(Screen.Setup) })
                                    Screen.Setup -> SetupScreen(onDone = ::pop)
                                    is Screen.Template -> TemplateEditorScreen(screen.id, onBack = ::pop)
                                }
                            }
                        }
                    }
                    UpdateBar(app)
                }
            }
        }
    }
}

/** Asks once for permission to show notifications, so update notices can reach the user. */
@Composable
private fun AskForNotifications(app: ObsiBibleApp) {
    if (Build.VERSION.SDK_INT < 33) return
    val s by app.settings.state.collectAsState()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (!s.askedNotifications && !UpdateNotice.canNotify(app)) {
            app.settings.update { it.copy(askedNotifications = true) }
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun UpdatePrompt(app: ObsiBibleApp, openSettings: () -> Unit) {
    val update by app.updates.state.collectAsState()
    val tapped by app.updateTapped.collectAsState()
    var later by remember { mutableStateOf<String?>(null) }

    // The update notification was tapped: show the update, or start it straight away.
    LaunchedEffect(tapped) {
        val install = tapped ?: return@LaunchedEffect
        app.updateTapped.value = null
        var info = (app.updates.state.value as? UpdateState.Available)?.info
        if (info == null) info = runCatching { withContext(Dispatchers.IO) { app.updates.checkNow() } }.getOrNull()
        if (info == null) { openSettings(); return@LaunchedEffect }
        if (install) app.updates.installFlow(info) else later = null
    }

    val info = (update as? UpdateState.Available)?.info ?: return
    if (later == info.version) return
    AlertDialog(
        onDismissRequest = { later = info.version },
        title = { Text("Version ${info.version} is ready") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("You have ${app.updates.currentVersion}.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                val notes = cleanNotes(info.notes)
                if (notes.isNotBlank()) { Spacer(Modifier.height(12.dp)); Text(notes) }
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

/** A slim bar at the bottom while an update downloads and installs. */
@Composable
private fun UpdateBar(app: ObsiBibleApp) {
    val st by app.updates.state.collectAsState()
    val show = st is UpdateState.Downloading || st is UpdateState.Installing || st is UpdateState.Ready
    AnimatedVisibility(show) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().background(scheme.surfaceContainer).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            when (val u = st) {
                is UpdateState.Downloading -> {
                    val pct = if (u.total > 0) (u.done * 100 / u.total).toInt() else 0
                    Text("Downloading ${u.info.version} · $pct%", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (u.total > 0) LinearProgressIndicator(progress = { u.done / u.total.toFloat() }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateState.Installing -> {
                    Text("Installing ${u.info.version}", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                is UpdateState.Ready -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Version ${u.info.version} is downloaded", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { app.updates.install(u) }) { Text("Install") }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun Tabs(tab: Tab, onTab: (Tab) -> Unit, push: (Screen) -> Unit, openRef: (Ref) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                Tab.Home -> HomeTab(openRef = openRef, openSearch = { onTab(Tab.Search) })
                Tab.Bible -> BibleTab(openSearch = { push(Screen.Search) }, openVersions = { push(Screen.Versions) }, onBack = { onTab(Tab.Home) })
                Tab.Search -> SearchScreen(onOpen = openRef, onBack = null, autofocus = false)
                Tab.You -> YouTab(openSettings = { push(Screen.Settings) }, openRef = openRef)
                Tab.Notes -> NotesTab(openRef = openRef)
            }
        }
        // The keyboard covers the tabs anyway. Hiding them stops the space being counted twice.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        val typing = WindowInsets.isImeVisible
        val appRef = LocalApp.current
        // While reading, the phone's own bars hide too. A swipe from the edge shows them for a moment.
        val hostView = androidx.compose.ui.platform.LocalView.current
        val fullScreen = tab == Tab.Bible && appRef.reading.collectAsState().value
        LaunchedEffect(fullScreen) {
            val window = (hostView.context as? android.app.Activity)?.window ?: return@LaunchedEffect
            val bars = androidx.core.view.WindowCompat.getInsetsController(window, hostView)
            bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (fullScreen) bars.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            else bars.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
        androidx.compose.runtime.DisposableEffect(Unit) {
            onDispose {
                (hostView.context as? android.app.Activity)?.window?.let { w ->
                    androidx.core.view.WindowCompat.getInsetsController(w, hostView).show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val reading by appRef.reading.collectAsState()
        LaunchedEffect(tab) { if (tab != Tab.Bible) appRef.reading.value = false }
        androidx.compose.animation.AnimatedVisibility(
            visible = !typing && !(tab == Tab.Bible && reading),
            enter = androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(220)),
            exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(220)),
        ) { BottomTabs(tab, onTab) }
    }
}

@Composable
private fun BottomTabs(tab: Tab, onTab: (Tab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val update by LocalApp.current.updates.state.collectAsState()
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
                    Box {
                        Icon(t.icon, t.label, Modifier.size(28.dp), tint = color)
                        // A green dot on You when an update is waiting.
                        if (t == Tab.You && update is UpdateState.Available) Box(Modifier.align(Alignment.TopEnd).size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Ink.dot))
                    }
                    Text(t.label, color = color, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
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
