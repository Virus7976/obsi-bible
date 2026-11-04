package app.obsibible.ui

import app.obsibible.obsidian.Template
import app.obsibible.obsidian.TemplateSync
import app.obsibible.obsidian.TemplateNote
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.layout.widthIn
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.StickyNote2
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Diamond
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withLink
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.ObsiBibleApp
import app.obsibible.bible.Ref
import app.obsibible.data.HighlightColors
import app.obsibible.data.Mark
import app.obsibible.data.Marks
import app.obsibible.obsidian.Clip
import app.obsibible.obsidian.NoteBuilder
import app.obsibible.obsidian.Sender
import app.obsibible.obsidian.NoteLink
import app.obsibible.obsidian.NoteLinks
import app.obsibible.obsidian.VaultTemplates
import androidx.compose.material.icons.outlined.Description
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.obsibible.obsidian.VaultIndex

// ---------- the bar under the page ----------

/**
 * The bar under the page while verses are picked or a highlight is open: colour dots on top,
 * then the reference and the actions.
 */
@Composable
fun MarkBar(
    label: String,
    hint: String?,
    color: Int?,
    onColor: (Int) -> Unit,
    onNoColor: (() -> Unit)?,
    actions: List<BarAction>,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", Modifier.size(24.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            HighlightColors.forEachIndexed { i, c -> ColorDot(Color(c.ui), c.name, i == color) { onColor(i) } }
            if (onNoColor != null) NoColorDot(onNoColor)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            actions.forEach { a -> BarButton(a) }
        }
    }
}

data class BarAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

@Composable
private fun ColorDot(c: Color, name: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = name, onClick = onClick)
            .semantics { contentDescription = name; selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(30.dp).clip(CircleShape).background(c)
                .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
        )
    }
}

@Composable
private fun NoColorDot(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClickLabel = "No colour", onClick = onClick).semantics { contentDescription = "No colour" },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(30.dp).clip(CircleShape).border(2.dp, scheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, null, Modifier.size(18.dp), tint = scheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BarButton(a: BarAction) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = a.onClick).padding(horizontal = 10.dp, vertical = 6.dp).width(56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(a.icon, null, Modifier.size(24.dp))
        Spacer(Modifier.height(3.dp))
        Text(a.label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

object BarIcons {
    val play = Icons.Rounded.PlayArrow
    val note = Icons.AutoMirrored.Outlined.StickyNote2
    val obsidian = Icons.Rounded.Diamond
    val copy = Icons.Outlined.ContentCopy
    val share = Icons.Outlined.Share
    val delete = Icons.Outlined.Delete
}

// ---------- the quote card used by the sheets and the feed ----------

/** The verse words with a colour bar on the left, like a quote in Obsidian. */
@Composable
fun QuoteCard(text: String, color: Int, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val scheme = MaterialTheme.colorScheme
    val bar = if (color >= 0) Color(HighlightColors[color.coerceIn(HighlightColors.indices)].ui) else scheme.onSurfaceVariant.copy(alpha = 0.5f)
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(bar))
        Spacer(Modifier.width(14.dp))
        Text(text, fontFamily = SerifFont, fontSize = 18.sp, lineHeight = 27.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}

// ---------- the note sheet ----------

/** Write or change the note on a highlight, and its tags. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NoteSheet(mark: Mark, onSave: (note: String, tags: List<String>) -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val scheme = MaterialTheme.colorScheme
    var field by remember(mark.id) { mutableStateOf(TextFieldValue(mark.note, TextRange(mark.note.length))) }
    val note = field.text
    var linking by remember { mutableStateOf(false) }
    fun insert(link: String) {
        val t = field.text
        var a = field.selection.min
        val b = field.selection.max
        // "[[" typed to start a link is replaced by the link
        if (a >= 2 && t.substring(a - 2, a) == "[[") a -= 2
        val pad = if (a > 0 && !t[a - 1].isWhitespace()) " " else ""
        val next = t.substring(0, a) + pad + link + t.substring(b)
        field = TextFieldValue(next, TextRange(a + pad.length + link.length))
    }
    val tags = remember(mark.id) { mutableStateListOf(*mark.tags.toTypedArray()) }
    var tagText by remember { mutableStateOf("") }
    val known = remember { app.marks.tags() }
    val focus = remember { FocusRequester() }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    fun addTag(t: String) {
        val clean = t.trim().removePrefix("#").replace(' ', '-')
        if (clean.isNotEmpty() && clean !in tags) tags += clean
    }
    fun save() { addTag(tagText); tagText = ""; onSave(note.trim(), tags.toList()) }
    // Saves while you type, so a stray swipe or a closed app never loses a word.
    LaunchedEffect(mark.id) {
        // An empty note is never saved here: saving it empty would delete it while it is still open.
        androidx.compose.runtime.snapshotFlow { field.text to tags.toList() }
            .collect { (t, tg) -> kotlinx.coroutines.delay(400); if (t.isNotBlank() || tg.isNotEmpty()) onSave(t.trim(), tg) }
    }
    // A swipe down does not close it: only Done, Back, or a tap above it. Each of those saves first.
    ModalBottomSheet(
        onDismissRequest = { save(); onDismiss() }, sheetState = state, sheetGesturesEnabled = false,
        containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface, dragHandle = { SheetHandle() },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(mark.label, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Button(onClick = { save(); onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface)) { Text("Done") }
            }
            Spacer(Modifier.height(12.dp))
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 18.dp)) {
                QuoteCard(mark.quote, mark.color, maxLines = 3)
                Spacer(Modifier.height(14.dp))
                TextField(
                    field, { v ->
                        val typedLink = v.text.length == field.text.length + 1 && v.selection.min >= 2 && v.text.substring(v.selection.min - 2, v.selection.min) == "[["
                        field = v
                        if (typedLink) linking = true
                    },
                    Modifier.fillMaxWidth().heightIn(min = 110.dp).focusRequester(focus),
                    placeholder = { Text("Write a note") },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp, lineHeight = 27.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(16.dp),
                )
                val links = remember(note) { NoteLinks.find(note) }
                if (links.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LinkChips(links)
                }
                Spacer(Modifier.height(16.dp))
                Text("Tags", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                TagEditor(tags, tagText) { tagText = it }
                Spacer(Modifier.height(16.dp))
                Text("Links", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.clip(CircleShape).background(scheme.surfaceContainerHighest).clickable { linking = true }.padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Link, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add link", fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Or type [[ in the note.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
    LaunchedEffect(Unit) { if (mark.note.isBlank()) runCatching { focus.requestFocus() } }
    if (linking) LinkPicker(exclude = mark.id, onPick = { insert(it); linking = false }, onDismiss = { linking = false })
}

// ---------- links inside notes ----------

/** Opens a link: a verse in the reader, or a note in Obsidian. */
@Composable
fun rememberLinkOpener(): (NoteLink) -> Unit {
    val app = LocalApp.current
    val context = LocalContext.current
    return remember {
        { link ->
            when (link) {
                is NoteLink.Verse -> app.openRef.value = link.ref.key
                is NoteLink.Note ->
                    if (!Sender.open(context, app.settings.value.vaultName, link.name)) Toast.makeText(context, "Obsidian is not on this phone.", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/** The links in a note as chips you can tap. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LinkChips(links: List<NoteLink>) {
    val open = rememberLinkOpener()
    val scheme = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        links.distinctBy { it.text }.forEach { l ->
            Row(
                Modifier.clip(CircleShape).border(1.5.dp, LinkColor, CircleShape).clickable { open(l) }.padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (l is NoteLink.Verse) Icons.AutoMirrored.Outlined.MenuBook else Icons.Rounded.Diamond, null, Modifier.size(16.dp), tint = LinkColor)
                Spacer(Modifier.width(6.dp))
                Text(l.text, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

val LinkColor = Color(0xFFA88BFA)

/** Note text with its links shown by name, in the link colour, and tappable. */
@Composable
fun LinkedText(text: String, modifier: Modifier = Modifier, fontSize: androidx.compose.ui.unit.TextUnit = 16.sp, maxLines: Int = Int.MAX_VALUE) {
    val open = rememberLinkOpener()
    val links = remember(text) { NoteLinks.find(text) }
    val shown = remember(text, links) {
        buildAnnotatedString {
            var i = 0
            for (l in links) {
                if (l.start < i) continue
                append(text.substring(i, l.start))
                withLink(LinkAnnotation.Clickable(l.text, TextLinkStyles(SpanStyle(color = LinkColor, fontWeight = FontWeight.SemiBold))) { open(l) }) { append(l.text) }
                i = l.end
            }
            append(text.substring(i))
        }
    }
    Text(shown, modifier, fontSize = fontSize, lineHeight = fontSize * 1.45f, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

private data class LinkChoice(val group: String, val title: String, val detail: String, val insert: String)

/** Pick what to link: a verse you type, one of your highlights, or a note in the vault. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPicker(exclude: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val context = LocalContext.current
    val s by app.settings.state.collectAsState()
    val scheme = MaterialTheme.colorScheme
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var q by remember { mutableStateOf("") }
    var vault by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(s.vaultTree) { vault = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { VaultIndex.notes(context, s.vaultTree) } }
    val marks = app.marks.all.value
    val choices = remember(q, vault, marks) {
        val out = ArrayList<LinkChoice>()
        val parsed = if (q.isNotBlank()) Ref.parse(q) else null
        if (parsed != null && (parsed.chapterGiven || q.any(Char::isDigit))) out += LinkChoice("Verse", parsed.ref.label, "Opens in Obsi Bible", NoteLinks.verse(parsed.ref))
        val mine = marks.filter { it.id != exclude && (it.hasColor || it.hasNote) }
        Fuzzy.filter(q, mine.sortedByDescending { it.updated }) { it.label + " " + it.quote + " " + it.note + " " + it.tags.joinToString(" ") }.take(if (q.isBlank()) 5 else 8)
            .forEach { m -> out += LinkChoice("Your highlights", m.label, m.note.ifBlank { m.quote }, NoteLinks.verse(m.ref)) }
        Fuzzy.filter(q, vault) { it }.take(if (q.isBlank()) 8 else 30).forEach { n -> out += LinkChoice("Vault notes", n, "Note in Obsidian", NoteLinks.note(n)) }
        if (q.isNotBlank() && parsed == null && vault.none { it.equals(q.trim(), true) })
            out += LinkChoice("Vault notes", q.trim(), "New note link", NoteLinks.note(q.trim()))
        out
    }
    val focus = remember { FocusRequester() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp).padding(bottom = 12.dp)) {
            Text("Link", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            TextField(
                q, { q = it }, Modifier.fillMaxWidth().focusRequester(focus), singleLine = true,
                placeholder = { Text("A verse, a highlight, or a vault note") },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerHighest,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                ),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { choices.firstOrNull()?.let { onPick(it.insert) } }),
            )
            if (s.vaultTree.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text("Pick your vault folder in Settings to link to vault notes.", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                var last = ""
                choices.forEach { c ->
                    if (c.group != last) {
                        last = c.group
                        item(key = "h" + c.group) { Text(c.group, Modifier.padding(top = 14.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant) }
                    }
                    item(key = c.group + c.insert) {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(c.insert) }.padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (c.group == "Vault notes") Icons.Rounded.Diamond else Icons.AutoMirrored.Outlined.MenuBook, null, Modifier.size(20.dp), tint = LinkColor)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(c.detail, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}

@Composable
fun TagChip(text: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.clip(CircleShape)
            .background(if (on) scheme.onSurface else scheme.surfaceContainerHighest)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (on) scheme.surface else scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------- sending to Obsidian ----------

/** Builds what goes to Obsidian for a mark. */
fun clipFor(app: ObsiBibleApp, m: Mark): Clip {
    val info = app.bibles.installed.value.firstOrNull { it.id == m.bible }
    val chapter = app.bibles.chapter(m.bible, Ref(m.book, m.chapter))
    val text = chapter?.let { Marks.quote(it.verses().toMap(), m) }?.ifBlank { null } ?: m.quote
    return Clip(
        ref = m.ref, translation = info?.abbreviation ?: m.bible, translationName = info?.name ?: m.bible,
        text = text, note = m.note, tags = m.tags,
        color = if (m.hasColor) HighlightColors[m.color.coerceIn(HighlightColors.indices)].name else "",
        copyright = info?.copyright.orEmpty(), at = m.created,
    )
}

/** Remembers the vault folder the user picks, with lasting read and write access. */
@Composable
fun rememberVaultPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val context = LocalContext.current
    val app = LocalApp.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            val name = Sender.folderName(context, uri.toString()).orEmpty()
            app.settings.update { s -> s.copy(vaultTree = uri.toString(), vaultName = s.vaultName.ifBlank { name }) }
            onPicked(uri)
        }
    }
    return { launcher.launch(null) }
}

/**
 * Send to Obsidian. Shows the note as it will look, where it goes, and the Markdown, which can
 * be changed before sending.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipSheet(clip: Clip, color: Int, onSent: () -> Unit, onDismiss: () -> Unit) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Tags and links added here go only into the note that is sent.
    val tags = remember(clip) { mutableStateListOf(*clip.tags.toTypedArray()) }
    val links = remember(clip) { mutableStateListOf<String>() }
    var tagText by remember { mutableStateOf("") }
    var linking by remember { mutableStateOf(false) }
    // The template: the default one, or one picked here (which becomes the default).
    var templateId by remember { mutableStateOf(s.selectedTemplateId) }
    val template = s.templates.firstOrNull { it.id == templateId } ?: s.templates.firstOrNull() ?: TemplateNote.VERSE
    var choosing by remember { mutableStateOf<List<VaultTemplates.Entry>?>(null) }
    // Answers for the properties marked "Ask me", by property id.
    val answers = remember(template.id) { mutableStateMapOf<String, String>() }
    val vars = remember(clip) { TemplateNote.variables(clip) }
    val tagList = tags.toList()
    val linkList = links.toList()
    val built = remember(clip, template, tagList, linkList, answers.toMap(), s.autoTags) {
        TemplateNote.build(template, clip.copy(tags = tagList, links = linkList), s, answers.toMap())
    }
    var markdown by remember(built) { mutableStateOf(built.markdown) }
    var showMarkdown by remember { mutableStateOf(false) }
    val pick = rememberVaultPicker { }
    val scope = rememberCoroutineScope()
    val folderName = remember(s.vaultTree) { if (s.vaultTree.isBlank()) null else Sender.folderName(context, s.vaultTree) }
    val adds = built.mode == "append" || built.mode == "prepend"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = scheme.surfaceContainer, contentColor = scheme.onSurface, dragHandle = { SheetHandle() }) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 20.dp)) {
            Text("Send to Obsidian", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            // the note, as it will look
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(scheme.surfaceContainerHighest).padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Diamond, null, Modifier.size(16.dp), tint = LinkColor)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        listOf(built.folder, built.name + ".md").filter { it.isNotBlank() }.joinToString("/"),
                        style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("${clip.ref.label} · ${clip.translation}", style = MaterialTheme.typography.titleMedium, color = LinkColor)
                Spacer(Modifier.height(8.dp))
                QuoteCard(clip.text, color, maxLines = 6)
                if (clip.note.isNotBlank()) {
                    Spacer(Modifier.height(12.dp))
                    LinkedText(clip.note, fontSize = 16.sp)
                }
                if (linkList.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LinkedText(linkList.joinToString(" · "), fontSize = 15.sp)
                }
                if (tagList.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(tagList.joinToString(" ") { "#$it" }, color = LinkColor, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // template
            Spacer(Modifier.height(18.dp))
            Text("Template", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                    scope.launch { choosing = withContext(Dispatchers.IO) { if (s.vaultTree.isBlank()) emptyList() else VaultTemplates.list(context, s.vaultTree) } }
                }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Description, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(template.name, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        when (built.mode) { "append" -> "Adds to ${built.name}"; "prepend" -> "Adds to the top of ${built.name}"; "overwrite" -> "Saves over ${built.name}"; else -> "Makes a new note" },
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                Text("Change", style = MaterialTheme.typography.labelLarge)
            }

            // the properties the template asks for
            val asks = template.properties.filter { it.ask }
            if (asks.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    asks.forEach { p ->
                        val start = remember(p.id) { Template.render(p.value, vars) }
                        TypedField(
                            p.name, p.type, answers[p.id] ?: start, { answers[p.id] = it },
                            vars = vars, vault = s.vaultTree.ifBlank { null }, insert = { f -> vars[f.key].orEmpty() },
                        )
                    }
                }
            }

            // links
            Spacer(Modifier.height(14.dp))
            Text("Links", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                linkList.forEach { l -> TagChip(NoteLinks.plain(l) + "  ×", on = true) { links.remove(l) } }
                Row(
                    Modifier.clip(CircleShape).background(scheme.surfaceContainerHighest).clickable { linking = true }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Link, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add link", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                }
            }

            // tags
            Spacer(Modifier.height(14.dp))
            Text("Tags", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            TagEditor(tags, tagText) { tagText = it }

            // where it goes
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = pick).padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Folder, null, Modifier.size(22.dp), tint = scheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (folderName != null) listOf(folderName, built.folder).filter { it.isNotBlank() }.joinToString(" / ") else "No vault folder picked",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (folderName != null) "The note is saved straight into this folder." else "Obsidian makes the note when you send it.",
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                    )
                }
                Text(if (folderName != null) "Change" else "Pick", style = MaterialTheme.typography.labelLarge)
            }

            Spacer(Modifier.height(6.dp))
            Text(
                if (showMarkdown) "Hide Markdown" else "Show Markdown",
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { showMarkdown = !showMarkdown }.padding(vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant,
            )
            AnimatedVisibility(showMarkdown) {
                TextField(
                    markdown, { markdown = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = scheme.surfaceContainerHighest, unfocusedContainerColor = scheme.surfaceContainerHighest,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                    ),
                    shape = RoundedCornerShape(14.dp),
                )
            }

            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = {
                    Sender.copy(context, markdown)
                    Toast.makeText(context, "Copied the note.", Toast.LENGTH_SHORT).show()
                }, Modifier.height(52.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = scheme.onSurface)) { Text("Copy") }
                Button(
                    onClick = {
                        // A tag still being typed counts. A Markdown edit made by hand wins.
                        val note = when {
                            markdown != built.markdown -> built.copy(markdown = markdown, body = markdown)
                            tagText.isNotBlank() -> TemplateNote.build(template, clip.copy(tags = tagList + tagText.trim().removePrefix("#"), links = linkList), s, answers.toMap())
                            else -> built
                        }
                        val r = if (s.vaultTree.isNotBlank()) Sender.saveFile(context, note, s.vaultTree) else Sender.viaLink(context, note, s.vaultName)
                        Toast.makeText(context, r.message, Toast.LENGTH_SHORT).show()
                        if (r.ok) onSent()
                    },
                    Modifier.weight(1f).height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.onSurface, contentColor = scheme.surface),
                ) { Text(if (adds) "Add" else "Send", fontSize = 16.sp) }
            }
        }
    }
    if (linking) LinkPicker(exclude = null, onPick = { if (it !in links) links += it; linking = false }, onDismiss = { linking = false })
    choosing?.let { vault ->
        // Your templates first, then notes from the vault's Templates folder not made into one yet.
        val made = s.templates.map { it.source }.toSet()
        QuickSwitcher(
            "Template",
            s.templates.map { Choice("t:" + it.id, it.name, if (it.source.isNotBlank()) "From your vault" else "In the app", "Your templates") } +
                vault.filter { it.path !in made }.map { Choice("v:" + it.uri, it.name, it.path.substringBeforeLast('/'), "Vault notes") },
            "t:" + template.id,
            onPick = { c ->
                choosing = null
                if (c.id.startsWith("t:")) {
                    templateId = c.id.removePrefix("t:")
                    app.settings.update { it.copy(selectedTemplateId = templateId) }
                } else {
                    val e = vault.first { it.uri == c.id.removePrefix("v:") }
                    scope.launch {
                        val id = withContext(Dispatchers.IO) { TemplateSync.use(app, e.uri, e.path) }
                        if (id != null) templateId = id else Toast.makeText(context, "Could not read that note.", Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onDismiss = { choosing = null },
            hint = "Find a template",
        )
    }
}

@Composable
private fun Segment(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier.clip(CircleShape).background(if (on) scheme.onSurface else Color.Transparent).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (on) scheme.surface else scheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
