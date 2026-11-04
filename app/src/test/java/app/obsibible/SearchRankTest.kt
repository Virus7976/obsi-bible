package app.obsibible

import app.obsibible.bible.SearchRank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRankTest {
    @Test fun phraseBeatsScatteredWords() {
        val q = listOf("love", "one", "another")
        val phrase = SearchRank.score("a new commandment i give you love one another", q)
        val scattered = SearchRank.score("love the lord and one day you will see another man", q)
        assertTrue(phrase > scattered)
    }

    @Test fun wholeWordBeatsPrefix() {
        val q = listOf("grace")
        assertTrue(SearchRank.score("by grace you have been saved", q) > SearchRank.score("he was gracious to them", listOf("grac")) - 1)
        assertTrue(SearchRank.score("by grace you have been saved", q) > SearchRank.score("by graceful steps", q))
    }

    @Test fun editDistanceCountsASwapAsOne() {
        assertEquals(1, SearchRank.distance("gace", "grace"))
        assertEquals(1, SearchRank.distance("jesus", "jeuss"))
        assertEquals(3, SearchRank.distance("abc", "xyzabc"))
    }

    private val vocab = mapOf("grace" to 120, "great" to 300, "shepherd" to 40, "love" to 500, "lord" to 7000, "trace" to 2, "heard" to 900, "steward" to 22)

    @Test fun fixesATypo() {
        assertEquals("grace", SearchRank.nearest("gace", vocab))
        assertEquals("shepherd", SearchRank.nearest("shepard", vocab))
    }

    @Test fun leavesGoodWordsAndPartWordsAlone() {
        assertNull(SearchRank.nearest("love", vocab))
        assertNull(SearchRank.nearest("shep", vocab)) // still typing
        assertNull(SearchRank.nearest("zzzzzz", vocab))
    }

    @Test fun correctsOnlyWhatIsWrong() {
        assertEquals(listOf("lord", "shepherd"), SearchRank.correct(listOf("lord", "shepard"), vocab))
        assertNull(SearchRank.correct(listOf("lord", "love"), vocab))
    }
}
