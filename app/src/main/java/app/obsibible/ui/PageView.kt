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
    val text = TextView(context)
    private var verseStarts: List<Pair<Int, Int>> = emptyList() // verse -> start offset, in order
    private var verseEnds: Map<Int, Int> = emptyMap()
    private var follow: MarkSpan? = null
    private var word: MarkSpan? = null
    private var selected = HashMap<Int, MarkSpan>()
    private var colors = PageColors(0, 0, 0, 0, 0, 0)
    private var lastTouch = 0L
    private var shownKey = ""

    var onVerseTap: (Int) -> Unit = {}
    var onNoteTap: (String) -> Unit = {}
    var onScrolled: () -> Unit = {}

    private val serif: Typeface = ResourcesCompat.getFont(context, R.font.newsreader) ?: Typeface.SERIF
    private val serifItalic: Typeface = ResourcesCompat.getFont(context, R.font.newsreader_italic) ?: Typeface.create(Typeface.SERIF, Typeface.ITALIC)

    init {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        val frame = FrameLayout(context)
        text.typeface = serif
        text.includeFontPadding = false
        frame.addView(text, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        addView(frame, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        setOnScrollChangeListener { _, _, _, _, _ -> onScrolled() }
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downAt = 0L
        text.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; downAt = System.currentTimeMillis(); lastTouch = downAt }
                MotionEvent.ACTION_UP -> {
                    lastTouch = System.currentTimeMillis()
                    if (abs(e.x - downX) < slop && abs(e.y - downY) < slop && lastTouch - downAt < 500) tap(e.x, e.y)
                }
            }
            false
        }
        setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_MOVE) lastTouch = System.currentTimeMillis(); false }
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
        text.setText(built.text, TextView.BufferType.SPANNABLE)
        if (keepVerse > 1) scrollToVerse(keepVerse, false) else scrollTo(0, 0)
    }

    private class Built(val text: CharSequence, val starts: List<Pair<Int, Int>>, val ends: Map<Int, Int>)

    private fun build(ch: Chapter, px: Float): Built {
        val sb = SpannableStringBuilder()
        val starts = ArrayList<Pair<Int, Int>>()
        val ends = HashMap<Int, Int>()
        var verse = 0
        val indent = (px * 1.6f).roundToInt()
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
                    "q1" -> LeadingMarginSpan.Standard(0, indent)
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
        return Built(sb, starts, ends)
    }

    // ---------- marks ----------

    private fun range(verse: Int): IntRange? {
        val s = verseStarts.firstOrNull { it.first == verse }?.second ?: return null
        val e = verseEnds[verse] ?: return null
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
        if (scroll && !recentlyTouched) keepInView(word?.let { it.start } ?: r.first)
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
            val r = range(v) ?: continue
            val s = MarkSpan(text, r.first, r.last + 1, colors.underline, MarkSpan.DOTS)
            sp.setSpan(s, r.first, r.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            selected[v] = s
        }
        text.invalidate()
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
        sp.getSpans((off - 1).coerceAtLeast(0), off + 1, NoteSpan::class.java).firstOrNull()?.let { onNoteTap(it.note); return }
        val v = verseStarts.lastOrNull { it.second <= off + 1 }?.first ?: return
        val end = verseEnds[v] ?: return
        if (off <= end + 1) onVerseTap(v)
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

/** The big grey chapter number that the first two lines flow around. */
private class DropCap(private val number: String, face: Typeface, textPx: Float, capColor: Int) : LeadingMarginSpan.LeadingMarginSpan2 {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = capColor
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(face, 400, false) else face
        textSize = textPx * 3.1f
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
