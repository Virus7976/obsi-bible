package app.obsibible.bible

/**
 * A place in the Bible. [verse] 0 means the whole chapter. [verseEnd] 0 means one verse.
 * The key form is JHN.3.16 (used in links: obsibible://ref/JHN.3.16).
 */
data class Ref(val book: String, val chapter: Int, val verse: Int = 0, val verseEnd: Int = 0) {
    val bookInfo: Book get() = Books.get(book) ?: Books.all[0]

    val key: String get() = buildString {
        append(book).append('.').append(chapter)
        if (verse > 0) append('.').append(verse)
        if (verseEnd > verse) append('-').append(verseEnd)
    }

    val label: String get() = buildString {
        // One psalm is a "Psalm": Psalm 23, Psalm 34:18.
        append(if (book == "PSA") "Psalm" else bookInfo.name).append(' ').append(chapter)
        if (verse > 0) append(':').append(verse)
        if (verseEnd > verse) append('-').append(verseEnd)
    }

    val chapterRef: Ref get() = Ref(book, chapter)

    fun next(): Ref? {
        val b = bookInfo
        return if (chapter < b.chapters) Ref(book, chapter + 1) else Books.after(b)?.let { Ref(it.id, 1) }
    }

    fun previous(): Ref? {
        val b = bookInfo
        return if (chapter > 1) Ref(book, chapter - 1) else Books.before(b)?.let { Ref(it.id, it.chapters) }
    }

    companion object {
        /** Reads the key form: JHN.3, JHN.3.16, JHN.3.16-18. */
        fun fromKey(key: String): Ref? {
            val m = Regex("^([1-3]?[A-Za-z]{2,3})\\.(\\d+)(?:\\.(\\d+)(?:-(\\d+))?)?$").find(key.trim()) ?: return null
            val book = Books.get(m.groupValues[1]) ?: return null
            val ch = m.groupValues[2].toInt().coerceIn(1, book.chapters)
            val v = m.groupValues[3].toIntOrNull() ?: 0
            val e = m.groupValues[4].toIntOrNull() ?: 0
            return Ref(book.id, ch, v, if (e > v) e else 0)
        }

        private val pattern = Regex(
            "^\\s*((?:[1-3]|iii|ii|i|first|second|third)?\\s*[a-z][a-z .]*?)\\s*(\\d+)?(?:\\s*[:. ]\\s*(\\d+)(?:\\s*[-\\u2013]\\s*(\\d+))?)?\\s*$",
        )

        /**
         * Reads what a person types: "jn 3 16", "john3:16", "1 cor 13", "1co13.4-7", "ps 23",
         * "eph". Returns null when no book matches. A book alone gives chapter 1, with [Parsed.chapterGiven] false.
         */
        fun parse(text: String): Parsed? {
            val m = pattern.find(text.lowercase()) ?: return null
            val book = Books.match(m.groupValues[1]).firstOrNull() ?: return null
            val chText = m.groupValues[2]
            val ch = chText.toIntOrNull()?.coerceIn(1, book.chapters) ?: 1
            val v = m.groupValues[3].toIntOrNull() ?: 0
            val e = m.groupValues[4].toIntOrNull() ?: 0
            return Parsed(Ref(book.id, ch, v, if (e > v) e else 0), chText.isNotEmpty())
        }
    }

    data class Parsed(val ref: Ref, val chapterGiven: Boolean)
}
