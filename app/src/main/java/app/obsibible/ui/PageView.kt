package app.obsibible.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.LineBackgroundSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.widget.NestedScrollView
import app.obsibible.R
import app.obsibible.bible.Chapter
import app.obsibible.bible.Seg
import app.obsibible.data.HighlightColors
import app.obsibible.data.Mark
import app.obsibible.data.Marks
import kotlin.math.abs
import kotlin.math.roundToInt

/** Colours the page needs, taken from the theme. */
data class PageColors(val text: Int, val muted: Int, val faint: Int, val mark: Int, val markStrong: Int, val underline: Int)

/**
 * The chapter page: one TextView inside a scroll view. One TextView keeps the text selectable
 * later for highlights, and lets us draw the big chapter number and the follow-along marks.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class PageView(context: Context) : NestedScrollView(context) {
    val text = PageText(context)
    private var verseStarts: List<Pair<Int, Int>> = emptyList() // verse -> start offset, in order
    private var verseEnds: Map<Int, Int> = emptyMap()
    private var follow: MarkSpan? = null
    private var word: MarkSpan? = null
    private var selected = HashMap<Int, MarkSpan>()
    private var colors = PageColors(0, 0, 0, 0, 0, 0)
    private var lastTouch = 0L
    private var shownKey = ""
    private var plain: Map<Int, String> = emptyMap()
    private val maps = HashMap<Int, IntArray>()
    private val markSpans = ArrayList<MarkSpan>()
    private var marks: List<Mark> = emptyList()
    private var openId: String? = null
    private var marksKey: Any? = null

    /** A highlight was tapped. */
    var onMarkTap: (String) -> Unit = {}
    /** A note icon in the margin was tapped. */
    var onNoteIconTap: (String) -> Unit = {}
    /** The ends of the open highlight were dragged: new start verse and letter, end verse and letter. */
    var onResize: (id: String, v1: Int, c1: Int, v2: Int, c2: Int) -> Unit = { _, _, _, _, _ -> }

    var onVerseTap: (Int) -> Unit = {}
    var onNoteTap: (String) -> Unit = {}
    /** The copyright line shown small at the end of the chapter. Set it before show(). */
    var credit: String = ""
    var onCreditTap: () -> Unit = {}
    /** True when the reader scrolled away from the verse being read. The page stops following until resync(). */
    var away = false
        private set
    var onAway: (Boolean) -> Unit = {}
    /** True while the reader scrolls down into the text, false when scrolling back up or at the top. */
    var onReading: (Boolean) -> Unit = {}
    var onScrolled: () -> Unit = {}

    private val serif: Typeface = ResourcesCompat.getFont(context, R.font.literata) ?: Typeface.SERIF
    private val serifItalic: Typeface = ResourcesCompat.getFont(context, R.font.literata_italic) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        val frame = FrameLayout(context)
        text.typeface = serif
        text.includeFontPadding = false
        frame.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnScrollChangeListener { _, _, _, _, _ -> onScrolled() }
    }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** True when the reader touched the page in the last few seconds. Auto-scroll waits then. */
    val recentlyTouched: Boolean get() = System.currentTimeMillis() - lastTouch < 4000

    fun show(chapter: Chapter, key: String, sizeSp: Float, lineHeight: Float, c: PageColors, padH: Int, padTop: Int, padBottom: Int) {
        val sameText = key == shownKey
        colors = c
        text.setTextColor(c.text)
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        text.setLineSpacing(0f, lineHeight)
        text.setPadding(padH, padTop, padH, padBottom)
        if (sameText) return
        // Same chapter with a new size or colour: keep the reader's place.
        val samePlace = shownKey.split('/').take(2) == key.split('/').take(2)
        val keepVerse = if (samePlace) topVerse() else 0
        shownKey = key
        val px = text.textSize
        val built = build(chapter, px)
        verseStarts = built.starts
        verseEnds = built.ends
        follow = null; word = null; selected.clear(); followKey = null
        if (away) { away = false; onAway(false) }
        plain = chapter.verses().toMap()
        groups = chapter.groups()
        maps.clear(); markSpans.clear(); marksKey = null
        text.setPins(-1, -1, 0); text.notes = emptyList()
        text.setText(built.text, TextView.BufferType.SPANNABLE)
        if (keepVerse > 1) scrollToVerse(keepVerse, false) else scrollTo(0, 0)
    }

    private class Built(val text: CharSequence, val starts: List<Pair<Int, Int>>, val ends: Map<Int, Int>)

    private fun build(ch: Chapter, px: Float): Built {
        val sb = SpannableStringBuilder()
        val starts = ArrayList<Pair<Int, Int>>()
        val ends = HashMap<Int, Int>()
        var verse = 0
        // Narrow indents: a phone line is short, and wide ones push single words onto lines of their own.
        val indent = (px * 0.9f).roundToInt()
        var firstBody = true
        var dropCapPending: DropCap? = null
        var dropCapSecond = false
        var noteNo = 0
        var lastKind = ""

        fun closeVerse() { if (verse > 0) ends[verse] = sb.length }

        fun newParagraph() {
            if (sb.isNotEmpty() && sb[sb.length - 1] != '\n') sb.append('\n')
        }

        // A short blank line: a newline drawn at [size] of the text height.
        fun gap(size: Float) {
            newParagraph()
            if (sb.isEmpty()) return
            val s = sb.length
            sb.append('\n')
            sb.setSpan(RelativeSizeSpan(size), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        for (b in ch.blocks) {
            when (b.kind) {
                "r" -> continue
                "b" -> {
                    // a blank line right after a heading adds nothing
                    if (lastKind.startsWith("s") || lastKind == "d" || lastKind == "ms") continue
                    gap(0.35f); lastKind = "b"; continue
                }
            }
            if (b.isHeading) {
                val text = b.segs.filter { it.t == Seg.TEXT }.joinToString("") { it.s }.trim()
                if (text.isEmpty()) continue
                if (sb.isNotEmpty()) gap(if (b.kind == "d") 0.4f else 0.9f)
                newParagraph()
                val s = sb.length
                sb.append(text)
                when (b.kind) {
                    "d" -> {
                        sb.setSpan(TypefaceSpan2(serifItalic, 400), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(RelativeSizeSpan(0.88f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colors.muted), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    "ms", "mr" -> {
                        sb.setSpan(TypefaceSpan2(serif, 600), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(ForegroundColorSpan(colors.muted), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                    else -> {
                        sb.setSpan(TypefaceSpan2(serifItalic, 700), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.setSpan(RelativeSizeSpan(if (b.kind == "s1") 1.02f else 0.95f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    }
                }
                sb.append('\n')
                gap(0.5f)
                lastKind = b.kind
                continue
            }

            // body text
            val poetry = b.kind.startsWith("q") || b.kind.startsWith("li")
            val wasPoetry = lastKind.startsWith("q") || lastKind.startsWith("li")
            if (sb.isNotEmpty() && !poetry && lastKind != "b" && !lastKind.let { it.startsWith("s") || it == "d" || it == "ms" }) gap(0.45f)
            else if (poetry && !wasPoetry && sb.isNotEmpty() && lastKind != "b" && !lastKind.let { it.startsWith("s") || it == "d" }) gap(0.45f)
            newParagraph()
            val pStart = sb.length
            for (seg in b.segs) when (seg.t) {
                Seg.VERSE -> {
                    closeVerse()
                    verse = seg.s.substringBefore('-').toIntOrNull() ?: verse
                    if (firstBody && starts.isEmpty()) {
                        // verse 1 has no number: the chapter number stands in for it
                        starts += verse to sb.length
                    } else {
                        if (sb.isNotEmpty() && sb[sb.length - 1] != '\n' && sb[sb.length - 1] != ' ') sb.append(' ')
                        val s = sb.length
                        sb.append(seg.s).append(' ')
                        sb.setSpan(VerseNumber(colors.muted), s, sb.length - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        starts += verse to sb.length
                    }
                }
                Seg.TEXT -> {
                    var t = seg.s
                    if (sb.isEmpty() || sb[sb.length - 1] == '\n' || sb[sb.length - 1] == ' ' || sb[sb.length - 1] == ' ') t = t.trimStart()
                    sb.append(t)
                }
                Seg.NOTE -> {
                    while (sb.isNotEmpty() && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
                    noteNo++
                    val s = sb.length
                    sb.append(('a' + (noteNo - 1) % 26).toString())
                    sb.setSpan(VerseNumber(colors.faint), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(NoteSpan(seg.s), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            // trim the trailing space of the paragraph
            while (sb.length > pStart && sb[sb.length - 1] == ' ') sb.delete(sb.length - 1, sb.length)
            val pEnd = sb.length
            if (pEnd > pStart) {
                if (firstBody) {
                    val cap = DropCap(ch.number.toString(), serif, px, colors.muted)
                    sb.setSpan(cap, pStart, pEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    dropCapPending = cap
                    dropCapSecond = poetry
                    firstBody = false
                } else if (dropCapSecond) {
                    // a one-line poem opening: the next line also steps around the number
                    sb.setSpan(LeadingMarginSpan.Standard(dropCapPending!!.width, 0), pStart, pEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    dropCapSecond = false
                }
                val margin: LeadingMarginSpan? = when (b.kind) {
                    "q1" -> LeadingMarginSpan.Standard(0, indent * 2)
                    "q2" -> LeadingMarginSpan.Standard(indent, indent * 2)
                    "li1" -> LeadingMarginSpan.Standard(indent / 2, indent)
                    "li2" -> LeadingMarginSpan.Standard(indent, indent * 3 / 2)
                    "pmo" -> LeadingMarginSpan.Standard(indent / 2, indent / 2)
                    else -> null
                }
                margin?.let { sb.setSpan(it, pStart, pEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                when (b.kind) {
                    "pc" -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), pStart, pEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    "qr" -> sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_OPPOSITE), pStart, pEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            lastKind = b.kind
        }
        closeVerse()
        if (credit.isNotBlank()) {
            sb.append("\n\n")
            val s = sb.length
            sb.append(credit)
            sb.setSpan(RelativeSizeSpan(0.6f), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(ForegroundColorSpan(colors.muted), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(CreditSpan(), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return Built(sb, starts, ends)
    }

    // ---------- marks ----------

    private var groups: Map<Int, IntRange> = emptyMap()

    /** The verse number printed on the page for [verse]: itself, or the first verse of its group. */
    private fun head(verse: Int) = if (verseEnds.containsKey(verse)) verse else groups.entries.firstOrNull { verse in it.value }?.key ?: verse

    /** All verses printed together with [verse], such as 1..4 for "1-4". */
    fun group(verse: Int): IntRange = groups[head(verse)] ?: verse..verse

    private fun range(verse: Int): IntRange? {
        val h = head(verse)
        val s = verseStarts.firstOrNull { it.first == h }?.second ?: return null
        val e = verseEnds[h] ?: return null
        val t = text.text
        var a = s
        var b = e
        while (a < b && t[a].isWhitespace()) a++
        while (b > a && t[b - 1].isWhitespace()) b--
        return a until b
    }

    /** Marks the verse being read aloud, and the word inside it. [wordRange] is relative to the verse's plain text. */
    private var followKey: Pair<Int, IntRange?>? = null

    fun setFollow(verse: Int, wordRange: IntRange?, scroll: Boolean) {
        if (followKey == verse to wordRange) return
        followKey = verse to wordRange
        val sp = text.text as? Spannable ?: return
        follow?.let { sp.removeSpan(it) }
        word?.let { sp.removeSpan(it) }
        follow = null; word = null
        if (verse <= 0) { text.invalidate(); return }
        val r = range(verse) ?: return
        follow = MarkSpan(text, r.first, r.last + 1, colors.mark, MarkSpan.FILL).also { sp.setSpan(it, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        if (wordRange != null) {
            val ws = locateWord(r, wordRange)
            if (ws != null) word = MarkSpan(text, ws.first, ws.last + 1, colors.markStrong, MarkSpan.FILL).also { sp.setSpan(it, ws.first, ws.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        text.invalidate()
        if (scroll && !away && !recentlyTouched) keepInView(word?.let { it.start } ?: r.first)
    }

    /**
     * The verse on the page has verse numbers and note letters inside it; the spoken text does not.
     * Walk both in step, skipping page characters that are not in the plain text.
     */
    private fun locateWord(verse: IntRange, w: IntRange): IntRange? {
        val page = text.text
        val sp = page as? Spanned ?: return null
        var plain = 0
        var i = verse.first
        var startAt = -1
        var lastWasSpace = true
        while (i <= verse.last) {
            val inMarker = sp.getSpans(i, i + 1, VerseNumber::class.java).isNotEmpty()
            val ch = page[i]
            if (!inMarker && ch != ' ') {
                val isSpace = ch.isWhitespace()
                if (!(isSpace && lastWasSpace)) {
                    if (plain == w.first && startAt < 0) startAt = i
                    plain++
                    if (plain >= w.last + 1 && startAt >= 0) return startAt..i
                }
                lastWasSpace = isSpace
            }
            i++
        }
        return if (startAt >= 0) startAt..verse.last else null
    }

    fun setSelected(verses: Set<Int>) {
        val sp = text.text as? Spannable ?: return
        for ((v, s) in selected.entries.toList()) if (v !in verses) { sp.removeSpan(s); selected.remove(v) }
        for (v in verses) if (v !in selected) {
            if (head(v) != v && head(v) in verses) continue // one line under a group of verses, not four
            val r = range(v) ?: continue
            val s = MarkSpan(text, r.first, r.last + 1, colors.underline, MarkSpan.DOTS)
            sp.setSpan(s, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            selected[v] = s
        }
        text.invalidate()
    }

    // ---------- saved highlights ----------

    private fun mapOf(verse: Int): IntArray? {
        maps[verse]?.let { return it }
        val p = plain[verse] ?: return null
        val r = range(verse) ?: return null
        val sp = text.text as? Spanned ?: return null
        val m = Marks.align(sp, r.first, r.last + 1, { i -> sp.getSpans(i, i + 1, VerseNumber::class.java).isNotEmpty() }, p)
        maps[verse] = m
        return m
    }

    /** Where a mark sits on the page, as start until end. */
    private fun pageRange(m: Mark): IntRange? {
        val r1 = range(m.v1) ?: return null
        val r2 = range(m.v2) ?: return null
        val len1 = plain[m.v1]?.length ?: 0
        val len2 = plain[m.v2]?.length ?: 0
        val s = if (m.c1 <= 0 || len1 == 0) r1.first else mapOf(m.v1)?.getOrNull(m.c1.coerceAtMost(len1 - 1)) ?: r1.first
        val e = if (m.c2 < 0 || m.c2 >= len2 || len2 == 0) r2.last + 1 else (mapOf(m.v2)?.getOrNull((m.c2 - 1).coerceAtLeast(0))?.plus(1) ?: (r2.last + 1))
        return if (e > s) s until e else null
    }

    /** Page offset to (verse, letter). [end] is true for the end of a range (one past the last letter). */
    private fun toPlain(off: Int, end: Boolean): Pair<Int, Int>? {
        if (verseStarts.isEmpty()) return null
        var v = verseStarts.lastOrNull { it.second <= off }?.first ?: verseStarts.first().first
        var r = range(v)
        if (r != null && !end && off > r.last) {
            // between verses (a verse number or a heading): the start moves to the next verse
            val next = verseStarts.firstOrNull { it.second > off }?.first
            if (next != null) { v = next; r = range(v) }
            return v to 0
        }
        val len = plain[v]?.length ?: return v to 0
        val map = mapOf(v) ?: return v to 0
        if (r != null && off > r.last) return v to len
        if (r != null && off <= r.first) return v to 0
        return if (end) v to (Marks.plainIndex(map, off - 1) + 1).coerceIn(1, len)
        else v to Marks.plainIndex(map, off).let { if (map[it] < off && it + 1 < len) it + 1 else it }
    }

    private fun fillColor(color: Int): Int {
        val base = HighlightColors[color.coerceIn(HighlightColors.indices)].ui.toInt()
        // A dark page needs a softer fill so white text stays easy to read.
        val lightPage = android.graphics.Color.luminance(colors.text) < 0.5f
        val a = if (lightPage) 0x70 else 0x5C
        return (base and 0x00FFFFFF) or (a shl 24)
    }

    /** Draws the saved marks for this chapter. [open] gets pull handles. */
    fun setMarks(list: List<Mark>, open: String?) {
        if (dragging != 0) return
        val key = Triple(list, open, shownKey)
        if (key == marksKey) return
        marksKey = key
        marks = list
        openId = open
        drawMarks()
    }

    private fun drawMarks(live: IntRange? = null) {
        val sp = text.text as? Spannable ?: return
        markSpans.forEach { sp.removeSpan(it) }
        markSpans.clear()
        val notes = ArrayList<Pair<Int, String>>()
        var pin: IntRange? = null
        var pinColor = 0
        for (m in marks.sortedBy { it.created }) {
            val r = (if (m.id == openId && live != null) live else pageRange(m)) ?: continue
            if (m.hasColor) {
                val span = MarkSpan(text, r.first, r.last + 1, fillColor(m.color), MarkSpan.FILL)
                sp.setSpan(span, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                markSpans += span
            } else if (m.id == openId) {
                val span = MarkSpan(text, r.first, r.last + 1, colors.underline, MarkSpan.DOTS)
                sp.setSpan(span, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                markSpans += span
            }
            if (m.hasNote) notes += r.first to m.id
            if (m.id == openId) {
                pin = r
                pinColor = if (m.hasColor) HighlightColors[m.color.coerceIn(HighlightColors.indices)].ui.toInt() else colors.text
            }
        }
        text.notes = notes
        text.noteColor = colors.muted
        if (pin != null) text.setPins(pin.first, pin.last + 1, pinColor) else text.setPins(-1, -1, 0)
        text.invalidate()
    }

    /** Scrolls so a highlight is on screen. */
    fun revealMark(id: String) {
        val m = marks.firstOrNull { it.id == id } ?: return
        val r = pageRange(m) ?: return
        post { keepInView(r.first) }
    }

    // ---------- pull handles ----------

    private var dragging = 0
    private var dragRange: IntRange? = null
    private var grabDx = 0f
    private var grabDy = 0f
    private var fingerX = 0f
    private var fingerY = 0f
    private var magnifier: android.widget.Magnifier? = null
    private var edgeSpeed = 0

    private fun pinAt(x: Float, y: Float): Int {
        val (a, b) = text.pins() ?: return 0
        val px = x + scrollX - text.left
        val py = y + scrollY - text.top
        val reach = dp(30f)
        fun dist(p: PageText.Pin): Float {
            val dy = when {
                py < minOf(p.knobY, p.top) -> minOf(p.knobY, p.top) - py
                py > maxOf(p.knobY, p.bottom) -> py - maxOf(p.knobY, p.bottom)
                else -> 0f
            }
            return kotlin.math.hypot(px - p.x, dy)
        }
        val da = dist(a)
        val db = dist(b)
        return when {
            da > reach && db > reach -> 0
            da <= db -> -1
            else -> 1
        }
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var downScroll = 0

    // Taps are found here, above the scroll view, so they work however the touch is handled below.
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downAt = System.currentTimeMillis(); downScroll = scrollY; lastTouch = downAt }
            MotionEvent.ACTION_MOVE -> lastTouch = System.currentTimeMillis()
            MotionEvent.ACTION_UP -> {
                lastTouch = System.currentTimeMillis()
                val still = abs(ev.x - downX) < slop && abs(ev.y - downY) < slop && abs(scrollY - downScroll) < slop
                if (still && dragging == 0 && lastTouch - downAt < 500) {
                    val handled = super.dispatchTouchEvent(ev)
                    tap(ev.x + scrollX - text.left, ev.y + scrollY - text.top)
                    return handled
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val which = pinAt(ev.x, ev.y)
            if (which != 0) { beginDrag(which, ev); return true }
        }
        return dragging != 0 || super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (dragging == 0) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { fingerX = ev.x; fingerY = ev.y; dragStep() }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endDrag()
        }
        return true
    }

    private fun beginDrag(which: Int, ev: MotionEvent) {
        val (a, b) = text.pins() ?: return
        val p = if (which < 0) a else b
        dragging = which
        dragRange = text.pinFrom until text.pinTo
        fingerX = ev.x
        fingerY = ev.y
        grabDx = p.x - (ev.x + scrollX - text.left)
        grabDy = (p.top + p.bottom) / 2f - (ev.y + scrollY - text.top)
        parent?.requestDisallowInterceptTouchEvent(true)
        lastTouch = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 28) magnifier = android.widget.Magnifier(text)
        text.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
    }

    private fun isWord(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    private fun skipped(i: Int): Boolean {
        val sp = text.text as? Spanned ?: return false
        return sp.getSpans(i, i + 1, VerseNumber::class.java).isNotEmpty()
    }

    /** Moves the held end to the word under the finger. The ends never cross, so one word always stays. */
    private fun dragStep() {
        val l = text.layout ?: return
        val r = dragRange ?: return
        val t = text.text
        val tx = fingerX + scrollX - text.left + grabDx
        val ty = fingerY + scrollY - text.top + grabDy
        val line = l.getLineForVertical((ty - text.totalPaddingTop).toInt().coerceIn(0, l.height - 1))
        var off = l.getOffsetForHorizontal(line, tx - text.totalPaddingLeft).coerceIn(0, t.length)
        val next: IntRange
        if (dragging < 0) {
            while (off < t.length && (!isWord(t[off]) || skipped(off))) off++
            while (off > 0 && isWord(t[off - 1]) && !skipped(off - 1)) off--
            next = off.coerceAtMost(lastWordStart(r.last + 1))..r.last
        } else {
            while (off > 0 && (!isWord(t[off - 1]) || skipped(off - 1))) off--
            while (off < t.length && isWord(t[off]) && !skipped(off)) off++
            var e = off.coerceAtLeast(firstWordEnd(r.first))
            // punctuation right after the word goes with it
            while (e < t.length && t[e] in PUNCT && !skipped(e)) e++
            next = r.first until e
        }
        if (next != r && !next.isEmpty()) {
            dragRange = next
            text.performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 27) android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE else android.view.HapticFeedbackConstants.CLOCK_TICK,
            )
            drawMarks(next)
        }
        text.pins()?.let { (a, b) ->
            val p = if (dragging < 0) a else b
            if (Build.VERSION.SDK_INT >= 28) magnifier?.show(p.x, (p.top + p.bottom) / 2f)
        }
        val edge = dp(56f)
        val speed = when {
            fingerY < edge -> -((edge - fingerY) / edge * dp(18f)).toInt().coerceAtLeast(1)
            fingerY > height - edge -> ((fingerY - (height - edge)) / edge * dp(18f)).toInt().coerceAtLeast(1)
            else -> 0
        }
        removeCallbacks(edgeScroll)
        if (speed != 0) { edgeSpeed = speed; postOnAnimation(edgeScroll) }
    }

    private fun lastWordStart(end: Int): Int {
        val t = text.text
        var i = end
        while (i > 0 && (!isWord(t[i - 1]) || skipped(i - 1))) i--
        while (i > 0 && isWord(t[i - 1]) && !skipped(i - 1)) i--
        return i
    }

    private fun firstWordEnd(start: Int): Int {
        val t = text.text
        var i = start
        while (i < t.length && (!isWord(t[i]) || skipped(i))) i++
        while (i < t.length && isWord(t[i]) && !skipped(i)) i++
        return i
    }

    private val edgeScroll = Runnable {
        if (dragging != 0) {
            scrollBy(0, edgeSpeed)
            dragStep()
        }
    }

    private fun endDrag() {
        removeCallbacks(edgeScroll)
        if (Build.VERSION.SDK_INT >= 28) magnifier?.dismiss()
        magnifier = null
        val r = dragRange
        val id = openId
        dragging = 0
        dragRange = null
        lastTouch = System.currentTimeMillis()
        if (r == null || id == null) return
        val a = toPlain(r.first, false) ?: return
        val b = toPlain(r.last + 1, true) ?: return
        if (b.first < a.first || (b.first == a.first && b.second <= a.second)) { drawMarks(); return }
        onResize(id, a.first, a.second, b.first, b.second)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        val touching = System.currentTimeMillis() - lastTouch < 1500
        when {
            t < 24 -> onReading(false)
            touching && t - oldt > 6 -> onReading(true)
            touching && oldt - t > 6 -> onReading(false)
        }
        // Only a finger moves the page "away". The page's own follow scroll does not count.
        if (!away && follow != null && dragging == 0 && System.currentTimeMillis() - lastTouch < 1500 && t != oldt) {
            away = true
            onAway(true)
        }
    }

    /** True when the verse being read is on the screen. */
    fun followVisible(): Boolean {
        val f = follow ?: return true
        val layout = text.layout ?: return true
        val line = layout.getLineForOffset(word?.start ?: f.start)
        val top = text.top + text.paddingTop + layout.getLineTop(line)
        val bottom = text.top + text.paddingTop + layout.getLineBottom(line)
        return bottom > scrollY && top < scrollY + height
    }

    /** Goes back to the verse being read, and follows it again. */
    fun resync() {
        away = false
        onAway(false)
        val f = follow ?: return
        val layout = text.layout ?: return
        val top = text.paddingTop + layout.getLineTop(layout.getLineForOffset(word?.start ?: f.start))
        smoothScrollTo(0, (top - height / 4).coerceAtLeast(0))
    }

    /** Scrolls so [verse] sits a third of the way down. */
    fun scrollToVerse(verse: Int, smooth: Boolean) {
        val s = verseStarts.firstOrNull { it.first == verse }?.second ?: return
        post {
            val layout = text.layout ?: return@post
            val y = text.top + text.paddingTop + layout.getLineTop(layout.getLineForOffset(s)) - height / 4
            if (smooth) smoothScrollTo(0, y.coerceAtLeast(0)) else scrollTo(0, y.coerceAtLeast(0))
        }
    }

    private fun keepInView(offset: Int) {
        val layout = text.layout ?: return
        val line = layout.getLineForOffset(offset)
        val top = text.paddingTop + layout.getLineTop(line)
        val bottom = text.paddingTop + layout.getLineBottom(line)
        if (top < scrollY + height * 0.12f || bottom > scrollY + height * 0.62f) {
            smoothScrollTo(0, (top - height / 4).coerceAtLeast(0))
        }
    }

    /** The first verse at the top of the screen, for "play from here". */
    fun topVerse(): Int {
        val layout = text.layout ?: return 0
        val line = layout.getLineForVertical((scrollY - text.paddingTop).coerceAtLeast(0))
        val off = layout.getLineStart(line)
        return verseStarts.lastOrNull { it.second <= off }?.first ?: verseStarts.firstOrNull()?.first ?: 0
    }

    private fun tap(x: Float, y: Float) {
        val layout = text.layout ?: return
        val ly = (y - text.paddingTop).toInt()
        if (ly < 0 || ly > layout.height) return
        val line = layout.getLineForVertical(ly)
        val off = layout.getOffsetForHorizontal(line, x - text.paddingLeft)
        val sp = text.text as? Spanned ?: return
        text.noteAt(x, y)?.let { onNoteIconTap(it); return }
        sp.getSpans((off - 1).coerceAtLeast(0), off + 1, NoteSpan::class.java).firstOrNull()?.let { onNoteTap(it.note); return }
        if (sp.getSpans(off, off, CreditSpan::class.java).isNotEmpty()) { onCreditTap(); return }
        if (x >= text.paddingLeft && x <= text.width - text.paddingRight) {
            val hit = marks.filter { it.hasColor || it.id == openId }.sortedByDescending { it.created }
                .firstOrNull { m -> pageRange(m)?.let { off in it } == true }
            if (hit != null) { onMarkTap(hit.id); return }
        }
        val v = verseStarts.lastOrNull { it.second <= off + 1 }?.first ?: return
        val end = verseEnds[v] ?: return
        if (off <= end + 1) onVerseTap(v)
    }
}

private const val PUNCT = ".,;:!?\u201D\u2019)\""

/**
 * The page text. On top of the text it draws the pull handles of the open highlight and a note
 * icon in the right margin beside each verse with a note.
 */
class PageText(context: Context) : TextView(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dp = resources.displayMetrics.density
    var pinFrom = -1
        private set
    var pinTo = -1
        private set
    private var pinColor = 0

    /** (page offset, mark id) for each mark with a note. */
    var notes: List<Pair<Int, String>> = emptyList()
    var noteColor = 0

    fun setPins(from: Int, to: Int, color: Int) {
        if (from == pinFrom && to == pinTo && color == pinColor) return
        pinFrom = from; pinTo = to; pinColor = color
        invalidate()
    }

    class Pin(val x: Float, val top: Float, val bottom: Float, val knobY: Float)

    /** The start knob sits above the text and the end knob below, so neither covers a word. */
    fun pins(): Pair<Pin, Pin>? {
        val l = layout ?: return null
        if (pinFrom < 0 || pinTo <= pinFrom || pinTo > text.length) return null
        val size = textSize
        val left = totalPaddingLeft
        val top = totalPaddingTop
        val r = KNOB * dp
        val l0 = l.getLineForOffset(pinFrom)
        val b0 = l.getLineBaseline(l0).toFloat()
        val x0 = l.getPrimaryHorizontal(pinFrom) - 2 * dp + left
        val t0 = b0 - size * 0.84f + top
        val start = Pin(x0, t0, b0 + size * 0.3f + top, t0 - r)
        val l1 = l.getLineForOffset(pinTo - 1)
        val b1 = l.getLineBaseline(l1).toFloat()
        val x1 = (if (pinTo >= l.getLineVisibleEnd(l1)) l.getLineRight(l1) else l.getPrimaryHorizontal(pinTo)) + 2 * dp + left
        val e1 = b1 + size * 0.3f + top
        return start to Pin(x1, b1 - size * 0.84f + top, e1, e1 + r)
    }

    private fun drawPin(c: Canvas, p: Pin, knobAbove: Boolean) {
        val r = KNOB * dp
        ink.style = Paint.Style.FILL
        ink.color = 0xCC000000.toInt()
        c.drawRect(p.x - 2f * dp, p.top, p.x + 2f * dp, p.bottom, ink)
        c.drawCircle(p.x, p.knobY, r + 1.5f * dp, ink)
        ink.color = pinColor
        c.drawRect(p.x - 1.25f * dp, if (knobAbove) p.knobY else p.top, p.x + 1.25f * dp, if (knobAbove) p.bottom else p.knobY, ink)
        c.drawCircle(p.x, p.knobY, r, ink)
    }

    /** Centre of the note icon for a page offset. */
    private fun noteCentre(off: Int): Pair<Float, Float>? {
        val l = layout ?: return null
        if (off > text.length) return null
        val line = l.getLineForOffset(off)
        val base = l.getLineBaseline(line) + totalPaddingTop
        return (width - paddingRight / 2f) to (base - textSize * 0.32f)
    }

    /** The mark whose note icon is at (x, y), if any. */
    fun noteAt(x: Float, y: Float): String? {
        if (x < width - paddingRight - 4 * dp) return null
        return notes.firstOrNull { (off, _) ->
            val (_, cy) = noteCentre(off) ?: return@firstOrNull false
            kotlin.math.abs(y - cy) < 22 * dp
        }?.second
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (notes.isNotEmpty()) {
            val seen = HashSet<Int>()
            ink.color = noteColor
            ink.strokeWidth = 1.6f * dp
            for ((off, _) in notes) {
                val l = layout ?: break
                if (off > text.length || !seen.add(l.getLineForOffset(off))) continue
                val (cx, cy) = noteCentre(off) ?: continue
                // a small page with two lines of writing
                val w = 6.5f * dp
                val h = 8f * dp
                ink.style = Paint.Style.STROKE
                canvas.drawRoundRect(cx - w, cy - h, cx + w, cy + h, 2.5f * dp, 2.5f * dp, ink)
                canvas.drawLine(cx - w + 3 * dp, cy - 2.5f * dp, cx + w - 3 * dp, cy - 2.5f * dp, ink)
                canvas.drawLine(cx - w + 3 * dp, cy + 2.5f * dp, cx + w - 5 * dp, cy + 2.5f * dp, ink)
            }
        }
        pins()?.let { (a, b) ->
            drawPin(canvas, a, knobAbove = true)
            drawPin(canvas, b, knobAbove = false)
        }
    }

    companion object {
        /** Radius of a pull handle's knob, in dp. */
        const val KNOB = 9f
    }
}

/** Small grey raised number. */
private class VerseNumber(private val color: Int) : MetricAffectingSpan() {
    override fun updateDrawState(tp: TextPaint) = apply(tp, true)
    override fun updateMeasureState(tp: TextPaint) = apply(tp, false)
    private fun apply(tp: TextPaint, draw: Boolean) {
        val base = tp.textSize
        tp.textSize = base * 0.58f
        // Raise it only when drawing. Raising it while measuring makes the line taller.
        if (draw) { tp.baselineShift -= (base * 0.42f).toInt(); tp.color = color }
        tp.typeface = Typeface.create(tp.typeface, Typeface.NORMAL)
    }
}

/** A typeface with a weight. Works on Android 8 (where TypefaceSpan(Typeface) does not exist). */
private class TypefaceSpan2(private val face: Typeface, private val weight: Int) : MetricAffectingSpan() {
    private val made: Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(face, weight, face.isItalic) else
        Typeface.create(face, if (weight >= 600) (if (face.isItalic) Typeface.BOLD_ITALIC else Typeface.BOLD) else face.style)
    override fun updateDrawState(tp: TextPaint) { tp.typeface = made }
    override fun updateMeasureState(tp: TextPaint) { tp.typeface = made }
}

/** A footnote. The page shows a small letter; tapping it shows the note. */
class NoteSpan(val note: String)

/** Marks the copyright line, so a tap on it opens the source's site. */
class CreditSpan

/** The big grey chapter number that the first two lines flow around. */
private class DropCap(private val number: String, face: Typeface, textPx: Float, capColor: Int) : LeadingMarginSpan.LeadingMarginSpan2 {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = capColor
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(face, 400, false) else face
        textSize = textPx * 3.3f
    }
    val width: Int = (paint.measureText(number) + textPx * 0.55f).roundToInt()
    private var drawn = -1

    override fun getLeadingMarginLineCount() = 2
    override fun getLeadingMargin(first: Boolean) = if (first) width else 0

    override fun drawLeadingMargin(c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout) {
        val sp = text as? Spanned ?: return
        if (start != sp.getSpanStart(this)) return
        // sit the number's baseline on the second line's baseline
        val lineH = bottom - top
        val secondBaseline = baseline + (lineH * (if (layout.lineCount > layout.getLineForOffset(start) + 1) 1f else 0.6f))
        c.drawText(number, x.toFloat(), secondBaseline - lineH * 0.08f, paint)
    }
}

/** A mark drawn behind part of the text: a soft fill, or a dotted underline for a picked verse. */
class MarkSpan(private val view: TextView, val start: Int, val end: Int, private val tint: Int, private val kind: Int) : LineBackgroundSpan {
    companion object { const val FILL = 0; const val DOTS = 1 }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = tint
        if (kind == DOTS) {
            this.style = Paint.Style.STROKE
            strokeWidth = view.resources.displayMetrics.density * 1.6f
            pathEffect = DashPathEffect(floatArrayOf(view.resources.displayMetrics.density * 2f, view.resources.displayMetrics.density * 3f), 0f)
        }
    }
    private val rect = RectF()

    override fun drawBackground(c: Canvas, p: Paint, left: Int, right: Int, top: Int, baseline: Int, bottom: Int, text: CharSequence, lineStart: Int, lineEnd: Int, lineNumber: Int) {
        val layout = view.layout ?: return
        val s = maxOf(start, lineStart)
        var e = minOf(end, lineEnd)
        while (e > s && text[e - 1] == '\n') e--
        if (e <= s) return
        val x1 = layout.getPrimaryHorizontal(s)
        val x2 = if (e >= lineEnd) layout.getLineRight(lineNumber) else layout.getPrimaryHorizontal(e)
        val d = view.resources.displayMetrics.density
        val fm = p.fontMetrics
        if (kind == DOTS) {
            val y = baseline + d * 5f
            c.drawLine(x1, y, x2, y, paint)
        } else {
            rect.set(x1 - d * 2f, baseline + fm.ascent - d * 1f, x2 + d * 2f, baseline + fm.descent + d * 2f)
            c.drawRoundRect(rect, d * 4f, d * 4f, paint)
        }
    }
}
