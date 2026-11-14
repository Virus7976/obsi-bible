package app.obsibible

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.obsi.core.link.Links
import app.obsibible.bible.RefLinks
import app.obsibible.ui.AppRoot
import app.obsibible.update.UpdateNotice

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A restart (like a turn of the phone) must not send the same link again.
        if (savedInstanceState == null && handle(intent)) { finish(); return }
        setContent { AppRoot(application as ObsiBibleApp) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    /** Returns true when the intent went to another Obsi app, and this one has nothing to show. */
    private fun handle(intent: Intent?): Boolean {
        val app = application as ObsiBibleApp
        if (intent?.action == UpdateNotice.ACTION_UPDATE) {
            UpdateNotice.clear(this)
            app.updateTapped.value = intent.getBooleanExtra(UpdateNotice.EXTRA_INSTALL, false)
            return false
        }
        // ObsiTube or ObsiRead asked to open one of our highlights.
        intent?.getStringExtra(Links.EXTRA_OPEN_ID)?.let { key ->
            intent.removeExtra(Links.EXTRA_OPEN_ID)
            intent.getStringExtra(Links.EXTRA_OPEN_AT)?.takeIf { it.isNotBlank() }?.let { app.openMark.value = it }
            app.openRef.value = key
            return false
        }
        if (intent?.action == Intent.ACTION_SEND) {
            val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            // A verse or Bible link opens here. A video or web page goes to the app that reads it.
            val ref = RefLinks.find(text)
            if (ref != null) { app.openRef.value = ref.key; return false }
            if (Links.route(this, intent, text)) return true
            android.widget.Toast.makeText(this, "No Bible verse in that.", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        refFrom(intent)?.let { app.openRef.value = it }
        return false
    }

    /** obsibible://ref/JHN.3.16 gives JHN.3.16. */
    private fun refFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "obsibible") return null
        return uri.pathSegments.lastOrNull()?.takeIf { uri.host == "ref" && it.isNotBlank() }
    }
}
