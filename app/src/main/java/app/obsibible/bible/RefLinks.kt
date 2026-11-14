package app.obsibible.bible

import java.net.URI
import java.net.URLDecoder

/**
 * Finds the Bible place in a shared link or text: bible.com (YouVersion), Bible Gateway, esv.org,
 * Bible Hub, Blue Letter Bible, our own obsibible:// links, or plain "John 3:16".
 */
object RefLinks {
    private val keyForm = Regex("""^([1-3]?[A-Za-z]{2,3})\.(\d+)(?:\.(\d+)(?:-(\d+))?)?(?:\.[A-Za-z0-9]+)?$""")
    private val link = Regex("""(?:https?|obsibible)://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val plain = Regex("""((?:[1-3]\s?)?[A-Za-z][A-Za-z.]{1,20}\s?\d{1,3}(?::\d{1,3}(?:\s?[-–]\s?\d{1,3})?)?)""")

    fun find(text: String): Ref? {
        val l = link.find(text)?.value
        if (l != null) fromLink(l)?.let { return it }
        return plain.findAll(text).firstNotNullOfOrNull { m -> Ref.parse(m.value)?.takeIf { it.chapterGiven }?.ref }
    }

    fun fromLink(url: String): Ref? {
        // java.net.URI, not android.net.Uri, so the unit tests can run it.
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val segs = (u.rawPath ?: "").split('/').filter { it.isNotBlank() }.map { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }
        if (u.scheme.equals("obsibible", true)) return (segs.lastOrNull() ?: u.host)?.let { Ref.fromKey(it) }
        val host = u.host.orEmpty().lowercase().removePrefix("www.")
        fun param(name: String) = u.rawQuery?.split('&')?.firstOrNull { it.substringBefore('=') == name }
            ?.substringAfter('=')?.let { URLDecoder.decode(it, "UTF-8") }
        return when {
            // bible.com/bible/59/JHN.3.16.ESV and bible.com/bible/59/JHN.3.ESV
            host.endsWith("bible.com") -> segs.firstNotNullOfOrNull { key(it) }
            // biblegateway.com/passage/?search=John+3%3A16&version=ESV
            host.endsWith("biblegateway.com") -> param("search")?.let { typed(it) }
            // blueletterbible.org/kjv/jhn/3/16/
            host.endsWith("blueletterbible.org") && segs.size >= 3 -> typed(segs.drop(1).take(3).joinToString(" "))
            // biblehub.com/john/3-16.htm
            host.endsWith("biblehub.com") && segs.size >= 2 -> typed(segs[segs.size - 2] + " " + segs.last().substringBefore('.').replace('-', ':'))
            // esv.org/John+3:16/ and anything else: try the path as typed words
            else -> typed(segs.joinToString(" ").replace('+', ' ')) ?: segs.firstNotNullOfOrNull { key(it) }
        }
    }

    private fun key(seg: String): Ref? {
        val m = keyForm.find(seg) ?: return null
        val k = buildString {
            append(m.groupValues[1]).append('.').append(m.groupValues[2])
            if (m.groupValues[3].isNotEmpty()) append('.').append(m.groupValues[3])
            if (m.groupValues[4].isNotEmpty()) append('-').append(m.groupValues[4])
        }
        return Ref.fromKey(k.uppercase())
    }

    private fun typed(s: String): Ref? = Ref.parse(s.replace('+', ' ').trim())?.takeIf { it.chapterGiven }?.ref
}
