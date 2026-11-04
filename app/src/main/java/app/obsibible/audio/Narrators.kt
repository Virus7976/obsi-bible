package app.obsibible.audio

import app.obsibible.bible.Ref

/**
 * A recorded reading of one Bible.
 *
 * BSB: the narrations are dedicated to the public domain (CC0) and served by openbible.com as one
 * MP3 per chapter. Verse start times come from the timing files that Bible Hub's own player reads.
 *
 * ESV: Crossway's own recording, streamed through api.esv.org with the user's key. Crossway does
 * not allow it to be kept, so it is never cached. It has no timing file; verse times are estimated.
 */
data class Narrator(
    val id: String,
    val name: String,
    private val suffix: String,
    val bible: String = "BSB",
    val credit: String = "Recorded · public domain",
) {
    val streamOnly get() = bible == "ESV"

    fun audioUrl(ref: Ref): String {
        if (bible == "ESV") return "esv://audio/${ref.chapterRef.key}"
        val b = ref.bookInfo
        return "https://openbible.com/audio/$id/BSB_%02d_%s_%03d%s.mp3".format(b.number, b.audioCode, ref.chapter, suffix)
    }

    /** Blank when there is no timing file. */
    fun timingUrl(ref: Ref): String {
        if (bible != "BSB") return ""
        val key = if (ref.book == "SNG") "songofsolomon" else ref.bookInfo.name.lowercase().replace(" ", "")
        return "https://biblehub.com/audio/player/timing_$id/${key}_${ref.chapter}.json"
    }
}

object Narrators {
    val all = listOf(
        Narrator("souer", "Bob Souer", ""),
        Narrator("hays", "Barry Hays", "_H"),
        Narrator("gilbert", "Jordan Gilbert", "_G"),
        Narrator("esv", "Crossway recording", "", bible = "ESV", credit = "ESV audio · Crossway · streams only"),
    )

    fun byId(id: String): Narrator? = all.firstOrNull { it.id == id }

    /** The recordings made for [bible]. Other Bibles use the phone or Microsoft voices. */
    fun forBible(bible: String): List<Narrator> = all.filter { it.bible == bible }
}
