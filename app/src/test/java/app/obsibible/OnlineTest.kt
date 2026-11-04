package app.obsibible

import app.obsibible.audio.Narrators
import app.obsibible.bible.ApiBible
import app.obsibible.bible.EsvApi
import app.obsibible.bible.Ref
import app.obsibible.bible.Seg
import app.obsibible.obsidian.Clip
import app.obsibible.obsidian.NoteBuilder
import app.obsibible.obsidian.VaultTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class OnlineTest {
    private val esvText = "The Word Became Flesh\n\n  [1] In the beginning was the Word, and the Word was with God. [2] He was in the beginning with God.\n\n" +
        "Psalm Heading\n\n    [3] The LORD is my shepherd;\n        I shall not want.\n"

    @Test fun esvHeadingsVersesAndPoetry() {
        val ch = EsvApi.parse(Ref("JHN", 1), esvText)
        assertEquals("s1", ch.blocks[0].kind)
        assertEquals("p", ch.blocks[1].kind)
        assertEquals("q1", ch.blocks[3].kind)
        assertEquals("q2", ch.blocks[4].kind)
        val v = ch.verses().toMap()
        assertEquals(listOf(1, 2, 3), v.keys.toList())
        assertEquals("He was in the beginning with God.", v[2]!!.trim())
        assertTrue(v[3]!!.contains("I shall not want."))
    }

    @Test fun segsSplitVerseNumbers() {
        val s = EsvApi.segs("[16] For God so loved [17] For God did not")
        assertEquals(listOf(Seg.VERSE, Seg.TEXT, Seg.VERSE, Seg.TEXT), s.map { it.t })
        assertEquals("16", s[0].s)
        assertEquals("For God did not", s[3].s)
    }

    @Test fun apiBibleTitleThenParagraphs() {
        val ch = ApiBible.parse(Ref("JHN", 3), "Jesus and Nicodemus\n[1] Now there was a man. [2] He came at night.\n[3] Jesus answered him.")
        assertEquals("s1", ch.blocks[0].kind)
        assertEquals(listOf(1, 2, 3), ch.verses().map { it.first })
    }

    @Test fun esvRecordingIsOnlyForTheEsvAndStreams() {
        assertEquals(listOf("esv"), Narrators.forBible("ESV").map { it.id })
        assertEquals(3, Narrators.forBible("BSB").size)
        assertTrue(Narrators.forBible("KJV").isEmpty())
        val n = Narrators.forBible("ESV")[0]
        assertTrue(n.streamOnly)
        assertEquals("esv://audio/JHN.3", n.audioUrl(Ref("JHN", 3, 16)))
        assertEquals("", n.timingUrl(Ref("JHN", 3)))
    }

    @Test fun mergeTagsKeepsOldAndAddsNew() {
        val inline = "---\ntitle: x\ntags: [bible, faith]\n---\nbody\n"
        assertEquals("---\ntitle: x\ntags:\n  - bible\n  - faith\n  - hope\n---\nbody\n", VaultTemplates.mergeTags(inline, listOf("#hope", "faith")))
        val block = "---\ntags:\n  - a\ncreated: 1\n---\n"
        assertEquals("---\ntags:\n  - a\n  - b\ncreated: 1\n---\n", VaultTemplates.mergeTags(block, listOf("b")))
        val none = "---\ntitle: x\n---\nbody"
        assertEquals("---\ntitle: x\ntags:\n  - b\n---\nbody", VaultTemplates.mergeTags(none, listOf("b")))
        assertEquals("---\ntags:\n  - b\n---\nbody", VaultTemplates.mergeTags("body", listOf("b")))
        assertEquals("body", VaultTemplates.mergeTags("body", emptyList()))
    }

    @Test fun momentFormats() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 3, 9, 5, 0) }.timeInMillis
        assertEquals("2026-11-03", VaultTemplates.moment("YYYY-MM-DD", at))
        assertEquals("Tuesday, November 3rd", VaultTemplates.moment("dddd, MMMM Do", at))
        assertEquals("09:05 AM", VaultTemplates.moment("HH:mm A", at))
        assertEquals("Week of 3", VaultTemplates.moment("[Week of] D", at))
    }

    @Test fun vaultTemplateGetsVerseAndDate() {
        val at = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 3, 9, 5, 0) }.timeInMillis
        val t = VaultTemplates.prepare("---\ndate: {{date:YYYY-MM-DD}}\ntags:\n---\n# {{title}}\n", at)
        assertTrue(t.contains("date: 2026-11-03"))
        assertTrue(t.contains(VaultTemplates.BIBLE_BLOCK))
        val own = VaultTemplates.prepare("{{text}}", at)
        assertFalse(own.contains("[!bible]"))
    }

    @Test fun youVersionHtmlWithDataUsfm() {
        val html = "<div class=\"s1\"><span class=\"heading\">The Word Became Flesh</span></div>" +
            "<div class=\"p\"><span class=\"verse v1\" data-usfm=\"JHN.1.1\"><span class=\"label\">1</span><span class=\"content\">In the beginning was the Word,</span></span>" +
            "<span class=\"verse v1\" data-usfm=\"JHN.1.1\"><span class=\"content\"> and the Word was God.</span></span>" +
            "<span class=\"verse v2\" data-usfm=\"JHN.1.2\"><span class=\"label\">2</span><span class=\"content\">He was with God</span>" +
            "<span class=\"note f\"><span class=\"label\">#</span><span class=\"body\">Or a footnote</span></span><span class=\"content\">&nbsp;in the beginning.</span></span></div>" +
            "<div class=\"q1\"><span class=\"verse v3\" data-usfm=\"JHN.1.3\"><span class=\"label\">3</span><span class=\"content\">All things were made</span></span></div>"
        val ch = app.obsibible.bible.YouVersion.parseHtml(Ref("JHN", 1), html)
        assertEquals("s1", ch.blocks[0].kind)
        assertEquals("The Word Became Flesh", ch.blocks[0].segs[0].s)
        val v = ch.verses().toMap()
        assertEquals(listOf(1, 2, 3), v.keys.toList())
        assertEquals("In the beginning was the Word, and the Word was God.", v[1]!!.trim())
        assertEquals("He was with God in the beginning.", v[2]!!.trim())
        assertEquals("q1", ch.blocks.last().kind)
    }

    @Test fun youVersionHtmlWithOnlyLabels() {
        val html = "<p><sup>16</sup>For God so loved the world, <sup>17</sup>For God did not send</p>"
        val v = app.obsibible.bible.YouVersion.parseHtml(Ref("JHN", 3), html).verses().toMap()
        assertEquals(listOf(16, 17), v.keys.toList())
        assertEquals("For God so loved the world,", v[16]!!.trim())
    }

    @Test fun settingsSearchFindsByWords() {
        val m = app.obsibible.ui.SettingsSearch::matches
        assertTrue(m("", "anything"))
        assertTrue(m("msg", "ESV The Message MSG key"))
        assertTrue(m("dark the", "Look appearance theme dark"))
        assertTrue(m("youv", "API.Bible YouVersion key"))
        assertFalse(m("vault", "Look appearance theme dark"))
    }

    @Test fun linksAndTagsReachTheNote() {
        val clip = Clip(Ref("JHN", 3, 16), "BSB", "Berean Standard Bible", "For God so loved the world", tags = listOf("love"), links = listOf("[[Grace]]"))
        val t = VaultTemplates.prepare("---\ntags: [study]\n---\n# {{title}}\n")
        val n = NoteBuilder.build(clip, append = false, folder = "Bible", template = t)
        assertTrue(n.markdown.contains("# John 3:16"))
        assertTrue(n.markdown.contains("  - study\n  - love"))
        assertTrue(n.markdown.trimEnd().endsWith("[[Grace]]"))
        val placed = NoteBuilder.build(clip, append = false, folder = "", template = "Links: {{links}}\n{{text}}")
        assertTrue(placed.markdown.contains("Links: [[Grace]]"))
        assertFalse(placed.markdown.trimEnd().endsWith("world\n[[Grace]]"))
    }

    @Test fun messageIsFoundAndSummed() {
        val f = app.obsibible.bible.MessageFinder
        val msg = app.obsibible.bible.RemoteBible("65", "MSG", "The Message", "English")
        val kjv = app.obsibible.bible.RemoteBible("de4e", "KJV", "King James Version", "English")
        assertTrue(f.isMessage(msg))
        assertTrue(f.isMessage(msg.copy(abbreviation = "", name = "The Message Bible")))
        assertFalse(f.isMessage(kjv))
        assertEquals("Your API.Bible key works. The Message is added to your Bibles.", f.summary("API.Bible", listOf(kjv, msg), true))
        assertEquals("Your API.Bible key works, but The Message is not on it. It has: KJV.", f.summary("API.Bible", listOf(kjv), false))
        assertTrue(f.summary("YouVersion", emptyList(), false).contains("no Bibles yet"))
    }
}
