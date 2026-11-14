package app.obsibible

import app.obsibible.bible.RefLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RefLinksTest {
    private fun key(s: String) = RefLinks.find(s)?.key

    @Test fun youVersionLinks() {
        assertEquals("JHN.3.16", key("https://www.bible.com/bible/59/JHN.3.16.ESV"))
        assertEquals("PSA.23", key("Psalm 23 https://www.bible.com/bible/111/PSA.23.NIV"))
        assertEquals("ROM.8.28-30", key("https://www.bible.com/bible/59/ROM.8.28-30.ESV"))
    }

    @Test fun otherSites() {
        assertEquals("JHN.3.16", key("https://www.biblegateway.com/passage/?search=John+3%3A16&version=ESV"))
        assertEquals("JHN.3.16", key("https://www.esv.org/John+3:16/"))
        assertEquals("JHN.3.16", key("https://biblehub.com/john/3-16.htm"))
        assertEquals("JHN.3.16", key("https://www.blueletterbible.org/kjv/jhn/3/16/"))
        assertEquals("JHN.3.16", key("obsibible://ref/JHN.3.16"))
    }

    @Test fun plainText() {
        assertEquals("JHN.3.16", key("John 3:16"))
        assertEquals("1CO.13.4-7", key("Read 1 Cor 13:4-7 today"))
    }

    @Test fun notABiblePlace() {
        assertNull(key("https://www.bible.com/"))
        assertNull(key("hello there"))
    }
}
