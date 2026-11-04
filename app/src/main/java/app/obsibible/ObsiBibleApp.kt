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

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A verse link tapped in Obsidian, like JHN.3.16. The reader opens it and clears it. */
    val openRef = MutableStateFlow<String?>(null)

    /** Set when the update notification was tapped. The app shows the update, or installs it. */
    val updateTapped = MutableStateFlow<Boolean?>(null)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        updates = SelfUpdate(this, settings)
        bibles = Bibles(this)
        audio = AudioEngine(this)
        UpdateNotice.schedule(this)
        // Check every time the app comes to the front (at most once an hour).
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = updates.checkIfDue()
        })
    }
}
