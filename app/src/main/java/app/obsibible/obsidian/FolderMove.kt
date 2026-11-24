package app.obsibible.obsidian

import android.provider.DocumentsContract
import app.obsi.core.obsidian.ObsiFolder
import app.obsi.core.obsidian.VaultNotes
import app.obsibible.ObsiBibleApp

/**
 * Once: the notes Obsi Bible made before the shared Obsi folder move into it. The highlights files go from
 * Bible/Highlights to Obsi/Bible/Highlights. Verse notes go from Bible to Obsi/Bible. Only notes that link
 * back to Obsi Bible move, so your own notes in a "Bible" folder stay where they are. A folder you picked
 * yourself is not moved.
 */
object FolderMove {
    fun run(app: ObsiBibleApp) {
        if (app.prefs.value.foldersVersion >= 1) return
        // The templates follow even with no vault yet.
        app.settings.update { VerseNote.toObsiFolder(it) }
        val s = app.settings.value
        if (app.vaultTree().isBlank()) { app.prefs.update { it.copy(foldersVersion = 1) }; return }
        val p = app.prefs.value
        if (p.highlightsFile && p.highlightsFolder.trim('/') in setOf("", VerseNote.OLD_HIGHLIGHTS)) {
            if (p.highlightsFolder.isNotBlank()) app.prefs.update { it.copy(highlightsFolder = "") }
            ObsiFolder.moveAll(app, s, VerseNote.OLD_HIGHLIGHTS, ObsiFolder.of(s, "Highlights"))
        }
        moveVerseNotes(app, s)
        app.prefs.update { it.copy(foldersVersion = 1) }
    }

    /** Moves the notes in the old Bible folder that link to Obsi Bible. A name already in the new folder stays. */
    private fun moveVerseNotes(app: ObsiBibleApp, s: app.obsi.core.data.AppSettings) = runCatching {
        val src = VaultNotes.folder(app, s, VerseNote.OLD_FOLDER, false) ?: return@runCatching
        val r = app.contentResolver
        val ours = src.listFiles().filter { f ->
            f.isFile && f.name.orEmpty().endsWith(".md") &&
                runCatching { r.openInputStream(f.uri)!!.bufferedReader().use { it.readText() }.contains("obsibible://") }.getOrDefault(false)
        }
        if (ours.isEmpty()) return@runCatching
        val dst = VaultNotes.folder(app, s, ObsiFolder.of(s), true) ?: return@runCatching
        for (f in ours) {
            val name = f.name ?: continue
            if (dst.findFile(name) != null) continue
            val moved = runCatching { DocumentsContract.moveDocument(r, f.uri, src.uri, dst.uri) }.getOrNull() != null
            if (moved) continue
            // Some storage cannot move: copy, check the size, then delete.
            val copy = dst.createFile("application/octet-stream", name) ?: continue
            val ok = runCatching {
                r.openInputStream(f.uri)!!.use { i -> r.openOutputStream(copy.uri, "w")!!.use { o -> i.copyTo(o) } }
                copy.length() == f.length()
            }.getOrDefault(false)
            if (ok) f.delete() else copy.delete()
        }
    }
}
