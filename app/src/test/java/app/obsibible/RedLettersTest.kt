package app.obsibible

import app.obsibible.bible.Ref
import app.obsibible.bible.Seg
import app.obsibible.bible.Usfm
import app.obsibible.bible.YouVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedLettersTest {
    private fun text(segs: List<Seg>) = segs.filter { it.t == Seg.TEXT }

    @Test fun usfmMarksJesusWords() {
        val usfm = "\\id JHN\n\\c 3\n\\p\n\\v 3 Jesus replied, \\wj “Truly, truly, I tell you,\\wj* he said.\n\\v 4 Nicodemus asked.\n"
        val ch = Usfm.parse(usfm).single()
        val t = text(ch.blocks.single().segs)
        assertEquals(listOf(false, true, false, false), t.map { it.wj })
        assertTrue(t[1].s.startsWith("“Truly"))
        // The verse text reads the same as before: red letters change only the colour.
        assertEquals("Jesus replied, “Truly, truly, I tell you, he said.", ch.verses().toMap()[3])
    }

    @Test fun jesusWordsEndWithTheParagraph() {
        val usfm = "\\id MAT\n\\c 5\n\\p\n\\v 3 \\wj Blessed are the poor\n\\p\n\\v 4 Narration here.\n"
        val ch = Usfm.parse(usfm).single()
        assertTrue(text(ch.blocks[0].segs).all { it.wj })
        assertTrue(text(ch.blocks[1].segs).none { it.wj })
    }

    @Test fun wordMarkersInsideStillRed() {
        val usfm = "\\id MAT\n\\c 5\n\\p\n\\v 3 \\wj \\w Blessed|strong=\"G3107\"\\w* are\\wj* he said.\n"
        val t = text(Usfm.parse(usfm).single().blocks.single().segs)
        assertEquals("Blessed are", t.first { it.wj }.s.trim())
        assertFalse(t.last().wj)
    }

    @Test fun youVersionHtmlMarksJesusWords() {
        val html = "<div class=\"p\"><span class=\"verse v3\" data-usfm=\"JHN.3.3\"><span class=\"label\">3</span>" +
            "<span class=\"content\">Jesus answered, </span><span class=\"wj\"><span class=\"content\">Truly I tell you.</span></span></span></div>"
        val segs = text(YouVersion.parseHtml(Ref("JHN", 3), html).blocks.single().segs)
        assertEquals(listOf(false, true), segs.map { it.wj })
        assertEquals("Truly I tell you.", segs[1].s.trim())
    }
}
