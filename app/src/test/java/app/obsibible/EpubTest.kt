package app.obsibible

import app.obsibible.bible.Chapter
import app.obsibible.bible.Epub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EPUB pages in the common layouts, with public-domain BSB text. */
class EpubTest {
    private fun page(body: String) = "<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><head><title>x</title></head><body>$body</body></html>"
    private fun Chapter.v() = verses().toMap()

    @Test fun supVerseNumbersWithChapterHeadings() {
        val out = Epub.parse(listOf(page("""
            <h1>The Gospel According to John</h1>
            <h2>Chapter 3</h2>
            <h3>Jesus and Nicodemus</h3>
            <p><sup>1</sup>Now there was a Pharisee named Nicodemus, a leader of the Jews. <sup>2</sup>He came to Jesus at night.</p>
            <p><sup>16</sup>For God so loved the world that He gave His one and only Son.<sup><a href="#fn1">a</a></sup></p>
            <aside epub:type="footnote" id="fn1">Or only begotten</aside>
        """.trimIndent())))
        assertEquals(1, out.size)
        val c = out[0]
        assertEquals("JHN", c.book); assertEquals(3, c.number)
        assertEquals(listOf(1, 2, 16), c.v().keys.toList())
        assertEquals("For God so loved the world that He gave His one and only Son.", c.v()[16])
        assertTrue(c.blocks.any { it.kind == "s1" && it.segs[0].s == "Jesus and Nicodemus" })
    }

    @Test fun verseGroupsAndDropCapChapters() {
        val out = Epub.parse(listOf(page("""
            <h1 class="book-title">Mark</h1>
            <p><span class="chapter">1</span>The beginning of the gospel of Jesus Christ. <span class="verse">2-3</span>As it is written in Isaiah the prophet.</p>
            <p><span class="verse">4</span>John appeared in the wilderness.</p>
            <p><span class="chapter">2</span>A few days later, Jesus went back to Capernaum.</p>
        """.trimIndent())))
        assertEquals(listOf(1, 2), out.map { it.number })
        assertEquals(listOf(1, 2, 4), out[0].v().keys.toList())
        assertTrue(out[0].blocks.flatMap { it.segs }.any { it.s == "2-3" })
        assertEquals("A few days later, Jesus went back to Capernaum.", out[1].v()[1])
    }

    @Test fun bareNumbersAndPsalmHeadings() {
        val out = Epub.parse(listOf(page("<h1>Introduction</h1><p>This book was printed in 1 place, 2 times.</p>"), page("""
            <h2>Psalm 23</h2>
            <p class="poetry">1 The LORD is my shepherd; I shall not want.</p>
            <p class="poetry">2 He makes me lie down in green pastures.</p>
        """.trimIndent())))
        assertEquals(1, out.size)
        assertEquals("PSA", out[0].book); assertEquals(23, out[0].number)
        assertEquals("He makes me lie down in green pastures.", out[0].v()[2])
        assertEquals("q1", out[0].blocks[0].kind)
    }

    @Test fun verseOneAgainStartsTheNextChapter() {
        val out = Epub.parse(listOf(page("""
            <h1>Jude</h1><h1>Ruth</h1>
            <p><sup>1</sup>In the days when the judges ruled. <sup>2</sup>The man was named Elimelech.</p>
            <p><sup>1</sup>Now Naomi had a relative. <sup>2</sup>And Ruth said to Naomi.</p>
        """.trimIndent())))
        assertEquals(listOf("RUT" to 1, "RUT" to 2), out.map { it.book to it.number })
    }

    @Test fun bookNames() {
        assertEquals("1CO", Epub.bookOf("The First Letter of Paul to the Corinthians"))
        assertEquals("1JN", Epub.bookOf("1 John"))
        assertEquals("SNG", Epub.bookOf("Song of Solomon"))
        assertEquals("REV", Epub.bookOf("The Revelation to John"))
        assertEquals("GEN", Epub.bookOf("GENESIS"))
        assertEquals("PSA", Epub.bookOf("Psalms"))
        assertNull(Epub.bookOf("Love"))
        assertNull(Epub.bookOf("Introduction"))
        assertNull(Epub.bookOf("Contents"))
    }

    @Test fun readsTheSpineInOrder() {
        val bytes = ByteArrayOutputStream().also { bo ->
            ZipOutputStream(bo).use { z ->
                fun put(n: String, t: String) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray()); z.closeEntry() }
                put("mimetype", "application/epub+zip")
                put("META-INF/container.xml", "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>")
                put("OEBPS/content.opf", "<package><metadata><dc:title>Test Bible</dc:title></metadata><manifest><item id=\"b\" href=\"text/b.xhtml\"/><item id=\"a\" href=\"text/a.xhtml\"/></manifest><spine><itemref idref=\"a\"/><itemref idref=\"b\"/></spine></package>")
                put("OEBPS/text/a.xhtml", page("<h1>Jude</h1><p><sup>1</sup>Jude, a servant.</p>"))
                put("OEBPS/text/b.xhtml", page("<p><sup>2</sup>Mercy, peace and love.</p>"))
            }
        }.toByteArray()
        val book = Epub.read(ByteArrayInputStream(bytes))
        assertEquals("Test Bible", book.title)
        val out = Epub.parse(book.pages)
        assertEquals(listOf(1, 2), out.single().v().keys.toList())
    }
}
