package app.obsibible

import app.obsi.core.data.AppSettings
import app.obsi.core.data.ClipTemplate
import app.obsi.core.data.TemplateProperty
import app.obsibible.bible.Ref
import app.obsibible.obsidian.Clip
import app.obsibible.obsidian.VerseNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VerseNoteTest {
    private val clip = Clip(Ref("JHN", 3, 16), "BSB", "Berean Standard Bible", "For God so loved the world", note = "Love", tags = listOf("gospel"))

    @Test fun verseTemplateMakesFrontmatterAndLink() {
        val n = VerseNote.build(VerseNote.VERSE, clip, AppSettings())
        assertEquals("create", n.behavior)
        assertTrue(n.markdown, n.markdown.startsWith("---\n"))
        assertTrue(n.markdown, n.markdown.contains("John 3:16"))
        assertTrue(n.markdown, n.markdown.contains("gospel"))
        assertTrue(n.markdown, n.markdown.contains("For God so loved the world"))
        assertTrue(n.markdown, n.markdown.contains("obsibible://ref/JHN.3.16"))
    }

    @Test fun autoTagsJoinTheTags() {
        val n = VerseNote.build(VerseNote.VERSE, clip, AppSettings(autoTags = listOf("#study")))
        assertTrue(n.markdown, n.markdown.contains("study"))
        assertTrue(n.markdown, !n.markdown.contains("#study"))
    }

    @Test fun runningTemplateAppends() {
        val n = VerseNote.build(VerseNote.RUNNING, clip, AppSettings())
        assertEquals("append-specific", n.behavior)
        assertEquals("Bible highlights", n.name)
    }

    @Test fun askAnswerWinsOverTemplateValue() {
        val t = ClipTemplate("t", "T", properties = listOf(TemplateProperty("p1", "mood", "", "text", ask = true)))
        val n = VerseNote.build(t, clip, AppSettings(), mapOf("p1" to "glad"))
        assertTrue(n.markdown, n.markdown.contains("mood: \"glad\"") || n.markdown.contains("mood: glad"))
    }

    @Test fun linksGoAtTheEndWhenTemplateHasNoPlace() {
        val t = ClipTemplate("t", "T", noteContentFormat = "{{text}}")
        val n = VerseNote.build(t, clip.copy(links = listOf("[[Grace]]")), AppSettings())
        assertTrue(n.markdown, n.markdown.trimEnd().endsWith("[[Grace]]"))
    }

    @Test fun mergeAddsTheVerseOnce() {
        val n = VerseNote.build(VerseNote.RUNNING, clip, AppSettings())
        val once = VerseNote.merge("# Notes\n", n)
        assertEquals(1, once.addedHighlights)
        val twice = VerseNote.merge(once.text, n)
        assertEquals(0, twice.addedHighlights)
    }

    @Test fun seededAddsBuiltInsOnce() {
        val s = VerseNote.seeded(AppSettings(templates = emptyList()))
        assertEquals(listOf(VerseNote.VERSE_ID, VerseNote.RUNNING_ID), s.templates.map { it.id })
        assertEquals(VerseNote.VERSE_ID, s.selectedTemplateId)
        assertEquals(s, VerseNote.seeded(s))
    }
    @Test fun newTemplatesSaveToTheObsiFolder() {
        app.obsi.core.Brand.init(app.obsi.core.ObsiApp.Bible)
        val s = VerseNote.seeded(AppSettings(templates = emptyList()))
        assertEquals(listOf("Obsi/Bible", "Obsi/Bible"), s.templates.map { it.path })
    }

    @Test fun oldBibleTemplatesMoveButOthersStay() {
        app.obsi.core.Brand.init(app.obsi.core.ObsiApp.Bible)
        val mine = ClipTemplate("t", "Mine", path = "Bible")
        val s = AppSettings(templates = listOf(VerseNote.VERSE, VerseNote.RUNNING.copy(path = "Study"), mine))
        assertEquals(listOf("Obsi/Bible", "Study", "Bible"), VerseNote.toObsiFolder(s).templates.map { it.path })
    }
}
