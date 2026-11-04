package app.obsibible.audio

import kotlinx.serialization.Serializable

/** When a verse starts and ends in a chapter recording, in seconds. */
@Serializable
data class VerseTime(val v: Int, val start: Double, val end: Double)

/** When a word starts in one spoken verse: [start]..[end] are character offsets in the verse text. */
@Serializable
data class WordTime(val at: Double, val start: Int, val end: Int)

/** Pure timing maths for follow-along. No Android. */
object Timings {
    /**
     * Guesses verse times from verse lengths when a recording has no timing file.
     * [intro] is the spoken book and chapter name before verse 1.
     */
    fun estimate(verses: List<Pair<Int, String>>, duration: Double, intro: Double = 3.0): List<VerseTime> {
        if (verses.isEmpty() || duration <= 0) return emptyList()
        val start0 = intro.coerceAtMost(duration * 0.1)
        val weights = verses.map { it.second.length.coerceAtLeast(1) + 12.0 } // a short pause per verse
        val total = weights.sum()
        var t = start0
        return verses.mapIndexed { i, (v, _) ->
            val len = (duration - start0) * weights[i] / total
            VerseTime(v, t, t + len).also { t += len }
        }
    }

    /** The verse playing at [t] seconds. Before verse 1 it is verse 1's number. */
    fun verseAt(times: List<VerseTime>, t: Double): Int {
        if (times.isEmpty()) return 0
        var lo = 0
        var hi = times.lastIndex
        if (t < times[0].start) return times[0].v
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (times[mid].start <= t) lo = mid else hi = mid - 1
        }
        return times[lo].v
    }

    fun startOf(times: List<VerseTime>, verse: Int): Double? = times.firstOrNull { it.v == verse }?.start

    /** The word spoken at [t] seconds, or null before the first word. */
    fun wordAt(words: List<WordTime>, t: Double): WordTime? {
        if (words.isEmpty() || t < words[0].at) return null
        var lo = 0
        var hi = words.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (words[mid].at <= t) lo = mid else hi = mid - 1
        }
        return words[lo]
    }

    /**
     * Word times spread over [duration] by length, for a voice that gives no word events.
     * Each word gets time in proportion to its letters plus a little for the gap after it.
     */
    fun estimateWords(text: String, duration: Double): List<WordTime> {
        val spans = Regex("\\S+").findAll(text).map { it.range.first to it.range.last + 1 }.toList()
        if (spans.isEmpty() || duration <= 0) return emptyList()
        val weights = spans.map { (s, e) -> (e - s) + 1.5 + if (text[e - 1] in ",;:.!?") 3.0 else 0.0 }
        val total = weights.sum()
        var t = 0.0
        return spans.mapIndexed { i, (s, e) -> WordTime(t, s, e).also { t += duration * weights[i] / total } }
    }

    fun clock(seconds: Double): String {
        val s = seconds.coerceAtLeast(0.0).toInt()
        return "%02d:%02d".format(s / 60, s % 60)
    }
}
