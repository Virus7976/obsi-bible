package app.obsibible.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.obsibible.bible.Bibles
import app.obsibible.bible.Book
import app.obsibible.bible.Books
import app.obsibible.bible.Hit
import app.obsibible.bible.Found
import app.obsibible.obsidian.NoteLinks
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import app.obsibible.bible.Ref
import app.obsibible.bible.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * One box for everything: type a book ("eph"), a reference ("eph 3:20") or words ("love one another").
 * Books show as cards with their chapters; words search every verse on the phone, offline.
 */
@Composable
fun SearchScreen(onOpen: (Ref) -> Unit, onBack: (() -> Unit)?, autofocus: Boolean) {
    val app = LocalApp.current
    val s by app.settings.state.collectAsState()
    val bibleVersion by app.bibles.version.collectAsState()
    var field by remember { mutableStateOf(TextFieldValue("")) }
    val q = field.text
    var where by remember { mutableStateOf(Scope.All) }
    var results by remember { mutableStateOf(Found(0, emptyList())) }
    val allMarks by app.marks.all.collectAsState()
    val installed by app.bibles.installed.collectAsState()
    var searching by remember { mutableStateOf(false) }
    var openBook by remember { mutableStateOf<String?>(null) }
    val scheme = MaterialTheme.colorScheme
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val currentBook = Ref.fromKey(s.lastRef)?.book

    val parsed = remember(q) { if (q.isBlank()) null else Ref.parse(q) }
    val books = remember(q) {
        val letters = q.trim().replace(Regex("\\s*\\d+([:. ]\\d+)?(-\\d+)?\\s*$"), "")
        if (letters.isBlank()) emptyList() else Books.match(letters).take(4)
    }
    val wordsQuery = q.trim().length >= 2 && Bibles.matchQuery(q) != null && !(parsed?.chapterGiven == true && books.isNotEmpty())
    // your own highlights and notes; "#tag" finds by tag
    val mine = remember(q, allMarks) {
        val t = q.trim()
        when {
            t.length < 2 -> emptyList()
            t.startsWith("#") -> allMarks.filter { m -> m.tags.any { it.startsWith(t.drop(1), true) } }.sortedByDescending { it.updated }
            else -> Fuzzy.filter(t, allMarks.filter { it.hasColor || it.hasNote }) { NoteLinks.plain(it.note) + " " + it.quote + " " + it.tags.joinToString(" ") { "#$it" } + " " + it.label }
        }.take(6)
    }
    val recent = remember(q, s.recentSearches) {
        if (q.isBlank()) s.recentSearches else Fuzzy.filter(q, s.recentSearches) { it }.take(8)
    }

    LaunchedEffect(q, where, bibleVersion) {
        if (!wordsQuery) { results = Found(0, emptyList()); return@LaunchedEffect }
        delay(180)
        searching = true
        results = withContext(Dispatchers.IO) { app.bibles.search(s.bible, q, where, currentBook) }
        searching = false
    }
    LaunchedEffect(Unit) { if (autofocus) { delay(150); runCatching { focus.requestFocus() } } }

    fun remember(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        app.settings.update { st -> st.copy(recentSearches = (listOf(t) + st.recentSearches.filter { !it.equals(t, true) }).take(30)) }
    }

    fun open(ref: Ref, text: String = q) {
        remember(text.ifBlank { ref.label.lowercase() })
        keyboard?.hide()
        onOpen(ref)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", Modifier.size(28.dp)) }
            else Spacer(Modifier.size(20.dp))
            Box(Modifier.weight(1f).padding(start = 8.dp)) {
                if (q.isEmpty()) Text("Book, verse or words", color = scheme.onSurfaceVariant, fontSize = 20.sp)
                BasicTextField(
                    field, { field = it }, singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface, fontSize = 20.sp),
                    cursorBrush = SolidColor(scheme.onSurface),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        val p = parsed
                        if (p != null && (p.chapterGiven || books.size == 1)) open(p.ref) else { remember(q); keyboard?.hide() }
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (q.isNotEmpty()) IconButton(onClick = { field = TextFieldValue("") }) { Icon(Icons.Rounded.Close, "Clear", Modifier.size(28.dp)) }
            else Spacer(Modifier.size(48.dp))
        }
        HorizontalDivider(color = scheme.outlineVariant)

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            // a full reference: one card that opens it
            val p = parsed
            if (p != null && p.chapterGiven && books.isNotEmpty()) item(key = "goto") {
                val preview = remember(p.ref, bibleVersion) { if (p.ref.verse > 0) app.bibles.verseText(s.bible, p.ref) else null }
                Card(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp).clickable { open(p.ref) }) {
                    Text(p.ref.label, style = MaterialTheme.typography.titleLarge)
                    if (preview != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(preview, fontFamily = SerifFont, fontSize = 18.sp, lineHeight = 27.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    } else {
                        Spacer(Modifier.height(4.dp))
                        Text("Open chapter", color = scheme.onSurfaceVariant)
                    }
                }
            }
            // books as cards with their chapters
            items(books, key = { "book-${it.id}" }) { b ->
                BookCard(b, highlight = if (p?.chapterGiven == true && p.ref.book == b.id) p.ref.chapter else 0) { ch -> open(Ref(b.id, ch), "${b.name.lowercase()} $ch") }
            }
            // recent searches
            if (recent.isNotEmpty()) {
                if (q.isEmpty()) item(key = "recent-label") { SectionTitle("Recent") }
                else item(key = "recent-gap") { Spacer(Modifier.height(12.dp)) }
                items(recent, key = { "recent-$it" }) { r ->
                    RecentRow(r, onClick = {
                        val rp = asReference(r)
                        if (rp != null) open(rp, r) else field = TextFieldValue(r, TextRange(r.length))
                    }, onFill = { field = TextFieldValue(r, TextRange(r.length)) })
                }
            }
            // your highlights and notes
            if (mine.isNotEmpty()) {
                item(key = "mine-label") { SectionTitle("Your highlights and notes") }
                items(mine, key = { "mine-${it.id}" }) { m ->
                    val abbr = installed.firstOrNull { it.id == m.bible }?.abbreviation ?: m.bible
                    MarkRow(m, abbr, q) { app.openMark.value = m.id; open(m.ref) }
                }
            }
            // words in verses (hidden when a book matched and no verse has the words)
            if (wordsQuery && !q.trim().startsWith("#") && (books.isEmpty() || searching || results.hits.isNotEmpty())) {
                item(key = "scope") {
                    Column(Modifier.padding(top = 18.dp)) {
                        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Verses", style = MaterialTheme.typography.titleLarge)
                            Spacer(Modifier.size(10.dp))
                            Text(if (searching) "…" else results.total.toString(), color = scheme.onSurfaceVariant, fontSize = 16.sp)
                        }
                        val note = when {
                            searching -> null
                            results.fixed != null -> "Showing results for “${results.fixed}”."
                            results.partial -> "No verse has every word. These have most of them."
                            else -> null
                        }
                        if (note != null) Text(note, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = scheme.onSurfaceVariant, fontSize = 15.sp)
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Scope.entries.forEach { sc ->
                                val label = if (sc == Scope.Book) Books.get(currentBook ?: "")?.name ?: "This book" else sc.label
                                ScopeChip(label, sc == where) { where = sc }
                            }
                        }
                    }
                }
                if (!searching && results.hits.isEmpty()) item(key = "none") {
                    Text("No verses have these words.", Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = scheme.onSurfaceVariant)
                }
                items(results.hits, key = { "hit-${it.ref.key}" }) { h -> HitRow(h, results.fixed ?: q) { open(h.ref) } }
                if (results.total > results.hits.size) item(key = "more") {
                    Text("Showing the best ${results.hits.size}. Add a word to narrow it down.", Modifier.padding(20.dp), color = scheme.onSurfaceVariant, fontSize = 14.sp)
                }
            }
            // nothing typed: every book, to go to a chapter
            if (q.isBlank()) {
                item(key = "books-label") { SectionTitle("Books") }
                items(Books.all, key = { "all-${it.id}" }) { b ->
                    Column(Modifier.fillMaxWidth().animateContentSize()) {
                        Text(
                            b.name, fontSize = 18.sp,
                            modifier = Modifier.fillMaxWidth().clickable { openBook = if (openBook == b.id) null else b.id }.padding(horizontal = 24.dp, vertical = 14.dp),
                            fontWeight = if (openBook == b.id) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (openBook == b.id) ChapterGrid(b) { ch -> open(Ref(b.id, ch), "${b.name.lowercase()} $ch") }
                    }
                }
            }
        }
    }
}

/** A reference with a chapter ("eph 3", "john 3:16"), or null for plain words. */
fun asReference(text: String): Ref? {
    val p = Ref.parse(text) ?: return null
    if (!p.chapterGiven) return null
    val letters = text.trim().replace(Regex("\\s*\\d+([:. ]\\d+)?(-\\d+)?\\s*$"), "")
    return if (Books.match(letters).isNotEmpty()) p.ref else null
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, Modifier.padding(start = 20.dp, top = 22.dp, bottom = 6.dp), style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surfaceContainer).padding(20.dp)) { content() }
}

@Composable
private fun BookCard(b: Book, highlight: Int, onChapter: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp).fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceContainer).padding(vertical = 20.dp),
    ) {
        Text(b.name, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(16.dp))
        val scroll = rememberLazyListState(initialFirstVisibleItemIndex = (highlight - 2).coerceAtLeast(0))
        androidx.compose.foundation.lazy.LazyRow(state = scroll, contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items((1..b.chapters).toList()) { ch -> ChapterTile(ch, ch == highlight) { onChapter(ch) } }
        }
    }
}

@Composable
private fun ChapterGrid(b: Book, onChapter: (Int) -> Unit) {
    FlowRow(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (ch in 1..b.chapters) ChapterTile(ch, false) { onChapter(ch) }
    }
}

@Composable
private fun ChapterTile(n: Int, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.size(58.dp).clip(MaterialTheme.shapes.small).background(if (on) scheme.onSurface else scheme.surfaceContainerHighest).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(n.toString(), fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = if (on) scheme.surface else scheme.onSurface) }
}

@Composable
private fun RecentRow(text: String, onClick: () -> Unit, onFill: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Search, null, Modifier.size(24.dp))
        }
        Text(text, Modifier.weight(1f).padding(start = 16.dp), fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = onFill) { Icon(Icons.Rounded.NorthWest, "Put in the search box", Modifier.size(24.dp)) }
    }
}

@Composable
private fun ScopeChip(label: String, on: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.clip(CircleShape).background(if (on) scheme.onSurface else scheme.surfaceContainer)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
    ) { Text(label, color = if (on) scheme.surface else scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold) }
}

/** One of your highlights or notes in the search results. */
@Composable
private fun MarkRow(m: app.obsibible.data.Mark, abbr: String, q: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val bar = if (m.hasColor) androidx.compose.ui.graphics.Color(app.obsibible.data.HighlightColors[m.color.coerceIn(app.obsibible.data.HighlightColors.indices)].ui) else scheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
        Box(Modifier.width(4.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(bar))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("${m.label} $abbr", style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Text(boldMatches(m.quote, q), fontFamily = SerifFont, fontSize = 17.sp, lineHeight = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (m.hasNote) Text(boldMatches(NoteLinks.plain(m.note), q), fontSize = 15.sp, lineHeight = 21.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (m.tags.isNotEmpty()) Text(m.tags.joinToString("  ") { "#$it" }, color = scheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun HitRow(h: Hit, q: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(h.ref.label, style = MaterialTheme.typography.titleSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(boldMatches(h.text, q), fontFamily = SerifFont, fontSize = 18.sp, lineHeight = 27.sp)
    }
}

/** Bolds the words in [text] that start with a searched word. */
fun boldMatches(text: String, q: String): AnnotatedString {
    val words = Bibles.normalize(q.replace("\"", "")).split(' ').filter { it.length >= 2 }
    if (words.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var last = 0
        for (m in Regex("[\\p{L}\\p{N}]+").findAll(text)) {
            val w = m.value.lowercase()
            if (words.any { w.startsWith(it) }) {
                append(text.substring(last, m.range.first))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(m.value) }
                last = m.range.last + 1
            }
        }
        append(text.substring(last))
    }
}
