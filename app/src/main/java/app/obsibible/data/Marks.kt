package app.obsibible.data

import android.content.Context
import app.obsibible.bible.Ref
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/**
 * A highlight, a note, or both. It points into the Bible by verse and by the letter inside the
 * verse's plain text (the text with no verse numbers or note letters), so it does not depend on
 * text size or page layout.
 *
 * [c1] is the first letter in verse [v1]. [c2] is one past the last letter in verse [v2];
 * -1 means the end of that verse. [color] is a place in [HighlightColors]; -1 means no colour
 * (a note only).
 */
@Serializable
data class Mark(
    val id: String = UUID.randomUUID().toString(),
    val bible: String,
    val book: String,
    val chapter: Int,
    val v1: Int,
    val c1: Int = 0,
    val v2: Int,
    val c2: Int = -1,
    val color: Int = 0,
    val note: String = "",
    val tags: List<String> = emptyList(),
    val quote: String = "",
    val created: Long = System.currentTimeMillis(),
    val updated: Long = created,
    val clipped: Long = 0L,
) {
    val ref: Ref get() = Ref(book, chapter, v1, if (v2 > v1) v2 else 0)
    val label: String get() = ref.label
    val hasNote: Boolean get() = note.isNotBlank()
    val hasColor: Boolean get() = color >= 0
    fun covers(verse: Int) = verse in v1..v2
    fun sameChapter(bible: String, book: String, chapter: Int) = this.bible == bible && this.book == book && this.chapter == chapter
}

/** The rules for marks. Pure, so they are tested without a phone. */
object Marks {
    /** The words a mark covers, taken from the chapter's plain verse texts. */
    fun quote(verses: Map<Int, String>, m: Mark): String {
        val parts = ArrayList<String>()
        for (v in m.v1..m.v2) {
            val t = verses[v] ?: continue
            val from = if (v == m.v1) m.c1.coerceIn(0, t.length) else 0
            val to = if (v == m.v2 && m.c2 >= 0) m.c2.coerceIn(from, t.length) else t.length
            val piece = t.substring(from, to).trim()
            if (piece.isNotEmpty()) parts += piece
        }
        return parts.joinToString(" ")
    }

    /** True when the mark starts at the start of [v1] and ends at the end of [v2]. */
    fun wholeVerses(verses: Map<Int, String>, m: Mark): Boolean =
        m.c1 <= 0 && (m.c2 < 0 || m.c2 >= (verses[m.v2]?.length ?: 0))

    /** Splits picked verses into runs of verses that follow each other: 1,2,3,7 gives 1..3 and 7..7. */
    fun runs(verses: Collection<Int>): List<IntRange> {
        val out = ArrayList<IntRange>()
        for (v in verses.toSortedSet()) {
            val last = out.lastOrNull()
            if (last != null && v == last.last + 1) out[out.lastIndex] = last.first..v else out += v..v
        }
        return out
    }

    /**
     * Colours the picked verses. A plain highlight that sits fully inside the new one is
     * replaced, so painting over a verse changes its colour. Marks with a note are kept.
     * Returns the new list and the marks that were added.
     */
    fun paint(all: List<Mark>, bible: String, book: String, chapter: Int, verses: Collection<Int>, color: Int, chapterText: Map<Int, String>): Pair<List<Mark>, List<Mark>> {
        val added = runs(verses).map { r ->
            Mark(bible = bible, book = book, chapter = chapter, v1 = r.first, v2 = r.last, color = color)
                .let { it.copy(quote = quote(chapterText, it)) }
        }
        val kept = all.filterNot { m ->
            m.sameChapter(bible, book, chapter) && !m.hasNote && added.any { a -> m.v1 >= a.v1 && m.v2 <= a.v2 }
        }
        return (kept + added) to added
    }

    /** Takes the colour off marks that touch the picked verses. A mark with a note keeps its note. */
    fun erase(all: List<Mark>, bible: String, book: String, chapter: Int, verses: Collection<Int>): List<Mark> =
        all.mapNotNull { m ->
            if (!m.sameChapter(bible, book, chapter) || verses.none { m.covers(it) }) m
            else if (m.hasNote) m.copy(color = -1, updated = System.currentTimeMillis())
            else null
        }

    /**
     * Lines up a verse as it shows on the page with its plain text. [page] is the page text,
     * [from] until [to] is the verse on the page, and [skip] says which page letters are not part
     * of the text (verse numbers, note letters). Returns, for each plain letter, its place on the page.
     * Runs of spaces and line breaks count as one space, the same as the plain text.
     */
    fun align(page: CharSequence, from: Int, to: Int, skip: (Int) -> Boolean, plain: String): IntArray {
        val map = IntArray(plain.length)
        var j = 0
        var i = from
        var lastPage = from
        while (j < plain.length && i < to) {
            if (skip(i) || page[i] == ' ') { i++; continue }
            val pc = page[i]
            val qc = plain[j]
            when {
                pc == qc -> { map[j] = i; lastPage = i; i++; j++ }
                pc.isWhitespace() && qc == ' ' -> {
                    map[j] = i; lastPage = i; j++
                    i++
                    while (i < to && (page[i].isWhitespace() || skip(i))) i++
                }
                pc.isWhitespace() -> i++
                qc == ' ' -> { map[j] = i; j++ }
                else -> i++ // a page letter the plain text does not have
            }
        }
        while (j < plain.length) { map[j] = lastPage; j++ }
        return map
    }

    /** The place in [map] for page offset [off]: the last plain letter at or before it. */
    fun plainIndex(map: IntArray, off: Int): Int {
        var lo = 0
        var hi = map.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (map[mid] <= off) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/** All marks, kept in marks.json. */
class MarksStore(context: Context) : JsonFileStore<List<Mark>>(File(context.filesDir, "marks.json")) {
    private val ser = ListSerializer(Mark.serializer())
    private val _all = MutableStateFlow(readOrNull() ?: emptyList())
    val all: StateFlow<List<Mark>> = _all.asStateFlow()

    override fun encode(value: List<Mark>) = storeJson.encodeToString(ser, value)
    override fun decode(text: String) = storeJson.decodeFromString(ser, text)

    fun get(id: String): Mark? = _all.value.firstOrNull { it.id == id }

    fun set(list: List<Mark>) {
        _all.value = list
        persist(list)
    }

    fun change(block: (List<Mark>) -> List<Mark>) {
        _all.update(block)
        persist(_all.value)
    }

    fun put(m: Mark) = change { list -> if (list.any { it.id == m.id }) list.map { if (it.id == m.id) m else it } else list + m }

    fun remove(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Every tag used so far, most used first. */
    fun tags(): List<String> = _all.value.flatMap { it.tags }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
}
