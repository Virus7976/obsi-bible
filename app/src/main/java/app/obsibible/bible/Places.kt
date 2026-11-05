package app.obsibible.bible

/** A part of the Bible to search in: a testament, a group of books, or one book. */
data class Place(val name: String, val books: Set<String>)

/**
 * Search filters. The user types a word or a part of the Bible, adds it as a filter, then types
 * the next word. Words must all be in the verse. Places add up: John + Acts searches both.
 */
data class SearchFilters(val places: List<Place> = emptyList(), val words: List<String> = emptyList()) {
    val isEmpty get() = places.isEmpty() && words.isEmpty()

    /** The books to search in, or null for the whole Bible. */
    val books: Set<String>? get() = if (places.isEmpty()) null else places.flatMap { it.books }.toSet()

    /** The filter words plus what is typed now, as one query. */
    fun query(typed: String): String = (words + typed.trim()).filter { it.isNotBlank() }.joinToString(" ")

    fun add(p: Place) = if (places.any { it.name == p.name }) this else copy(places = places + p)
    fun add(word: String): SearchFilters {
        val w = word.trim().replace("\"", "")
        return if (w.isEmpty() || words.any { it.equals(w, true) }) this else copy(words = words + w)
    }

    /** Takes off the last filter added: used by backspace in an empty search box. */
    fun dropLast(): SearchFilters = when {
        words.isNotEmpty() -> copy(words = words.dropLast(1))
        else -> copy(places = places.dropLast(1))
    }
}

object Places {
    private fun books(from: Int, to: Int) = Books.all.filter { it.number in from..to }.map { it.id }.toSet()

    private class Group(val place: Place, val keys: List<String>)

    private val groups = listOf(
        Group(Place("Old Testament", books(1, 39)), listOf("old testament", "ot", "hebrew bible")),
        Group(Place("New Testament", books(40, 66)), listOf("new testament", "nt")),
        Group(Place("Law", books(1, 5)), listOf("law", "torah", "pentateuch", "books of moses")),
        Group(Place("History", books(6, 17)), listOf("history", "historical books")),
        Group(Place("Poetry and wisdom", books(18, 22)), listOf("poetry", "wisdom", "poetry and wisdom")),
        Group(Place("Prophets", books(23, 39)), listOf("prophets", "prophecy")),
        Group(Place("Major prophets", books(23, 27)), listOf("major prophets")),
        Group(Place("Minor prophets", books(28, 39)), listOf("minor prophets", "the twelve")),
        Group(Place("Gospels", books(40, 43)), listOf("gospels", "the gospels")),
        Group(Place("Paul's letters", books(45, 57)), listOf("paul's letters", "pauls letters", "paul", "pauline epistles")),
        Group(Place("Letters", books(45, 65)), listOf("letters", "epistles")),
        Group(Place("General letters", books(58, 65)), listOf("general letters", "general epistles")),
    )

    /**
     * Parts of the Bible that match what is typed: groups first, then books. "new" finds the
     * New Testament, "gos" the Gospels, "john" the book of John.
     */
    fun suggest(typed: String, max: Int = 3): List<Place> {
        val t = typed.trim().lowercase().replace(Regex("\\s+"), " ")
        if (t.length < 2 || t.any { it.isDigit() } && Books.match(t).isEmpty()) return emptyList()
        val out = ArrayList<Place>()
        for (g in groups) {
            val hit = g.keys.any { k -> k == t || (t.length >= 3 && k.startsWith(t)) || k.split(' ').any { w -> t.length >= 3 && w.startsWith(t) && w.length > 3 } }
            if (hit) out += g.place
        }
        val q = Books.squash(t)
        for (b in Books.match(t).take(2)) {
            // only clear matches: "rom" is Romans, but "am" is not Amos
            if (Books.squash(b.name).startsWith(q) && q.length >= 3 || b.aliases.any { Books.squash(it) == q }) out += Place(b.name, setOf(b.id))
        }
        return out.distinctBy { it.name }.take(max)
    }
}
