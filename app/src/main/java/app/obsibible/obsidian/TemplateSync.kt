package app.obsibible.obsidian

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import app.obsibible.ObsiBibleApp
import app.obsibible.data.ClipTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Keeps a template made from a vault note and that note the same, in both directions, as in ObsiTube. */
object TemplateSync {
    private fun readText(context: Context, uri: String): String? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    private fun writeText(context: Context, uri: String, text: String): Boolean = runCatching {
        context.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.bufferedWriter().use { it.write(text) }
        true
    }.getOrDefault(false)

    /** The vault's .obsidian/types.json file, or null. */
    private fun typesFile(context: Context, tree: String): DocumentFile? = runCatching {
        DocumentFile.fromTreeUri(context, Uri.parse(tree))?.findFile(".obsidian")?.findFile("types.json")
    }.getOrNull()

    fun types(context: Context, tree: String): Map<String, String> =
        typesFile(context, tree)?.let { readText(context, it.uri.toString()) }?.let(VaultTemplate::types).orEmpty()

    /** Makes a template from a vault note, or updates the one made from it before. Returns its id. */
    fun use(app: ObsiBibleApp, uri: String, path: String): String? {
        val text = readText(app, uri) ?: return null
        val s = app.settings.value
        val previous = s.templates.firstOrNull { it.source == path }
        val made = VaultTemplate.read(text, path, types(app, s.vaultTree), previous).copy(sourceUri = uri)
        app.settings.update { st ->
            val had = st.templates.any { it.id == made.id }
            st.copy(templates = if (had) st.templates.map { if (it.id == made.id) made else it } else st.templates + made, selectedTemplateId = made.id)
        }
        return made.id
    }

    /** Brings in what changed in Obsidian. Your choices for each property stay. */
    suspend fun pull(app: ObsiBibleApp, id: String) = withContext(Dispatchers.IO) {
        val t = app.settings.value.templates.firstOrNull { it.id == id } ?: return@withContext
        if (t.sourceUri.isBlank()) return@withContext
        val text = readText(app, t.sourceUri) ?: return@withContext
        val fresh = VaultTemplate.read(text, t.source, types(app, app.settings.value.vaultTree), t)
        if (fresh != t) app.settings.update { s -> s.copy(templates = s.templates.map { if (it.id == id) fresh else it }) }
    }

    /** Writes the template's changes into the vault note, and says so. */
    suspend fun push(app: ObsiBibleApp, id: String) = withContext(Dispatchers.IO) {
        val t = app.settings.value.templates.firstOrNull { it.id == id } ?: return@withContext
        if (t.sourceUri.isBlank()) return@withContext
        val tree = app.settings.value.vaultTree
        val text = readText(app, t.sourceUri) ?: return@withContext
        val updated = VaultTemplate.writeBack(text, t, VaultTemplate.read(text, t.source, types(app, tree)))
        val wrote = updated != null && writeText(app, t.sourceUri, updated)
        val tf = typesFile(app, tree)
        val typed = tf != null && readText(app, tf.uri.toString())?.let { VaultTemplate.writeTypes(it, t) }?.let { writeText(app, tf.uri.toString(), it) } == true
        if (wrote || typed) withContext(Dispatchers.Main) {
            Toast.makeText(app, "Updated ${t.source.substringAfterLast('/').removeSuffix(".md")} in your vault.", Toast.LENGTH_SHORT).show()
        }
    }
}
