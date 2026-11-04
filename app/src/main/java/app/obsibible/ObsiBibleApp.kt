package app.obsibible

import android.app.Application
import app.obsibible.data.SettingsStore
import app.obsibible.update.SelfUpdate

/** Holds the app's long-lived parts. Screens reach them through LocalApp. */
class ObsiBibleApp : Application() {
    lateinit var settings: SettingsStore
        private set
    lateinit var updates: SelfUpdate
        private set

    val appScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)

    /** A verse link tapped in Obsidian, like JHN.3.16. The reader opens it and clears it. */
    val openRef = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        updates = SelfUpdate(this, settings)
        updates.checkIfDue()
    }
}
