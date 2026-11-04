package app.obsibible.audio

import app.obsibible.bible.Ref

/**
 * Recorded BSB audio. The narrations are dedicated to the public domain (CC0) and served by
 * openbible.com as one MP3 per chapter. Verse start times come from the timing files that
 * Bible Hub's own player reads; without one, the app estimates them.
 */
data class Narrator(val id: String, val name: String, private val suffix: String) {
    fun audioUrl(ref: Ref): String {
        val b = ref.bookInfo
        return "https://openbible.com/audio/$id/BSB_%02d_%s_%03d%s.mp3".format(b.number, b.audioCode, ref.chapter, suffix)
    }

    fun timingUrl(ref: Ref): String {
        val key = if (ref.book == "SNG") "songofsolomon" else ref.bookInfo.name.lowercase().replace(" ", "")
        return "https://biblehub.com/audio/player/timing_$id/${key}_${ref.chapter}.json"
    }
}

object Narrators {
    val all = listOf(
        Narrator("souer", "Bob Souer", ""),
        Narrator("hays", "Barry Hays", "_H"),
        Narrator("gilbert", "Jordan Gilbert", "_G"),
    )

    fun byId(id: String): Narrator? = all.firstOrNull { it.id == id }

    /** Recorded audio exists only for the BSB. */
    fun forBible(bible: String): List<Narrator> = if (bible == "BSB") all else emptyList()
}
