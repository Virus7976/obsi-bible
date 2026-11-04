package app.obsibible

import app.obsibible.bible.Ref
import app.obsibible.data.AppSettings
import app.obsibible.data.ClipTemplate
import app.obsibible.data.TemplateProperty
import app.obsibible.obsidian.Clip
import app.obsibible.obsidian.TemplateNote
import app.obsibible.obsidian.VaultTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateNoteTest {
    private val clip = Clip(Ref("JHN", 3, 16), "BSB", "Berean Standard Bible", "For God so loved the world", note = "Love", tags = listOf("gospel"))

    @Test fun verseTemplateMakesFrontmatterAndLink() {
        val n = TemplateNote.build(TemplateNote.VERSE, clip, AppSettings())
        assertEquals("create", n.mode)
        assertTrue(n.markdown, n.markdown.startsWith("---\n"))
        assertTrue(n.markdown, n.markdown.contains("reference: \"John 3:16\""))
        assertTrue(n.markdown, n.markdown.contains("chapter: 3\n"))
        assertTrue(n.markdown, n.markdown.contains("  - \"gospel\""))
        assertTrue(n.markdown, n.markdown.contains("  - \"bible\""))
        assertTrue(n.markdown, n.markdown.contains("For God so loved the world"))
        assertTrue(n.markdown, n.markdown.contains("obsibible://ref/JHN.3.16"))
    }

    @Test fun autoTagsJoinTheTagsProperty() {
        val n = TemplateNote.build(TemplateNote.VERSE, clip, AppSettings(autoTags = listOf("#study")))
        assertTrue(n.markdown, n.markdown.contains("  - \"study\""))
    }

    @Test fun runningTemplateAppendsBodyOnly() {
        val n = TemplateNote.build(TemplateNote.RUNNING, clip, AppSettings())
        assertEquals("append", n.mode)
        assertTrue(n.append)
        assertEquals("Bible highlights", n.name)
        assertTrue(n.body, n.body.startsWith("## John 3:16 · BSB"))
    }

    @Test fun askAnswerWinsOverTemplateValue() {
        val t = ClipTemplate("t", "T", properties = listOf(TemplateProperty("p1", "mood", "", "text", ask = true)))
        val n = TemplateNote.build(t, clip, AppSettings(), mapOf("p1" to "glad"))
        assertTrue(n.markdown, n.markdown.contains("mood: \"glad\""))
    }

    @Test fun linksGoAtTheEndWhenTemplateHasNoPlace() {
        val t = ClipTemplate("t", "T", noteContentFormat = "{{text}}")
        val n = TemplateNote.build(t, clip.copy(links = listOf("[[Grace]]")), AppSettings())
        assertTrue(n.markdown, n.markdown.trimEnd().endsWith("[[Grace]]"))
    }

    @Test fun seededAddsBuiltInsOnce() {
        val s = TemplateNote.seeded(AppSettings(templates = emptyList()))
        assertEquals(listOf(TemplateNote.VERSE_ID, TemplateNote.RUNNING_ID), s.templates.map { it.id })
        assertEquals(TemplateNote.VERSE_ID, s.selectedTemplateId)
        assertEquals(s, TemplateNote.seeded(s))
    }

    @Test fun seededKeepsAnOldCustomTemplate() {
        val s = TemplateNote.seeded(AppSettings(templates = emptyList(), clipTemplate = "{{text}}"))
        assertEquals("my-template", s.selectedTemplateId)
        assertEquals("{{text}}", s.templates.last().noteContentFormat)
    }

    @Test fun vaultNoteGuessesBibleFields() {
        val note = "---\nbook:\nverses:\ncreated:\nmood:\ntags:\n  - study\n---\nMy thoughts\n"
        val t = VaultTemplate.read(note, "Templates/Bible study.md", emptyMap())
        val by = t.properties.associateBy { it.name }
        assertEquals("{{book}}", by["book"]!!.value)
        assertEquals("{{verses}}", by["verses"]!!.value)
        assertEquals("{{date}}", by["created"]!!.value)
        assertTrue(by["mood"]!!.ask)
        assertFalse(by["tags"]!!.ask)
        assertEquals("Bible study", t.name)
        assertTrue(t.noteContentFormat, t.noteContentFormat.contains("{{content}}"))
    }
}
