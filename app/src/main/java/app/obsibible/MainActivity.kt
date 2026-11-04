package app.obsibible

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.obsibible.ui.AppRoot
import app.obsibible.update.UpdateNotice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent { AppRoot(application as ObsiBibleApp) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val app = application as ObsiBibleApp
        if (intent?.action == UpdateNotice.ACTION_UPDATE) {
            UpdateNotice.clear(this)
            app.updateTapped.value = intent.getBooleanExtra(UpdateNotice.EXTRA_INSTALL, false)
            return
        }
        refFrom(intent)?.let { app.openRef.value = it }
    }

    /** obsibible://ref/JHN.3.16 gives JHN.3.16. */
    private fun refFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "obsibible") return null
        return uri.pathSegments.lastOrNull()?.takeIf { uri.host == "ref" && it.isNotBlank() }
    }
}
