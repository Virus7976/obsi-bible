package app.obsibible

import app.obsibible.bible.Ref
import app.obsibible.data.Mark
import app.obsibible.data.Marks
import app.obsibible.obsidian.Clip
import app.obsibible.obsidian.NoteBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksTest {
    private val verses = mapOf(
        16 to "For God so loved the world that He gave His one and only Son,",
        17 to "For God did not send His Son into the world to condemn the world,",
        18 to "Whoever believes in Him is not condemned,",
    )

    private fun mark(v1: Int, c1: Int, v2: Int, c2: Int, color: Int = 0, note: String = "") =
        Mark(bible = "BSB", book = "JHN", chapter = 3, v1 = v1, c1 = c1, v2 = v2, c2 = c2, color = color, note = note)

    @Test fun quoteTakesPartOfOneVerse() {
        assertEquals("God so loved the world", Marks.quote(verses, mark(16, 4, 16, 26)))
    }

    @Test fun quoteRunsAcrossVerses() {
        val q = Marks.quote(verses, mark(16, 31, 17, 7))
        assertEquals("He gave His one and only Son, For God", q)
    }

    @Test fun wholeVerseMarks() {
        assertTrue(Marks.wholeVerses(verses, mark(16, 0, 17, -1)))
        assertFalse(Marks.wholeVerses(verses, mark(16, 4, 17, -1)))
    }

    @Test fun runsSplitGaps() {
        assertEquals(listOf(1..3, 7..7), Marks.runs(listOf(3, 1, 2, 7)))
    }

    @Test fun paintReplacesAPlainHighlightButKeepsANote() {
        val plain = mark(17, 0, 17, -1, color = 1)
        val noted = mark(17, 0, 17, 10, color = 2, note = "keep")
        val other = Mark(bible = "BSB", book = "JHN", chapter = 4, v1 = 17, v2 = 17)
        val (list, added) = Marks.paint(listOf(plain, noted, other), "BSB", "JHN", 3, listOf(16, 17, 18), 3, verses)
        assertEquals(1, added.size)
        assertEquals(16, added[0].v1)
        assertEquals(18, added[0].v2)
        assertTrue(list.none { it.id == plain.id })
        assertTrue(list.any { it.id == noted.id })
        assertTrue(list.any { it.id == other.id })
        assertTrue(added[0].quote.startsWith("For God so loved"))
    }

    @Test fun eraseKeepsTheNoteOnly() {
        val noted = mark(17, 0, 17, -1, color = 2, note = "keep")
        val plain = mark(18, 0, 18, -1, color = 1)
        val out = Marks.erase(listOf(noted, plain), "BSB", "JHN", 3, listOf(17, 18))
        assertEquals(1, out.size)
        assertEquals(-1, out[0].color)
        assertEquals("keep", out[0].note)
    }

    @Test fun alignSkipsVerseNumbersAndLineBreaks() {
        // page: "16 For God\n  so loved" where "16 " is a verse number
        val page = "16 For God\n  so loved"
        val plain = "For God so loved"
        val map = Marks.align(page, 3, page.length, { it < 3 }, plain)
        assertEquals(3, map[0])
        assertEquals(page.indexOf("so"), map[plain.indexOf("so")])
        assertEquals(page.length - 1, map.last())
        assertEquals(plain.indexOf("so"), Marks.plainIndex(map, page.indexOf("so")))
    }

    @Test fun alignHandlesAMissingSpace() {
        // the plain text has a space the page does not (two pieces joined)
        val map = Marks.align("loveda", 0, 6, { it == 5 }, "loved")
        assertEquals(4, map[4])
        val map2 = Marks.align("God so", 0, 6, { false }, "God  so".replace("  ", " "))
        assertEquals(4, map2[4])
    }

    @Test fun noteHasLinkFrontMatterAndQuote() {
        val c = Clip(Ref("JHN", 3, 16, 17), "BSB", "Berean Standard Bible", "For God so loved\nthe world", note = "Grace.", tags = listOf("love", "gospel words"), at = 0L)
        val n = NoteBuilder.build(c, append = false, folder = "/Bible/")
        assertEquals("John 3.16-17", n.name)
        assertEquals("Bible", n.folder)
        assertTrue(n.markdown.contains("[John 3:16-17](obsibible://ref/JHN.3.16-17)"))
        assertTrue(n.markdown.contains("> For God so loved\n> the world"))
        assertTrue(n.markdown.contains("tags: [bible, love, gospel-words]"))
        assertTrue(n.markdown.contains("Grace."))
        assertFalse(n.markdown.contains("{{"))
    }

    @Test fun emptyNoteLeavesNoGap() {
        val c = Clip(Ref("PSA", 23, 1), "BSB", "Berean Standard Bible", "The LORD is my shepherd", at = 0L)
        val n = NoteBuilder.build(c, append = true, folder = "", appendFile = "Bible highlights")
        assertEquals("Bible highlights", n.name)
        assertTrue(n.append)
        assertTrue(n.markdown.startsWith("## Psalm 23:1 · BSB"))
        assertFalse(n.markdown.contains("\n\n\n"))
        assertTrue(n.markdown.endsWith("\n"))
    }

    @Test fun safeNames() {
        assertEquals("John 3.16", NoteBuilder.safeName("John 3:16"))
        assertEquals("a b", NoteBuilder.safeName("a/b"))
    }

    @Test fun findsVerseAndVaultLinks() {
        val text = "See [John 3:16](obsibible://ref/JHN.3.16) and [[Grace]] and [[Sermons/Hope|hope talk]]."
        val links = app.obsibible.obsidian.NoteLinks.find(text)
        assertEquals(3, links.size)
        val v = links[0] as app.obsibible.obsidian.NoteLink.Verse
        assertEquals("JHN.3.16", v.ref.key)
        assertEquals("John 3:16", v.text)
        assertEquals("Grace", (links[1] as app.obsibible.obsidian.NoteLink.Note).name)
        val n = links[2] as app.obsibible.obsidian.NoteLink.Note
        assertEquals("Sermons/Hope", n.name)
        assertEquals("hope talk", n.text)
        assertEquals("[John 3:16](obsibible://ref/JHN.3.16)", app.obsibible.obsidian.NoteLinks.verse(app.obsibible.bible.Ref("JHN", 3, 16)))
    }
}
