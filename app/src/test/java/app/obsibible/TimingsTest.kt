package app.obsibible

import app.obsibible.audio.Narrators
import app.obsibible.audio.Timings
import app.obsibible.audio.VerseTime
import app.obsibible.audio.WordTime
import app.obsibible.bible.Ref
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimingsTest {
    private val times = listOf(VerseTime(1, 5.0, 10.0), VerseTime(2, 10.0, 20.0), VerseTime(3, 20.0, 25.0))

    @Test fun findsTheVerseAtATime() {
        assertEquals(1, Timings.verseAt(times, 0.0))
        assertEquals(1, Timings.verseAt(times, 9.99))
        assertEquals(2, Timings.verseAt(times, 10.0))
        assertEquals(3, Timings.verseAt(times, 99.0))
        assertEquals(0, Timings.verseAt(emptyList(), 3.0))
    }

    @Test fun estimateCoversTheWholeRecordingInOrder() {
        val est = Timings.estimate(listOf(1 to "short", 2 to "a much longer verse than the first one", 3 to "mid length verse"), 60.0)
        assertEquals(3.0, est.first().start, 0.001)
        assertEquals(60.0, est.last().end, 0.001)
        assertTrue(est.zipWithNext().all { (a, b) -> a.end == b.start && b.start > a.start })
        assertTrue("longer verses get more time", est[1].end - est[1].start > est[0].end - est[0].start)
    }

    @Test fun wordAtPicksTheLastStartedWord() {
        val w = listOf(WordTime(0.1, 0, 3), WordTime(0.5, 4, 7), WordTime(0.9, 8, 12))
        assertNull(Timings.wordAt(w, 0.05))
        assertEquals(4, Timings.wordAt(w, 0.6)!!.start)
        assertEquals(8, Timings.wordAt(w, 5.0)!!.start)
    }

    @Test fun estimatedWordsCoverEveryWord() {
        val text = "For God so loved the world, that He gave"
        val w = Timings.estimateWords(text, 4.0)
        assertEquals(9, w.size)
        assertEquals("world,", text.substring(w[5].start, w[5].end))
        assertEquals(0.0, w[0].at, 0.0)
        assertTrue(w.zipWithNext().all { (a, b) -> b.at > a.at } && w.last().at < 4.0)
    }

    @Test fun wordEventsWorkInEitherOrder() {
        // seen from Google's engine: (frame, start, end)
        assertEquals(WordTime(7200 / 24000.0, 7, 12), app.obsibible.audio.wordTime(Triple(7200, 7, 12), 40, 24000))
        // the documented order: (start, end, frame)
        assertEquals(WordTime(7200 / 24000.0, 7, 12), app.obsibible.audio.wordTime(Triple(7, 12, 7200), 40, 24000))
        assertNull(app.obsibible.audio.wordTime(Triple(500, 400, 300), 40, 24000))
    }

    @Test fun clockFormat() {
        assertEquals("02:28", Timings.clock(148.4))
        assertEquals("00:00", Timings.clock(-3.0))
    }

    @Test fun narratorFileNames() {
        assertEquals("https://openbible.com/audio/souer/BSB_43_Jhn_003.mp3", Narrators.byId("souer")!!.audioUrl(Ref("JHN", 3)))
        assertEquals("https://openbible.com/audio/hays/BSB_19_Psa_119_H.mp3", Narrators.byId("hays")!!.audioUrl(Ref("PSA", 119)))
        assertEquals("https://biblehub.com/audio/player/timing_souer/songofsolomon_2.json", Narrators.byId("souer")!!.timingUrl(Ref("SNG", 2)))
        assertEquals("https://biblehub.com/audio/player/timing_gilbert/1corinthians_13.json", Narrators.byId("gilbert")!!.timingUrl(Ref("1CO", 13)))
    }
}
