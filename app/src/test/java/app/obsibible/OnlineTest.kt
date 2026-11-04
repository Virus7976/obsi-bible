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
}
