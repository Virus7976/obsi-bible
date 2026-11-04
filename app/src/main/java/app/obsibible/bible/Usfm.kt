package app.obsibible.bible

import kotlinx.serialization.Serializable

/**
 * One chapter as the reader shows it: a list of blocks (paragraphs, poetry lines, headings).
 * Text is stored, not the USFM, so the page needs no parsing.
 */
@Serializable
data class Chapter(val book: String, val number: Int, val blocks: List<Block>) {
    /** Plain text of each verse, without headings or notes. Used for search and the voice. */
    fun verses(): List<Pair<Int, String>> {
        val out = LinkedHashMap<Int, StringBuilder>()
        var v = 0
        for (b in blocks) {
            if (!b.isBody) continue
            for (s in b.segs) when (s.t) {
                Seg.VERSE -> { v = s.s.substringBefore('-').toIntOrNull() ?: v; out.getOrPut(v) { StringBuilder() } }
                Seg.TEXT -> if (v > 0) out.getOrPut(v) { StringBuilder() }.let { sb ->
                    if (sb.isNotEmpty() && !sb.endsWith(' ') && !s.s.startsWith(' ')) sb.append(' ')
                    sb.append(s.s)
                }
            }
        }
        return out.map { (k, sb) -> k to sb.toString().replace(Regex("\\s+"), " ").trim() }
    }
}

/**
 * A paragraph-level piece. [kind] is the USFM marker: p, m, q1, q2, li1, li2, pc, qr, pmo, b
 * for body text; s1, s2, ms, d, r for headings and titles.
 */
@Serializable
data class Block(val kind: String, val segs: List<Seg>) {
    val isBody: Boolean get() = kind !in headingKinds
    val isHeading: Boolean get() = kind in headingKinds

    companion object {
        val headingKinds = setOf("s1", "s2", "s3", "ms", "mr", "d", "r", "sp")
    }
}

/** A run inside a block: [t] is TEXT, VERSE (the number in [s]) or NOTE (footnote text in [s]). */
@Serializable
data class Seg(val t: Int, val s: String) {
    companion object {
        const val TEXT = 0
        const val VERSE = 1
        const val NOTE = 2
    }
}

/** Reads a USFM book file into chapters. Pure code, no Android. */
object Usfm {
    private val skipLine = setOf("id", "ide", "h", "toc1", "toc2", "toc3", "mt", "mt1", "mt2", "mt3", "rem", "sts", "usfm", "imt", "is", "ip", "cl", "cp")
    private val headings = mapOf("s" to "s1", "s1" to "s1", "s2" to "s2", "s3" to "s3", "ms" to "ms", "ms1" to "ms", "ms2" to "ms", "mr" to "mr", "d" to "d", "r" to "r", "sp" to "sp")
    private val paragraphs = mapOf(
        "p" to "p", "m" to "m", "pmo" to "pmo", "pm" to "p", "pi" to "p", "pi1" to "p", "nb" to "m", "po" to "p",
        "pc" to "pc", "q" to "q1", "q1" to "q1", "q2" to "q2", "q3" to "q2", "qr" to "qr", "qc" to "pc", "qa" to "d",
        "li" to "li1", "li1" to "li1", "li2" to "li2", "li3" to "li2", "qm" to "q1", "qm1" to "q1", "qm2" to "q2", "cls" to "qr",
    )

    /** The book id from the \id line, such as JHN. */
    fun bookId(text: String): String? = Regex("\\\\id\\s+([1-3A-Z]{3})").find(text)?.groupValues?.get(1)

    fun parse(text: String): List<Chapter> {
        val book = bookId(text) ?: return emptyList()
        val chapters = ArrayList<Chapter>()
        var chapter = 0
        var blocks = ArrayList<Block>()
        var kind = ""
        var segs = ArrayList<Seg>()
        val run = StringBuilder()
        var note: StringBuilder? = null
        var skipNote = false

        fun flushRun() {
            if (run.isEmpty()) return
            val s = run.toString().replace(Regex("\\s+"), " ")
            run.setLength(0)
            if (segs.isEmpty() || segs.last().t != Seg.TEXT) {
                val t = if (segs.isEmpty()) s.trimStart() else s
                if (t.isNotEmpty()) segs.add(Seg(Seg.TEXT, t))
            } else {
                segs[segs.lastIndex] = Seg(Seg.TEXT, segs.last().s + s)
            }
        }

        fun endBlock() {
            flushRun()
            if (kind.isNotEmpty() && chapter > 0) {
                val trimmed = segs.toMutableList()
                val last = trimmed.lastOrNull()
                if (last != null && last.t == Seg.TEXT) trimmed[trimmed.lastIndex] = Seg(Seg.TEXT, last.s.trimEnd())
                trimmed.removeAll { it.t == Seg.TEXT && it.s.isEmpty() }
                if (trimmed.isNotEmpty() || kind == "b") blocks.add(Block(kind, trimmed))
            }
            kind = ""
            segs = ArrayList()
        }

        fun endChapter() {
            endBlock()
            if (chapter > 0 && blocks.isNotEmpty()) chapters.add(Chapter(book, chapter, blocks))
            blocks = ArrayList()
        }

        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            if (c != '\\') {
                if (skipNote) { i++; continue }
                val target = note ?: run
                // \w word|strong="..."\w* : keep the word, drop everything after the bar
                if (c == '|') {
                    val end = text.indexOf('\\', i).let { if (it < 0) n else it }
                    i = end
                    continue
                }
                target.append(if (c == '\n' || c == '\r' || c == '\t') ' ' else c)
                i++
                continue
            }
            // read the marker name
            var j = i + 1
            while (j < n && (text[j].isLetterOrDigit())) j++
            val star = j < n && text[j] == '*'
            val name = text.substring(i + 1, j)
            i = if (star) j + 1 else j
            // one space after a marker belongs to the marker
            if (!star && i < n && text[i] == ' ') i++

            if (star) {
                when (name) {
                    "f", "fe" -> {
                        note?.let { segsAddNote(it, ::flushRun, segs) }
                        note = null
                    }
                    "x" -> skipNote = false
                }
                continue
            }
            when {
                name == "c" -> {
                    endChapter()
                    var k = i
                    while (k < n && text[k].isDigit()) k++
                    chapter = text.substring(i, k).toIntOrNull() ?: (chapter + 1)
                    i = k
                    while (i < n && text[i] != '\n' && text[i] != '\\') i++
                }
                name in skipLine -> {
                    while (i < n && text[i] != '\n') i++
                }
                name in headings -> {
                    endBlock()
                    kind = headings.getValue(name)
                }
                name in paragraphs -> {
                    endBlock()
                    kind = paragraphs.getValue(name)
                }
                name == "b" -> {
                    endBlock()
                    kind = "b"
                    endBlock()
                }
                name == "v" -> {
                    if (kind.isEmpty() || Block.headingKinds.contains(kind)) { endBlock(); kind = "p" }
                    var k = i
                    while (k < n && (text[k].isDigit() || text[k] == '-')) k++
                    val num = text.substring(i, k)
                    i = k
                    if (i < n && text[i] == ' ') i++
                    flushRun()
                    segs.add(Seg(Seg.VERSE, num))
                }
                name == "f" || name == "fe" -> {
                    // skip the caller character ("+")
                    while (i < n && text[i] != '\\') i++
                    note = StringBuilder()
                }
                name == "fr" || name == "xo" -> {
                    // the note's own reference ("3:16") is noise on the page
                    while (i < n && text[i] != '\\') i++
                }
                name == "x" -> skipNote = true
                else -> Unit // \w, \it, \nd, \wj, \add, \ft, \fq ... keep their text, drop the marker
            }
        }
        endChapter()
        return chapters
    }

    private fun segsAddNote(note: StringBuilder, flush: () -> Unit, segs: MutableList<Seg>) {
        flush()
        val t = note.toString().replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty()) segs.add(Seg(Seg.NOTE, t))
    }
}
