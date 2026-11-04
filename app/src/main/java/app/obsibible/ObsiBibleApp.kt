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
    lateinit var keyScan: app.obsibible.bible.KeyScan
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A verse link tapped in Obsidian, like JHN.3.16. The reader opens it and clears it. */
    val openRef = MutableStateFlow<String?>(null)

    /** Set when the update notification was tapped. The app shows the update, or installs it. */
    val updateTapped = MutableStateFlow<Boolean?>(null)

    /** A highlight opened from the You tab. The reader opens it and clears it. */
    val openMark = MutableStateFlow<String?>(null)

    /** True while the reader scrolls into the text. The tabs hide so the page is full screen. */
    val reading = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        updates = SelfUpdate(this, settings)
        bibles = Bibles(this)
        bibles.keys = { settings.value.let { app.obsibible.bible.Bibles.Keys(it.esvKey, it.apiBibleKey, it.youVersionKey, it.onlineBibles) } }
        // New or changed keys show their Bibles at once.
        appScope.launch {
            settings.state.map { app.obsibible.bible.Bibles.Keys(it.esvKey, it.apiBibleKey, it.youVersionKey, it.onlineBibles) }.distinctUntilChanged().collect { bibles.refreshOnline() }
        }
        keyScan = app.obsibible.bible.KeyScan(settings, appScope)
        // The 0.6 look has tighter lines. Move the old default once; a spacing the user chose stays.
        if (!settings.value.lookV3) settings.update { it.copy(lookV3 = true, lineHeight = if (it.lineHeight in 1.55f..1.8f) 1.4f else it.lineHeight, readerFontSize = if (it.readerFontSize == 21f) 20f else it.readerFontSize) }
        audio = AudioEngine(this)
        marks = app.obsibible.data.MarksStore(this)
        UpdateNotice.schedule(this)
        // Check every time the app comes to the front (at most once an hour).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                updates.resumeAfterPermission()
                updates.checkIfDue()
            }
        })
    }
}
