package app.obsibible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Every tag worth offering: yours from this app first, then the vault's, most used first. */
object TagPool {
    fun build(appTags: List<String>, vaultTags: Map<String, Int>): List<String> {
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (t in appTags + vaultTags.keys) if (seen.add(t.lowercase())) out += t
        return out
    }

    /** What to offer for [typed]: the most used when nothing is typed, else fuzzy matches. */
    fun suggest(pool: List<String>, typed: String, have: Collection<String>, limit: Int = 10): List<String> {
        val q = typed.trim().removePrefix("#")
        val free = pool.filter { p -> have.none { it.equals(p, true) } }
        return if (q.isBlank()) free.take(limit) else Fuzzy.filter(q, free) { it }.take(limit)
    }
}

/**
 * Tag chips with a box to type in, as one row, and suggestions from the app and the vault under it.
 * Space, comma or Done adds the typed tag. A tap on a suggestion adds it.
 */
@OptIn(ExperimentalLayoutApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TagEditor(tags: SnapshotStateList<String>, typed: String, onTyped: (String) -> Unit) {
    val app = LocalApp.current
    val scheme = MaterialTheme.colorScheme
    val marks by app.marks.all.collectAsState()
    val vault by app.vaultTags.tags.collectAsState()
    val pool = remember(marks, vault) { TagPool.build(app.marks.tags(), vault) }
    var focused by remember { mutableStateOf(false) }
    val bring = remember { BringIntoViewRequester() }
    fun add(t: String) {
        val clean = t.trim().removePrefix("#").replace(' ', '-')
        if (clean.isNotEmpty() && tags.none { it.equals(clean, true) }) tags += clean
        onTyped("")
    }
    val suggest = remember(pool, typed, tags.toList()) { TagPool.suggest(pool, typed, tags) }
    // Typing keeps the suggestions in sight above the keyboard.
    LaunchedEffect(focused, typed, suggest.size) { if (focused) runCatching { bring.bringIntoView() } }

    Column(Modifier.bringIntoViewRequester(bring)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { t -> TagChip("#$t", on = true) { tags.remove(t) } }
            BasicTextField(
                typed, { v -> if (v.endsWith(' ') || v.endsWith(',')) add(v.dropLast(1)) else onTyped(v) },
                Modifier.clip(CircleShape).background(scheme.surfaceContainerHighest).padding(horizontal = 14.dp, vertical = 9.dp)
                    .widthIn(min = 100.dp).onFocusChanged { focused = it.isFocused; if (!it.isFocused && typed.isNotBlank()) add(typed) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(scheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add(typed) }),
                decorationBox = { inner -> Box { if (typed.isEmpty()) Text("+ Add tag", color = scheme.onSurfaceVariant, fontSize = 15.sp); inner() } },
            )
        }
        if (suggest.isNotEmpty() && (focused || typed.isNotBlank() || tags.isEmpty())) {
            Spacer(Modifier.height(10.dp))
            Text(if (typed.isBlank()) "Your tags" else "Matching tags", style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                suggest.forEach { t -> TagChip("#$t", on = false) { add(t) } }
            }
        }
    }
}
