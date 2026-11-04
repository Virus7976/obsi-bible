package app.obsibible

import app.obsibible.data.Mark
import app.obsibible.obsidian.HighlightsFile
import app.obsibible.obsidian.VaultTags
import app.obsibible.ui.TagPool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultFilesTest {
    @Test fun highlightsFileListsMarksInOrder() {
        val a = Mark(bible = "ESV", book = "JHN", chapter = 3, v1 = 16, v2 = 16, color = 0, quote = "For God so loved", note = "Love", tags = listOf("love"))
        val b = Mark(bible = "BSB", book = "JHN", chapter = 1, v1 = 1, v2 = 2, color = -1, note = "Word", quote = "In the beginning")
        val md = HighlightsFile.build("John", listOf(a, b)) { it }
        assertTrue(md.startsWith("---\nbook: \"John\"\nhighlights: 2\n"))
        assertTrue(md.contains("  - love\n"))
        assertTrue(md.indexOf("## John 1") < md.indexOf("## John 3"))
        assertTrue(md.contains("### John 3:16 · ESV · "))
        assertTrue(md.contains("> For God so loved\n> [Open in Obsi Bible](obsibible://ref/JHN.3.16)"))
        assertTrue(md.contains("\nLove\n\n#love\n"))
        assertTrue(md.contains("Made by Obsi Bible"))
    }

    @Test fun vaultTagsFromFrontmatterAndBody() {
        val note = "---\ntags: [faith, \"hope\"]\n---\nText with #prayer and #2024 and a#notatag.\n```\n#incode\n```\n"
        assertEquals(setOf("faith", "hope", "prayer"), VaultTags.find(note))
        val list = "---\ntags:\n  - study\n  - grace\n---\nbody"
        assertEquals(setOf("study", "grace"), VaultTags.find(list))
        assertFalse(VaultTags.find("---\ntags: [{{tags}}]\n---\n").any { it.contains("{") })
    }

    @Test fun tagSuggestionsMixAppAndVault() {
        val pool = TagPool.build(listOf("love", "faith"), mapOf("Faith" to 9, "prayer" to 5, "promises" to 2))
        assertEquals(listOf("love", "faith", "prayer", "promises"), pool)
        assertEquals(listOf("prayer", "promises"), TagPool.suggest(pool, "pr", listOf("love")))
        assertEquals(listOf("faith", "prayer"), TagPool.suggest(pool, "", listOf("love", "promises")))
    }
}
