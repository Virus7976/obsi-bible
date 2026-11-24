package app.obsibible

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.obsibible.audio.AudioEngine
import app.obsibible.bible.Bibles
import app.obsibible.data.PrefsStore
import app.obsi.core.data.SettingsStore
import app.obsi.core.data.VaultStore
import app.obsi.core.update.SelfUpdate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/**
 * Holds the app's long-lived parts. Screens reach them through LocalApp. Like the other Obsi apps it is an
 * ObsiHost: the look, Obsidian, templates and updates are the shared ones. [prefs] holds what only Bible sets.
 */
class ObsiBibleApp : Application(), app.obsi.core.ObsiHost {
    override lateinit var settings: SettingsStore
        private set
    /** Obsi Bible's own settings: the Bible, your place, voices, keys and the highlights file. */
    lateinit var prefs: PrefsStore
        private set
    override lateinit var updates: SelfUpdate
        private set
    override lateinit var vaults: VaultStore
        private set
    lateinit var bibles: Bibles
        private set
    lateinit var audio: AudioEngine
        private set
    lateinit var marks: app.obsibible.data.MarksStore
        private set
    /** When each highlight comes back for review. */
    lateinit var reviews: app.obsi.core.data.ReviewCards
        private set
    lateinit var keyScan: app.obsibible.bible.KeyScan
        private set
    lateinit var vaultTags: app.obsibible.obsidian.VaultTags
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val recentTags: StateFlow<List<String>> by lazy {
        settings.state.map { it.recentTags }.stateIn(appScope, SharingStarted.Eagerly, settings.value.recentTags)
    }

    /** The folder of the vault notes go to, as a document tree link. Empty when no vault is linked. */
    fun vaultTree(): String = settings.value.let { s -> s.linkedVault(s.vault)?.let { s.vaultFolders[it] } }.orEmpty()

    /** The name of that vault in Obsidian. */
    fun vaultName(): String = settings.value.let { s -> s.linkedVault(s.vault) ?: s.vault }

    /** A verse link tapped in Obsidian, like JHN.3.16. The reader opens it and clears it. */
    val openRef = MutableStateFlow<String?>(null)

    /** A highlight opened from the You tab. The reader opens it and clears it. */
    val openMark = MutableStateFlow<String?>(null)

    /** When the highlights files in the vault last matched the app, and why the last write failed. */
    val highlightsSynced = MutableStateFlow(0L)
    val highlightsError = MutableStateFlow<String?>(null)

    /** True while the reader scrolls into the text. The tabs hide so the page is full screen. */
    val reading = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        app.obsi.core.Brand.init(app.obsi.core.ObsiApp.Bible)
        app.obsi.core.share.SharedHighlights.source = ::sharedHighlights
        app.obsi.core.share.SharedSearch.source = { q ->
            val marks = app.obsi.core.share.SharedSearch.highlights(q, sharedHighlights())
            val bible = prefs.state.value.bible
            val verses = runCatching { bibles.search(bible, q, app.obsibible.bible.Scope.All, null, limit = 12).hits }.getOrDefault(emptyList()).map { h ->
                app.obsi.core.share.SharedHit(app.obsi.core.ObsiApp.Bible, "text", h.ref.label, bible, h.text, h.ref.key, "", 40.0)
            }
            marks + verses
        }
        app.obsi.core.share.SharedStyle.source = { app.obsi.core.share.ObsiStyle.of(settings.value) }
        app.obsi.core.share.SharedSetup.source = { app.obsi.core.share.SharedSetup.of(settings.value) }
        prefs = PrefsStore(this)
        // Before 0.13 the old settings file held the vault as one folder. The first start moves it to the
        // shared vault list, before the shared settings save over that file.
        val oldVault = if (prefs.moved) oldVault() else null
        settings = SettingsStore(this)
        if (oldVault != null && settings.value.vaultFolders.isEmpty()) {
            val (name, tree) = oldVault
            settings.update { it.copy(vaults = listOf(name), vault = name, vaultFolders = mapOf(name to tree)) }
        }
        // The two Bible templates, once, when there are no templates yet.
        settings.update { app.obsibible.obsidian.VerseNote.seeded(it) }
        vaults = VaultStore(this) { settings.value.vaultFolders }
        updates = SelfUpdate(this, settings)
        bibles = Bibles(this)
        bibles.keys = { prefs.value.let { app.obsibible.bible.Bibles.Keys(it.esvKey, it.apiBibleKey, it.youVersionKey, it.onlineBibles) } }
        // New or changed keys show their Bibles at once.
        appScope.launch {
            prefs.state.map { app.obsibible.bible.Bibles.Keys(it.esvKey, it.apiBibleKey, it.youVersionKey, it.onlineBibles) }.distinctUntilChanged().collect { bibles.refreshOnline() }
        }
        keyScan = app.obsibible.bible.KeyScan(prefs, appScope)
        vaultTags = app.obsibible.obsidian.VaultTags(this)
        // The vault's tags are read in the background, so tag suggestions include them.
        appScope.launch(Dispatchers.IO) { settings.state.map { vaultTree() }.distinctUntilChanged().collect { vaultTags.refresh(it, force = true) } }
        audio = AudioEngine(this)
        marks = app.obsibible.data.MarksStore(this)
        reviews = app.obsi.core.data.ReviewCards(this)
        app.obsi.core.share.SharedReview.due = {
            val now = System.currentTimeMillis()
            val byId = marks.all.value.associateBy { it.id }
            sharedHighlights().filter { h -> byId[h.key]?.let { m -> (reviews.dueAt(m.id, m.created) ?: Long.MAX_VALUE) <= now } == true }
        }
        app.obsi.core.share.SharedReview.act = { key, action -> reviews.act(key, action) }
        // Every highlight goes into the vault's highlights files, a moment after it changes.
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.flow.combine(marks.all, kotlinx.coroutines.flow.combine(settings.state, prefs.state) { _, p -> Triple(vaultTree(), p.highlightsFile, p.highlightsFolder) }.distinctUntilChanged()) { m, s -> m to s }
                .debounce(1500)
                .collect { (m, st) ->
                    val (tree, on, folder) = st
                    if (!on || tree.isBlank()) return@collect
                    val started = System.currentTimeMillis()
                    val err = app.obsibible.obsidian.HighlightsFile.sync(this@ObsiBibleApp, tree, folder, m) { id ->
                        bibles.installed.value.firstOrNull { it.id == id }?.abbreviation ?: id
                    }
                    highlightsError.value = err
                    if (err == null) highlightsSynced.value = started
                }
        }
        // Check every time the app comes to the front (at most once an hour).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                updates.checkIfDue()
            }
        })
    }

    /** The vault of the settings file from before 0.13, as its Obsidian name and folder link. */
    private fun oldVault(): Pair<String, String>? = runCatching {
        val o = org.json.JSONObject(java.io.File(filesDir, "settings.json").readText())
        val tree = o.optString("vaultTree")
        if (tree.isBlank()) return null
        val name = o.optString("vaultName").ifBlank {
            android.net.Uri.decode(tree).substringAfterLast(':').substringAfterLast('/').ifBlank { "Vault" }
        }
        name to tree
    }.getOrNull()

    override fun sampleVariables(s: app.obsi.core.data.AppSettings): Map<String, String> {
        val m = marks.all.value.maxByOrNull { it.created } ?: return emptyMap()
        return app.obsibible.obsidian.VerseNote.variables(app.obsibible.ui.clipFor(this, m))
    }

    @androidx.compose.runtime.Composable
    override fun IconPicker() = app.obsibible.ui.AppIconPicker()

    /** Every highlight and note, for ObsiTube and ObsiRead to show. */
    private fun sharedHighlights(): List<app.obsi.core.share.SharedHighlight> = marks.all.value
        .filter { it.hasColor || it.hasNote }
        .map { m ->
            app.obsi.core.share.SharedHighlight(
                app = app.obsi.core.ObsiApp.Bible, itemId = m.ref.key, itemTitle = m.label,
                text = m.quote.trim().ifBlank { m.label }, note = m.note,
                color = if (m.hasColor) app.obsibible.data.HighlightColors[m.color.coerceIn(app.obsibible.data.HighlightColors.indices)].ui else 0xFF9AA0A6,
                at = m.id, place = m.bible, created = m.created, tags = m.tags, key = m.id,
            )
        }
}
