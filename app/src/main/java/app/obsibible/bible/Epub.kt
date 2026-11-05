package app.obsibible.bible

import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Reads a Bible from an EPUB file. EPUBs have no standard Bible markup, so this guesses:
 *  - a heading that names a book ("John", "The Gospel According to John") starts that book;
 *  - "Chapter 3", "John 3", "Psalm 23", or a heading or drop cap that is only a number starts a chapter;
 *  - a small number (in <sup>, or a tag whose class says verse) starts a verse; "1-4" is a group;
 *  - verse 1 again after a higher verse starts the next chapter;
 *  - footnotes, links to notes, and text before the first book are left out.
 * The result is the same [Chapter] the reader, search and voice already use.
 */
object Epub {
    class Book(val title: String, val pages: List<String>)

    /** Unzips an EPUB and returns its pages (XHTML) in reading order. */
    fun read(input: InputStream): Book {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(input.buffered()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                if (!e.isDirectory) files[e.name] = z.readBytes()
            }
        }
        fun text(name: String) = files[name]?.toString(Charsets.UTF_8)
        val container = text("META-INF/container.xml") ?: error("That file is not an EPUB.")
        val opfPath = Regex("full-path\\s*=\\s*\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: error("That EPUB has no contents list.")
        val opf = text(opfPath) ?: error("That EPUB has no contents list.")
        val base = opfPath.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }
        val manifest = HashMap<String, String>()
        for (m in Regex("<item\\b[^>]*>").findAll(opf)) {
            val id = attr(m.value, "id") ?: continue
            val href = attr(m.value, "href") ?: continue
            manifest[id] = resolve(base, java.net.URLDecoder.decode(href, "UTF-8"))
        }
        val order = Regex("<itemref\\b[^>]*>").findAll(opf).mapNotNull { attr(it.value, "idref") }.mapNotNull { manifest[it] }.toList()
        val pages = (order.ifEmpty { files.keys.filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) }.sorted() }).mapNotNull { text(it) }
        val title = Regex("<dc:title[^>]*>([\\s\\S]*?)</dc:title>").find(opf)?.groupValues?.get(1)?.let(::decode)?.trim().orEmpty()
        return Book(title, pages)
    }

    private fun attr(tag: String, name: String) =
        Regex("\\b$name\\s*=\\s*(\"([^\"]*)\"|'([^']*)')").find(tag)?.let { it.groupValues[2].ifEmpty { it.groupValues[3] } }

    private fun resolve(base: String, href: String): String {
        val parts = ArrayList<String>()
        for (p in (base + href.substringBefore('#')).split('/')) when (p) {
            "", "." -> {}
            ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
            else -> parts += p
        }
        return parts.joinToString("/")
    }

    // ---------- the guessing ----------

    private val verseLike = Regex("^(\\d{1,3})(?:\\s*[-\\u2013]\\s*(\\d{1,3}))?$")
    private val chapterWord = Regex("^(?:chapter|ch\\.?|psalm)\\s+(\\d{1,3})$", RegexOption.IGNORE_CASE)
    private val bookAndNumber = Regex("^(.+?)\\s+(\\d{1,3})$")
    private val prefixes = listOf(
        "the gospel according to saint", "the gospel according to st", "the gospel according to", "the gospel of", "the book of the", "the book of",
        "the letter of paul to the", "the letter of paul to", "the letter to the", "the letter to", "the letter of", "the epistle of paul to the",
        "the epistle to the", "the epistle of", "the acts of the", "the", "book of",
    )
    private val ordinals = mapOf("first" to "1", "second" to "2", "third" to "3", "1st" to "1", "2nd" to "2", "3rd" to "3", "i" to "1", "ii" to "2", "iii" to "3")

    /** The book a heading names, or null. Only clear matches count, so a heading like "Love" is not Lamentations. */
    fun bookOf(heading: String): String? {
        var t = heading.lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (t.isEmpty() || t.length > 60) return null
        // "The First Letter of Paul to the Corinthians" -> "1 corinthians"
        var num = ""
        for ((w, d) in ordinals) if (Regex("\\b$w\\b").containsMatchIn(t) && (w.length > 1 || t.startsWith("$w "))) { num = d; t = t.replace(Regex("\\b$w\\b"), " ").trim(); break }
        Regex("^([123])\\s*(.*)$").find(t)?.let { num = it.groupValues[1]; t = it.groupValues[2] }
        t = t.replace(Regex("\\s+"), " ").trim()
        for (p in prefixes) if (t.startsWith("$p ")) { t = t.removePrefix("$p ").trim(); break }
        t = t.replace(Regex("^(letter|epistle) (of|to) (the )?"), "").replace(Regex("^paul to (the )?"), "").replace(Regex("^(john|peter) "), "\$1 ").trim()
        fun find(words: String): String? {
            val key = Books.squash((if (num.isNotEmpty()) "$num " else "") + words)
            if (key.length < 2) return null
            return Books.all.firstOrNull { b -> Books.squash(b.name) == key || b.aliases.any { Books.squash(it) == key } }?.id
                ?: Books.all.firstOrNull { b -> val n = Books.squash(b.name); n.length >= 4 && (key == n + "s" || n == key + "s") }?.id
        }
        // "revelation to john", "song of solomon": try the whole name, then the part before "to"
        return find(t) ?: t.substringBefore(" to ").takeIf { it != t }?.let(::find)
    }

    private class Open(val name: String, val skip: Boolean, val verse: Boolean, val heading: Boolean, val chapter: Boolean, val block: Boolean)

    private val blockTags = setOf("p", "div", "li", "blockquote", "section", "article", "tr", "dd", "dt", "h1", "h2", "h3", "h4", "h5", "h6", "header", "table")
    private val voidTags = setOf("br", "img", "hr", "meta", "link", "input", "col", "wbr", "source")

    fun parse(pages: List<String>): List<Chapter> {
        val out = ArrayList<Chapter>()
        var book: String? = null
        var chapter = 0
        var verse = 0
        var tagVerses = false // verse numbers come in their own tags, so bare numbers in text are words
        val blocks = ArrayList<Block>()
        var kind = "p"
        var segs = ArrayList<Seg>()
        val pending = StringBuilder() // text of a heading, verse number or chapter mark being read

        fun flushBlock() {
            while (segs.isNotEmpty() && segs.last().t == Seg.TEXT && segs.last().s.isBlank()) segs.removeAt(segs.lastIndex)
            if (segs.any { it.t == Seg.TEXT && it.s.isNotBlank() } && book != null && chapter > 0) {
                val first = segs.indexOfFirst { it.t == Seg.TEXT }
                if (first >= 0) segs[first] = Seg(Seg.TEXT, segs[first].s.trimStart())
                blocks += Block(kind, segs.toList())
            }
            segs = ArrayList()
        }
        fun flushChapter() {
            flushBlock()
            val b = book
            if (b != null && chapter > 0 && blocks.any { bl -> bl.segs.any { it.t == Seg.VERSE } }) out += Chapter(b, chapter, blocks.toList())
            blocks.clear()
        }
        fun startChapter(n: Int) {
            flushChapter()
            chapter = n; verse = 0
        }
        fun startBook(id: String) {
            flushChapter()
            book = id; chapter = 0; verse = 0
        }
        fun text(s: String) {
            if (book == null) return
            if (chapter == 0) return
            if (verse == 0) {
                if (s.isBlank()) return
                verse = 1 // verse 1 often has no number: the chapter number stands in for it
                segs += Seg(Seg.VERSE, "1")
            }
            val last = segs.lastOrNull()
            if (last != null && last.t == Seg.TEXT) segs[segs.lastIndex] = Seg(Seg.TEXT, last.s + s) else segs += Seg(Seg.TEXT, s)
        }
        fun verseMark(raw: String): Boolean {
            val m = verseLike.find(raw.trim()) ?: return false
            val a = m.groupValues[1].toInt()
            val z = m.groupValues[2].toIntOrNull() ?: a
            if (book == null) return false
            if (chapter == 0) { if (a != 1) return false; startChapter(1) }
            if (a == 1 && verse > 1) startChapter(chapter + 1) // a new chapter with no heading
            else if (a <= verse || a > verse + 30) return false // not a verse number: a footnote or a year
            verse = maxOf(a, z)
            val last = segs.lastOrNull()
            if (last != null && last.t == Seg.TEXT && !last.s.endsWith(' ')) segs[segs.lastIndex] = Seg(Seg.TEXT, last.s + " ")
            segs += Seg(Seg.VERSE, if (z > a) "$a-$z" else "$a")
            return true
        }
        /** A heading: a book, a chapter, or a section title. */
        fun heading(raw: String, small: Boolean) {
            val t = raw.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) return
            chapterWord.find(t)?.let { m ->
                if (t.lowercase().startsWith("psalm") && book != "PSA") startBook("PSA") // "Psalm 23" names the book too
                if (book != null) startChapter(m.groupValues[1].toInt())
                return
            }
            if (verseLike.matches(t) && !t.contains('-') && book != null) { startChapter(t.toInt()); return }
            bookAndNumber.find(t)?.let { m ->
                val id = bookOf(m.groupValues[1])
                if (id != null) {
                    if (id != book) startBook(id)
                    startChapter(m.groupValues[2].toInt())
                    return
                }
            }
            val id = bookOf(t)
            if (id != null) { if (id != book) startBook(id); return }
            // a section title inside a chapter
            if (book != null && chapter > 0 && !small) {
                flushBlock()
                blocks += Block("s1", listOf(Seg(Seg.TEXT, t)))
            }
        }

        for (page in pages) {
            val body = page.substringAfter("<body", page).let { if (it === page) page else it.substringAfter('>') }
            val stack = ArrayList<Open>()
            for (m in Regex("<(/?)([a-zA-Z][\\w:-]*)([^>]*)>|([^<]+)").findAll(body)) {
                val raw = m.groupValues[4]
                if (raw.isNotEmpty()) {
                    if (stack.any { it.skip }) continue
                    val s = decode(raw)
                    if (stack.any { it.heading || it.verse || it.chapter }) pending.append(s)
                    else {
                        // A paragraph that starts with a bare number: a verse ("16 For God so loved")
                        // when it is the next verse, a chapter mark when it is the next chapter.
                        val atStart = segs.isEmpty()
                        val lead = if (atStart) Regex("^\\s*(\\d{1,3}(?:[-\\u2013]\\d{1,3})?)\\s+(?=\\S)").find(s) else null
                        if (lead != null) {
                            val n = lead.groupValues[1].substringBefore('-').substringBefore('\u2013').toInt()
                            val asVerse = book != null && chapter > 0 && n == verse + 1
                            if (asVerse && verseMark(lead.groupValues[1])) { text(s.substring(lead.range.last + 1)); continue }
                            if (book != null && n == chapter + 1 && !asVerse && !tagVerses) { startChapter(n); text(s.substring(lead.range.last + 1)); continue }
                        }
                        text(s)
                    }
                    continue
                }
                val closing = m.groupValues[1] == "/"
                val name = m.groupValues[2].lowercase().substringAfter(':')
                val attrs = m.groupValues[3]
                if (closing) {
                    val i = stack.indexOfLast { it.name == name }
                    if (i < 0) continue
                    val top = stack.subList(i, stack.size).toList()
                    while (stack.size > i) stack.removeAt(stack.lastIndex)
                    val o = top.first()
                    if (o.verse || o.heading || o.chapter) {
                        if (stack.none { it.verse || it.heading || it.chapter }) {
                            val t = pending.toString(); pending.setLength(0)
                            when {
                                o.chapter -> t.trim().toIntOrNull()?.let { if (book != null) startChapter(it) } ?: heading(t, false)
                                o.verse -> if (verseMark(t)) tagVerses = true
                                    else if (verseLike.find(t.trim()) == null && !(t.trim().length <= 2 && t.trim().all { it.isLetter() || it == '*' })) text(t) // a note letter is left out
                                else -> heading(t, name == "h5" || name == "h6")
                            }
                        }
                    }
                    if (top.any { it.block }) { flushBlock(); kind = "p" }
                    continue
                }
                val cls = (attr(attrs, "class").orEmpty() + " " + attr(attrs, "epub:type").orEmpty() + " " + attr(attrs, "id").orEmpty()).lowercase()
                val href = attr(attrs, "href").orEmpty()
                val skip = name in setOf("script", "style", "head", "nav", "aside", "title") ||
                    Regex("\\b(note|notes|footnote|footnotes|fn|noteref|endnote|rearnote|crossref|xref)\\b").containsMatchIn(cls) ||
                    (name == "a" && (href.contains("#fn") || href.contains("note", true)))
                val chapterMark = !skip && Regex("\\b(chapter|chapnum|chapter-?num|chap|c-num|dropcap|drop)\\b").containsMatchIn(cls) && name != "div" && name != "section"
                val verseTag = !skip && !chapterMark && (name == "sup" || Regex("\\b(verse|verse-?num|versenum|vnum|vn|v|vers)\\b").containsMatchIn(cls)) && name != "p" && name != "div"
                val headingTag = !skip && (name.matches(Regex("h[1-6]")) || (name != "body" && Regex("\\b(title|heading|subtitle|book-?title|section-?head)\\b").containsMatchIn(cls) && name != "span"))
                val isBlock = name in blockTags
                if (name in voidTags || attrs.trimEnd().endsWith("/")) { if (name == "br") text(" "); continue }
                if (isBlock && stack.none { it.heading || it.verse || it.chapter }) {
                    flushBlock()
                    kind = when {
                        Regex("\\b(poetry|poem|line|q|q1|indent1|stanza)\\b").containsMatchIn(cls) -> "q1"
                        Regex("\\b(q2|indent2)\\b").containsMatchIn(cls) -> "q2"
                        else -> "p"
                    }
                }
                stack += Open(name, skip, verseTag, headingTag, chapterMark, isBlock)
            }
            flushBlock()
        }
        flushChapter()
        // the same chapter twice (a contents page, then the text): keep the longer one
        return out.groupBy { it.book to it.number }.map { (_, list) -> list.maxBy { c -> c.verses().sumOf { it.second.length } } }
            .sortedWith(compareBy({ Books.get(it.book)?.number ?: 99 }, { it.number }))
    }

    private val entities = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "rsquo" to "\u2019", "lsquo" to "\u2018", "rdquo" to "\u201D", "ldquo" to "\u201C", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
    )

    fun decode(s: String): String = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);").replace(s) { m ->
        val k = m.groupValues[1]
        when {
            k.startsWith("#x") -> k.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            k.startsWith("#") -> k.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> entities[k] ?: m.value
        }
    }.replace('\u00A0', ' ').replace(Regex("[\\r\\n\\t]+"), " ")
}
