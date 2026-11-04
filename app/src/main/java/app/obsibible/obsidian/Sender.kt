package app.obsibible.obsidian

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

data class SendResult(val ok: Boolean, val message: String, val file: String = "")

/** Puts a note into Obsidian: straight into the vault folder, or through Obsidian's own link. */
object Sender {
    /** Writes the .md file into the vault folder the user picked. Adds to the end of a running note. */
    fun saveFile(context: Context, note: BuiltNote, treeUri: String): SendResult {
        val root = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
            ?: return SendResult(false, "Cannot open the vault folder. Pick it again in Settings.")
        if (!root.canWrite()) return SendResult(false, "The app cannot write to the vault folder. Pick it again in Settings.")
        var dir: DocumentFile = root
        for (part in note.folder.split("/").filter { it.isNotBlank() }) {
            dir = dir.findFile(part)?.takeIf { it.isDirectory } ?: dir.createDirectory(part)
                ?: return SendResult(false, "Could not make the folder $part.")
        }
        val fileName = "${note.name}.md"
        val existing = dir.findFile(fileName)
        return runCatching {
            if (note.append && existing != null) {
                val old = context.contentResolver.openInputStream(existing.uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val text = if (old.isBlank()) note.markdown else old.trimEnd() + "\n\n" + note.markdown
                context.contentResolver.openOutputStream(existing.uri, "wt")!!.bufferedWriter().use { it.write(text) }
                SendResult(true, "Added to ${note.name}.", path(note, note.name))
            } else {
                var name = note.name
                if (existing != null) {
                    var n = 2
                    while (dir.findFile("${note.name} $n.md") != null) n++
                    name = "${note.name} $n"
                }
                val target = dir.createFile("application/octet-stream", "$name.md") ?: error("no file")
                context.contentResolver.openOutputStream(target.uri, "wt")!!.bufferedWriter().use { it.write(note.markdown) }
                SendResult(true, "Saved $name.", path(note, name))
            }
        }.getOrElse { SendResult(false, "Could not write the note.") }
    }

    private fun path(note: BuiltNote, name: String) = listOf(note.folder, name).filter { it.isNotBlank() }.joinToString("/")

    /** Sends through Obsidian's obsidian://new link. Obsidian makes the file in the vault. */
    fun viaLink(context: Context, note: BuiltNote, vault: String): SendResult {
        val file = path(note, note.name)
        val v = if (vault.isBlank()) "" else "vault=${Uri.encode(vault)}&"
        val mode = if (note.append) "&append=true" else ""
        val base = "obsidian://new?${v}file=${Uri.encode(file)}$mode"
        val full = "$base&content=${Uri.encode(note.markdown)}"
        val uri = if (full.length <= 40_000) full else { copy(context, note.markdown); "$base&clipboard=true" }
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SendResult(true, "Sent to Obsidian.", file)
        } catch (e: ActivityNotFoundException) {
            copy(context, note.markdown)
            SendResult(false, "Obsidian is not on this phone. The note was copied.")
        }
    }

    /** Opens a note in Obsidian. False when Obsidian is not installed. */
    fun open(context: Context, vault: String, file: String): Boolean = try {
        val v = if (vault.isBlank()) "" else "vault=${Uri.encode(vault)}&"
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("obsidian://open?${v}file=${Uri.encode(file)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }

    fun copy(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Obsi Bible note", text))
    }

    /** The folder's name, for showing in Settings. */
    fun folderName(context: Context, treeUri: String): String? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri))?.name }.getOrNull()
}
