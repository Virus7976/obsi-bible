package app.obsibible.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row in a quick switcher. [group] puts a small heading above the first row of each group. */
data class Choice(val id: String, val title: String, val detail: String = "", val group: String = "")

/**
 * The one picker used everywhere: type to filter, tap to pick. Fuzzy, so "andr" finds
 * "Andrew" and "us iol" finds "United States IOL".
 */
@Composable
fun QuickSwitcher(
    title: String,
    choices: List<Choice>,
    picked: String?,
    onPick: (Choice) -> Unit,
    onDismiss: () -> Unit,
    hint: String = "Type to filter",
    trailing: (@Composable (Choice) -> Unit)? = null,
) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf("") }
    val scheme = MaterialTheme.colorScheme
    val shown = remember(q, choices) { Fuzzy.filter(q, choices) { it.title + " " + it.detail + " " + it.group } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f).imePadding()) {
            Text(title, Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp).fillMaxWidth().clip(CircleShape).background(scheme.surfaceContainerHighest)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.size(10.dp))
                Box(Modifier.weight(1f)) {
                    if (q.isEmpty()) Text(hint, color = scheme.onSurfaceVariant, fontSize = 16.sp)
                    BasicTextField(q, { q = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontSize = 16.sp), cursorBrush = SolidColor(scheme.onSurface), modifier = Modifier.fillMaxWidth())
                }
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                var lastGroup: String? = null
                shown.forEachIndexed { i, c ->
                    if (q.isEmpty() && c.group.isNotEmpty() && c.group != lastGroup) {
                        val g = c.group
                        item(key = "g-$g-$i") { Text(g, Modifier.padding(start = 24.dp, top = 18.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant) }
                    }
                    lastGroup = c.group
                    item(key = c.id) {
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { onPick(c) }.padding(horizontal = 24.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (c.id == picked) FontWeight.SemiBold else FontWeight.Normal)
                                if (c.detail.isNotEmpty()) Text(c.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            trailing?.invoke(c)
                            if (c.id == picked) Icon(Icons.Rounded.Check, "Picked", Modifier.padding(start = 8.dp).size(22.dp))
                        }
                    }
                }
                if (shown.isEmpty()) item { Text("Nothing matches.", Modifier.padding(24.dp), color = scheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
fun SheetHandle() {
    Box(Modifier.padding(top = 12.dp, bottom = 16.dp).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)))
}

/** Small fuzzy matcher: every typed word must appear in order as a run of letters. */
object Fuzzy {
    fun score(query: String, text: String): Int? {
        val t = text.lowercase()
        var total = 0
        for (word in query.lowercase().split(' ').filter { it.isNotBlank() }) {
            val idx = t.indexOf(word)
            total += if (idx >= 0) (if (idx == 0 || !t[idx - 1].isLetterOrDigit()) 0 else 5) + idx / 10 else {
                // letters in order, possibly spread out
                var i = 0
                var gaps = 0
                var last = -1
                for ((j, c) in t.withIndex()) if (i < word.length && c == word[i]) { if (last >= 0) gaps += j - last - 1; last = j; i++ }
                if (i < word.length) return null
                20 + gaps
            }
        }
        return total
    }

    fun <T> filter(query: String, items: List<T>, text: (T) -> String): List<T> {
        if (query.isBlank()) return items
        return items.mapNotNull { item -> score(query, text(item))?.let { item to it } }.sortedBy { it.second }.map { it.first }
    }
}
