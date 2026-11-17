package app.obsibible

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.obsibible.audio.AudioEngine
import app.obsibible.bible.Bibles
import app.obsibible.data.SettingsStore
import app.obsibible.update.SelfUpdate
import app.obsibible.update.UpdateNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

/** Holds the app's long-lived parts. Screens reach them through LocalApp. */
class ObsiBibleApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var updates: SelfUpdate
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

    /** A verse link tapped in Obsidian, like JHN.3.16. The reader opens it and clears it. */
    val openRef = MutableStateFlow<String?>(null)

    /** Set when the update notification was tapped. The app shows the update, or installs it. */
    val updateTapped = MutableStateFlow<Boolean?>(null)

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
            val bible = settings.state.value.bible
            val verses = runCatching { bibles.search(bible, q, app.obsibible.bible.Scope.All, null, limit = 12).hits }.getOrDefault(emptyList()).map { h ->
                app.obsi.core.share.SharedHit(app.obsi.core.ObsiApp.Bible, "text", h.ref.label, bible, h.text, h.ref.key, "", 40.0)
            }
            marks + verses
        }
        // Bible keeps its own colours. It shares only light, dark or black with the other apps.
        app.obsi.core.share.SharedStyle.source = {
            settings.value.let { app.obsi.core.share.ObsiStyle(at = it.styleAt, appearance = it.appearance, full = false) }
        }
        app.obsi.core.share.SharedSetup.source = {
            settings.value.takeIf { it.vaultTree.isNotBlank() }?.let { app.obsi.core.share.ObsiSetup(app.obsi.core.ObsiApp.Bible, it.vaultName, it.vaultTree) }
        }
        settings = SettingsStore(this)
        updates = SelfUpdate(this, settings)
        bibles = Bibles(this)
        bibles.keys = { settings.value.let { app.obsibible.bible.Bibles.Keys(it.esvKey, it.apiBibleKey, it.youVersionKey, it.onlineBibles) } }
        // New or changed keys show their Bibles at once.
        appScope.launch {
            settings.state.map { app.obsibible.bible.Bibles.Keys(it.esvKey, it.apiBibleKey, it.youVersionKey, it.onlineBibles) }.distinctUntilChanged().collect { bibles.refreshOnline() }
        }
        keyScan = app.obsibible.bible.KeyScan(settings, appScope)
        // Note templates as in ObsiTube: the built-in ones are added once, an old custom template is kept.
        settings.update { app.obsibible.obsidian.TemplateNote.seeded(it) }
        vaultTags = app.obsibible.obsidian.VaultTags(this)
        // The vault's tags are read in the background, so tag suggestions include them.
        appScope.launch(Dispatchers.IO) { settings.state.map { it.vaultTree }.distinctUntilChanged().collect { vaultTags.refresh(it, force = true) } }
        // The 0.6 look has tighter lines. Move the old default once; a spacing the user chose stays.
        if (!settings.value.lookV3) settings.update { it.copy(lookV3 = true, lineHeight = if (it.lineHeight in 1.55f..1.8f) 1.4f else it.lineHeight, readerFontSize = if (it.readerFontSize == 21f) 20f else it.readerFontSize) }
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
            kotlinx.coroutines.flow.combine(marks.all, settings.state.map { Triple(it.vaultTree, it.highlightsFile, it.highlightsFolder) }.distinctUntilChanged()) { m, s -> m to s }
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
        UpdateNotice.schedule(this)
        // Check every time the app comes to the front (at most once an hour).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                updates.resumeAfterPermission()
                updates.checkIfDue()
            }
        })
    }

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
