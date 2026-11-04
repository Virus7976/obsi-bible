package app.obsibible

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.obsibible.ui.AppRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as ObsiBibleApp
        refFrom(intent)?.let { app.openRef.value = it }
        setContent { AppRoot(app) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        refFrom(intent)?.let { (application as ObsiBibleApp).openRef.value = it }
    }

    /** obsibible://ref/JHN.3.16 gives JHN.3.16. */
    private fun refFrom(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (uri.scheme != "obsibible") return null
        return uri.pathSegments.lastOrNull()?.takeIf { uri.host == "ref" && it.isNotBlank() }
    }
}
