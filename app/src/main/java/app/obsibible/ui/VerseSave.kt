package app.obsibible.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import app.obsi.core.data.AppSettings
import app.obsi.core.data.ClipMemo
import app.obsi.core.data.ClipTemplate
import app.obsi.core.obsidian.BuiltNote
import app.obsi.core.obsidian.MergeResult
import app.obsi.core.obsidian.SaveSubject
import app.obsi.core.ui.SaveSheet as SharedSaveSheet
import app.obsibible.obsidian.Clip
import app.obsibible.obsidian.NoteBuilder
import app.obsibible.obsidian.VerseNote

/** Verses and what you wrote about them, for the shared save sheet. */
class VerseSubject(private val clip: Clip) : SaveSubject {
    override val title get() = clip.ref.label
    override val subtitle get() = clip.translationName
    override val sourceUrl get() = NoteBuilder.link(clip.ref)
    override val lastClip: ClipMemo? get() = null

    override fun build(template: ClipTemplate, settings: AppSettings, overrides: Map<String, String>, name: String?, folder: String?, vault: String?): BuiltNote =
        VerseNote.build(template, clip, settings, overrides, name, folder, vault)

    override fun variables(settings: AppSettings) = VerseNote.variables(clip)
    override fun readValues(text: String) = VerseNote.readValues(text)
    override fun merge(existing: String, note: BuiltNote, settings: AppSettings, replace: Set<String>): MergeResult = VerseNote.merge(existing, note)
    override fun saveMemo(memo: ClipMemo) {}
    override fun savedTo(vault: String, path: String) {}

    /** The book's short name on a coloured square, like a cover. */
    @Composable
    override fun Thumb(modifier: Modifier) {
        Box(modifier.background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text(clip.ref.book.take(3), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

/** Save verses to Obsidian with the shared save sheet: template, vault, folder and properties, as in ObsiRead. */
@Composable
fun ClipSheet(clip: Clip, @Suppress("UNUSED_PARAMETER") color: Int, onSent: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val subject = remember(clip) { VerseSubject(clip) }
    SharedSaveSheet(subject, onDismiss) { message, ok ->
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        if (ok) onSent() else onDismiss()
    }
}
