package app.obsibible

import app.obsibible.bible.Places
import app.obsibible.bible.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlacesTest {
    private fun names(t: String) = Places.suggest(t).map { it.name }

    @Test fun findsTestamentsGroupsAndBooks() {
        assertEquals("New Testament", names("new testament").first())
        assertEquals("New Testament", names("nt").first())
        assertTrue("Gospels" in names("gos"))
        assertEquals(listOf("John"), names("john"))
        assertTrue("Romans" in names("rom"))
        assertTrue("1 Corinthians" in names("1 cor"))
    }

    @Test fun plainWordsAreNotPlaces() {
        assertEquals(emptyList<String>(), names("dog"))
        assertEquals(emptyList<String>(), names("love"))
        assertEquals(emptyList<String>(), names("a"))
    }

    @Test fun newTestamentHas27Books() {
        assertEquals(27, Places.suggest("nt").first().books.size)
        assertEquals(4, Places.suggest("gospels").first().books.size)
    }

    @Test fun filtersStack() {
        var f = SearchFilters()
        assertNull(f.books)
        f = f.add(Places.suggest("john").first()).add(Places.suggest("acts").first()).add("dog").add("Dog")
        assertEquals(setOf("JHN", "ACT"), f.books)
        assertEquals(listOf("dog"), f.words)
        assertEquals("dog bread", f.query(" bread "))
        assertEquals("dog", f.query(""))
        f = f.dropLast()
        assertEquals(emptyList<String>(), f.words)
        assertEquals(2, f.places.size)
    }
}
