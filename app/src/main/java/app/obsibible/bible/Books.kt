package app.obsibible.bible

/** One book of the Bible. [id] is the USFM code (JHN), which is also the key everywhere in the app. */
data class Book(
    val id: String,
    val name: String,
    val number: Int,
    val chapters: Int,
    val aliases: List<String> = emptyList(),
) {
    val testament: Testament get() = if (number <= 39) Testament.Old else Testament.New

    /** The three-letter code in the openbible.com audio file names. Same as the id except Titus. */
    val audioCode: String get() = when {
        id == "TIT" -> "Tts"
        id[0].isDigit() -> "${id[0]}${id[1]}${id.substring(2).lowercase()}"
        else -> id[0] + id.substring(1).lowercase()
    }
}

enum class Testament { Old, New }

object Books {
    val all: List<Book> = listOf(
        Book("GEN", "Genesis", 1, 50, listOf("gn", "ge")),
        Book("EXO", "Exodus", 2, 40, listOf("ex", "exod")),
        Book("LEV", "Leviticus", 3, 27, listOf("lv", "le")),
        Book("NUM", "Numbers", 4, 36, listOf("nm", "nu")),
        Book("DEU", "Deuteronomy", 5, 34, listOf("dt", "deut")),
        Book("JOS", "Joshua", 6, 24, listOf("josh", "jsh")),
        Book("JDG", "Judges", 7, 21, listOf("jg", "judg")),
        Book("RUT", "Ruth", 8, 4, listOf("rt", "ru")),
        Book("1SA", "1 Samuel", 9, 31, listOf("1sm", "1s")),
        Book("2SA", "2 Samuel", 10, 24, listOf("2sm", "2s")),
        Book("1KI", "1 Kings", 11, 22, listOf("1kg", "1k")),
        Book("2KI", "2 Kings", 12, 25, listOf("2kg", "2k")),
        Book("1CH", "1 Chronicles", 13, 29, listOf("1chr")),
        Book("2CH", "2 Chronicles", 14, 36, listOf("2chr")),
        Book("EZR", "Ezra", 15, 10),
        Book("NEH", "Nehemiah", 16, 13),
        Book("EST", "Esther", 17, 10, listOf("es")),
        Book("JOB", "Job", 18, 42, listOf("jb")),
        Book("PSA", "Psalms", 19, 150, listOf("ps", "psalm", "pss", "psm")),
        Book("PRO", "Proverbs", 20, 31, listOf("pr", "prv", "prov")),
        Book("ECC", "Ecclesiastes", 21, 12, listOf("ec", "eccl", "qoh")),
        Book("SNG", "Song of Songs", 22, 8, listOf("song", "sos", "song of solomon", "canticles", "ss")),
        Book("ISA", "Isaiah", 23, 66, listOf("is")),
        Book("JER", "Jeremiah", 24, 52, listOf("jr")),
        Book("LAM", "Lamentations", 25, 5, listOf("la")),
        Book("EZK", "Ezekiel", 26, 48, listOf("ezek", "eze", "ezk")),
        Book("DAN", "Daniel", 27, 12, listOf("dn", "da")),
        Book("HOS", "Hosea", 28, 14, listOf("ho")),
        Book("JOL", "Joel", 29, 3, listOf("jl")),
        Book("AMO", "Amos", 30, 9, listOf("am")),
        Book("OBA", "Obadiah", 31, 1, listOf("ob", "obad")),
        Book("JON", "Jonah", 32, 4, listOf("jnh")),
        Book("MIC", "Micah", 33, 7, listOf("mi")),
        Book("NAM", "Nahum", 34, 3, listOf("na", "nah")),
        Book("HAB", "Habakkuk", 35, 3, listOf("hb")),
        Book("ZEP", "Zephaniah", 36, 3, listOf("zp", "zeph")),
        Book("HAG", "Haggai", 37, 2, listOf("hg")),
        Book("ZEC", "Zechariah", 38, 14, listOf("zc", "zech")),
        Book("MAL", "Malachi", 39, 4, listOf("ml")),
        Book("MAT", "Matthew", 40, 28, listOf("mt", "matt")),
        Book("MRK", "Mark", 41, 16, listOf("mk", "mr")),
        Book("LUK", "Luke", 42, 24, listOf("lk")),
        Book("JHN", "John", 43, 21, listOf("jn", "jhn")),
        Book("ACT", "Acts", 44, 28, listOf("ac")),
        Book("ROM", "Romans", 45, 16, listOf("rm", "ro")),
        Book("1CO", "1 Corinthians", 46, 16, listOf("1cor")),
        Book("2CO", "2 Corinthians", 47, 13, listOf("2cor")),
        Book("GAL", "Galatians", 48, 6, listOf("ga")),
        Book("EPH", "Ephesians", 49, 6, listOf("ephes")),
        Book("PHP", "Philippians", 50, 4, listOf("phil", "pp")),
        Book("COL", "Colossians", 51, 4),
        Book("1TH", "1 Thessalonians", 52, 5, listOf("1thess", "1ths")),
        Book("2TH", "2 Thessalonians", 53, 3, listOf("2thess", "2ths")),
        Book("1TI", "1 Timothy", 54, 6, listOf("1tim", "1tm")),
        Book("2TI", "2 Timothy", 55, 4, listOf("2tim", "2tm")),
        Book("TIT", "Titus", 56, 3, listOf("ti")),
        Book("PHM", "Philemon", 57, 1, listOf("philem", "pm")),
        Book("HEB", "Hebrews", 58, 13),
        Book("JAS", "James", 59, 5, listOf("jm", "jas")),
        Book("1PE", "1 Peter", 60, 5, listOf("1pet", "1pt")),
        Book("2PE", "2 Peter", 61, 3, listOf("2pet", "2pt")),
        Book("1JN", "1 John", 62, 5, listOf("1jhn", "1jo")),
        Book("2JN", "2 John", 63, 1, listOf("2jhn", "2jo")),
        Book("3JN", "3 John", 64, 1, listOf("3jhn", "3jo")),
        Book("JUD", "Jude", 65, 1, listOf("jud", "jde")),
        Book("REV", "Revelation", 66, 22, listOf("rv", "re", "apocalypse")),
    )

    private val byId = all.associateBy { it.id }

    fun get(id: String): Book? = byId[id.uppercase()]

    fun after(book: Book): Book? = all.getOrNull(book.number)
    fun before(book: Book): Book? = all.getOrNull(book.number - 2)

    /** Lowercase, no spaces or dots: "1 Cor." becomes "1cor". Roman numerals become digits. */
    fun squash(s: String): String {
        var t = s.lowercase().replace(Regex("[\\s.]+"), " ").trim()
        t = t.replace(Regex("^(iii|ii|i)\\s+(?=[a-z])")) { m -> when (m.groupValues[1]) { "i" -> "1"; "ii" -> "2"; else -> "3" } }
        t = t.replace(Regex("^(first|second|third)\\s+")) { m -> when (m.groupValues[1]) { "first" -> "1"; "second" -> "2"; else -> "3" } }
        return t.replace(" ", "")
    }

    /**
     * Books that match what was typed, best first. Prefix matches of the name or an alias come
     * first, then names that contain the text ("eph" finds Ephesians, then Zephaniah).
     */
    fun match(typed: String): List<Book> {
        val q = squash(typed)
        if (q.isEmpty()) return emptyList()
        data class Hit(val book: Book, val score: Int)
        val hits = all.mapNotNull { b ->
            val name = squash(b.name)
            val keys = listOf(name) + b.aliases.map(::squash)
            val score = when {
                keys.any { it == q } -> 0
                name.startsWith(q) -> 1
                keys.any { it.startsWith(q) } -> 2
                name.contains(q) -> 3
                subsequence(q, name) && q.length >= 3 -> 4
                else -> return@mapNotNull null
            }
            Hit(b, score)
        }
        return hits.sortedWith(compareBy<Hit> { it.score }.thenBy { it.book.number }).map { it.book }
    }

    private fun subsequence(q: String, s: String): Boolean {
        var i = 0
        for (c in s) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
}
