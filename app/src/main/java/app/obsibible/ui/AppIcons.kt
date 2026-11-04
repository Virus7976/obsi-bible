package app.obsibible.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.obsibible.R

class AppIcon(val id: String, val label: String, val art: Int, val bg: Color = Color(0xFF121212))

/** Each one is an activity-alias in the manifest, named Icon plus its id. */
val AppIcons = listOf(
    AppIcon("Night", "Night", R.drawable.ic_book_white),
    AppIcon("Paper", "Paper", R.drawable.ic_book_black, Color(0xFFF5F1E8)),
)

private fun component(ctx: Context, id: String) = ComponentName(ctx.packageName, "app.obsibible.Icon$id")

/** The system holds this, so what shows here is always what the phone shows. */
fun currentAppIcon(ctx: Context): String = AppIcons.firstOrNull {
    ctx.packageManager.getComponentEnabledSetting(component(ctx, it.id)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
}?.id ?: AppIcons.first().id

fun setAppIcon(ctx: Context, id: String) {
    val pm = ctx.packageManager
    // The new one goes on first, so the app is never left with no icon at all.
    pm.setComponentEnabledSetting(component(ctx, id), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
    AppIcons.filter { it.id != id }.forEach {
        pm.setComponentEnabledSetting(component(ctx, it.id), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
    }
}

@Composable
fun AppIconPicker() {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var picked by remember { mutableStateOf(currentAppIcon(ctx)) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("App icon", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 4.dp, horizontal = 2.dp)) {
            items(AppIcons, key = { it.id }) { icon ->
                val on = icon.id == picked
                Column(
                    Modifier.clickable(role = Role.RadioButton, onClickLabel = icon.label) {
                        if (!on) { setAppIcon(ctx, icon.id); picked = icon.id }
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    val shape = RoundedCornerShape(16.dp)
                    Box(
                        Modifier.size(60.dp).clip(shape).background(icon.bg)
                            .border(2.dp, if (on) scheme.primary else scheme.outlineVariant.copy(alpha = 0.5f), shape),
                    ) { Image(painterResource(icon.art), null, Modifier.fillMaxSize().padding(2.dp)) }
                    Text(icon.label, style = MaterialTheme.typography.labelSmall, color = if (on) scheme.onSurface else scheme.onSurfaceVariant)
                }
            }
        }
    }
}
