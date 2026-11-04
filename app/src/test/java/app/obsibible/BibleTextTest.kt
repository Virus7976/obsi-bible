package app.obsibible

import app.obsibible.bible.Block
import app.obsibible.bible.Books
import app.obsibible.bible.Ref
import app.obsibible.bible.Seg
import app.obsibible.bible.Usfm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

class BibleTextTest {
    @Test fun parsesCommonReferences() {
        assertEquals(Ref("JHN", 3, 16), Ref.parse("jn 3 16")!!.ref)
        assertEquals(Ref("JHN", 3, 16), Ref.parse("john3:16")!!.ref)
        assertEquals(Ref("1CO", 13), Ref.parse("1 cor 13")!!.ref)
        assertEquals(Ref("1CO", 13, 4, 7), Ref.parse("1co13.4-7")!!.ref)
        assertEquals(Ref("PSA", 23), Ref.parse("ps 23")!!.ref)
        assertEquals(Ref("PSA", 23), Ref.parse("Psalm 23")!!.ref)
        assertEquals(Ref("1JN", 1, 9), Ref.parse("1 John 1:9")!!.ref)
        assertEquals(Ref("SNG", 2), Ref.parse("song of songs 2")!!.ref)
        assertEquals(Ref("2TI", 3, 16), Ref.parse("II Tim 3:16")!!.ref)
        assertEquals(Ref("EPH", 3, 20), Ref.parse("ephesians 3:20")!!.ref)
    }

    @Test fun bookAloneIsChapterOneWithNoChapterGiven() {
        val p = Ref.parse("eph")!!
        assertEquals(Ref("EPH", 1), p.ref)
        assertFalse(p.chapterGiven)
        assertTrue(Ref.parse("eph 2")!!.chapterGiven)
    }

    @Test fun chapterIsClampedToTheBook() {
        assertEquals(Ref("JUD", 1), Ref.parse("jude 9")!!.ref.chapterRef)
        assertNull(Ref.parse("hello world 3"))
        assertNull(Ref.parse("12"))
    }

    @Test fun ephFindsEphesiansThenZephaniah() {
        assertEquals(listOf("EPH", "ZEP"), Books.match("eph").map { it.id })
        assertEquals("JHN", Books.match("jn").first().id)
        assertEquals("PHP", Books.match("phil").first().id)
        assertEquals("PHM", Books.match("philem").first().id)
    }

    @Test fun keysRoundTrip() {
        for (r in listOf(Ref("JHN", 3), Ref("JHN", 3, 16), Ref("1CO", 13, 4, 7))) assertEquals(r, Ref.fromKey(r.key))
        assertEquals("1 Corinthians 13:4-7", Ref("1CO", 13, 4, 7).label)
    }

    @Test fun nextAndPreviousCrossBooks() {
        assertEquals(Ref("EXO", 1), Ref("GEN", 50).next())
        assertEquals(Ref("MAL", 4), Ref("MAT", 1).previous())
        assertNull(Ref("REV", 22).next())
        assertNull(Ref("GEN", 1).previous())
    }

    @Test fun audioCodesMatchTheFileNames() {
        assertEquals("Jhn", Books.get("JHN")!!.audioCode)
        assertEquals("1Sa", Books.get("1SA")!!.audioCode)
        assertEquals("Tts", Books.get("TIT")!!.audioCode)
        assertEquals("Sng", Books.get("SNG")!!.audioCode)
    }

    private val sample = """
        \id JHN - Test
        \h John
        \mt1 John
        \c 1
        \s1 The Beginning
        \r (Genesis 1:1)
        \m
        \v 1 \w In|strong="G1722"\w* the beginning was the \w Word|strong="G3056"\w*.
        \v 2 He was with God \f + \fr 1:2 \ft Or \fq near\f* in the beginning.
        \b
        \q1
        \v 3 Through Him
        \q2 all things were made.
        \c 2
        \p
        \v 1 On the third day.
    """.trimIndent()

    @Test fun parsesBlocksVersesAndNotes() {
        val chapters = Usfm.parse(sample)
        assertEquals(2, chapters.size)
        val c1 = chapters[0]
        assertEquals(listOf("s1", "r", "m", "b", "q1", "q2"), c1.blocks.map { it.kind })
        assertEquals("The Beginning", c1.blocks[0].segs.single().s)
        val m = c1.blocks[2].segs
        assertEquals(Seg(Seg.VERSE, "1"), m[0])
        assertEquals("In the beginning was the Word.", m[1].s.trim())
        assertTrue(m.any { it.t == Seg.NOTE && it.s == "Or near" })
        assertEquals(
            listOf(1 to "In the beginning was the Word.", 2 to "He was with God in the beginning.", 3 to "Through Him all things were made."),
            c1.verses(),
        )
        assertEquals(listOf(1 to "On the third day."), chapters[1].verses())
    }

    @Test fun headingsAreNotBody() {
        assertTrue(Block("s1", emptyList()).isHeading)
        assertFalse(Block("q1", emptyList()).isHeading)
    }

    /** The bundled BSB: every chapter and every verse comes through. */
    @Test fun parsesTheWholeBundledBible() {
        val zip = File("src/main/assets/bibles/BSB.zip")
        ZipFile(zip).use { z ->
            val chapters = z.entries().toList().filter { it.name.endsWith(".usfm") }
                .flatMap { e -> Usfm.parse(z.getInputStream(e).readBytes().toString(Charsets.UTF_8)) }
            assertEquals(1189, chapters.size)
            val verses = chapters.flatMap { it.verses() }
            assertEquals(31086, verses.size)
            assertTrue("no verse is empty", verses.none { it.second.isBlank() })
            assertTrue("no markup leaks", verses.none { it.second.contains('\\') || it.second.contains("strong=") })
            val jn316 = chapters.first { it.book == "JHN" && it.number == 3 }.verses().first { it.first == 16 }.second
            assertTrue(jn316, jn316.startsWith("For God so loved the world"))
            for (b in Books.all) assertEquals(b.name, b.chapters, chapters.count { it.book == b.id })
        }
    }
}
