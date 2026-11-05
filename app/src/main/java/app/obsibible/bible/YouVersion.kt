package app.obsibible.bible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException

/**
 * Bibles from the YouVersion Platform (api.youversion.com) with the user's own free app key.
 * Which Bibles a key may read depends on its licence; The Message shows only when it is allowed.
 */
object YouVersion {
    const val PREFIX = "YV:"
    private const val BASE = "https://api.youversion.com/v1"
    private val json = Json { ignoreUnknownKeys = true }

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun call(url: String, key: String): String {
        val req = Request.Builder().url(url).header("X-YVP-App-Key", key.trim()).header("User-Agent", "ObsiBible").build()
        val call = try { http.newCall(req).execute() } catch (e: java.net.UnknownHostException) {
            throw IOException("No internet, or a blocker on this phone stopped it. Check the connection and try again.")
        }
        call.use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("The YouVersion key was not accepted. Copy it again and paste it.")
            if (r.code == 404) throw NotFound()
            if (r.code == 429) throw IOException("Too many requests today. Try again later.")
            if (!r.isSuccessful) throw IOException("YouVersion answered ${r.code}.")
            return r.body!!.string()
        }
    }

    class NotFound : IOException("Not in this Bible.")

    /** The Bibles this key may read, English first. */
    fun bibles(key: String): List<RemoteBible> {
        val out = ArrayList<RemoteBible>()
        var token = ""
        repeat(10) {
            val b = "$BASE/bibles".toHttpUrl().newBuilder().addQueryParameter("language_ranges[]", "*").addQueryParameter("page_size", "99")
            if (token.isNotBlank()) b.addQueryParameter("page_token", token)
            val o = json.parseToJsonElement(call(b.build().toString(), key)) as JsonObject
            (o["data"] as? JsonArray).orEmpty().forEach { e ->
                val v = e as? JsonObject ?: return@forEach
                val title = str(v, "localized_title").ifBlank { str(v, "title") }
                val abbr = str(v, "localized_abbreviation").ifBlank { str(v, "abbreviation") }
                out += RemoteBible(str(v, "id"), abbr, title, str(v, "language_tag"), plain(str(v, "copyright")))
            }
            token = (o["next_page_token"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            if (token.isBlank()) return out.sortedWith(compareBy({ !it.language.startsWith("en") }, { it.abbreviation }))
        }
        return out.sortedWith(compareBy({ !it.language.startsWith("en") }, { it.abbreviation }))
    }

    private fun passage(key: String, id: String, usfm: String, html: Boolean): String {
        val b = "$BASE/bibles/$id/passages/$usfm".toHttpUrl().newBuilder().addQueryParameter("format", if (html) "html" else "text")
        if (html) b.addQueryParameter("include_headings", "true").addQueryParameter("include_notes", "false")
        val o = json.parseToJsonElement(call(b.build().toString(), key)) as JsonObject
        return str(o, "content")
    }

    fun chapter(key: String, id: String, ref: Ref): Chapter {
        val c = ref.chapterRef
        val ch = parseHtml(c, passage(key, id, "${c.book}.${c.chapter}", html = true))
        if (ch.verses().size > 1) return ch
        // No verse marks in the chapter text: read it verse by verse instead.
        val blocks = ArrayList<Block>()
        for (v in 1..176) {
            val t = try { passage(key, id, "${c.book}.${c.chapter}.$v", html = false) } catch (e: NotFound) { break }
            if (t.isBlank()) break
            blocks += Block("p", listOf(Seg(Seg.VERSE, v.toString()), Seg(Seg.TEXT, t.trim())))
        }
        if (blocks.isEmpty()) throw IOException("No text for ${c.label}.")
        return Chapter(c.book, c.chapter, blocks)
    }

    // ---------- HTML to a chapter ----------

    private val tag = Regex("<(/?)([a-zA-Z0-9]+)([^>]*)>|([^<]+)")
    private val classAttr = Regex("class\\s*=\\s*\"([^\"]*)\"")
    private val usfmAttr = Regex("data-usfm\\s*=\\s*\"([^\"]*)\"")
    private val voidTags = setOf("br", "img", "hr", "meta", "input")

    fun plain(s: String) = decode(s.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    fun decode(s: String) = s.replace("&nbsp;", " ").replace("&#160;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&#8217;", "\u2019").replace("&#8220;", "\u201C").replace("&#8221;", "\u201D")

    private fun kindOf(tagName: String, cls: String): String? {
        val c = cls.split(Regex("\\s+")).toSet()
        return when {
            tagName.matches(Regex("h[1-6]")) -> "s1"
            c.any { it in setOf("s", "s1", "s2", "s3", "ms", "ms1", "mr", "d", "heading", "title", "sp") } -> "s1"
            c.any { it in setOf("q2", "q3", "qm2") } -> "q2"
            c.any { it in setOf("q", "q1", "qm", "qm1", "qc", "qr") } -> "q1"
            tagName == "p" || tagName == "div" -> "p"
            else -> null
        }
    }

    /**
     * Reads the HTML Bible apps use: blocks such as <div class="p">, headings such as
     * <div class="s1">, and verses as <span class="verse v16" data-usfm="JHN.3.16"> with the number in
     * a <span class="label">. Footnotes and cross references are left out.
     */
    fun parseHtml(ref: Ref, html: String): Chapter {
        data class Open(val name: String, val skip: Boolean, val label: Boolean, val block: Boolean)
        val stack = ArrayList<Open>()
        val blocks = ArrayList<Block>()
        var kind = "p"
        var segs = ArrayList<Seg>()
        var verse = 0
        val hasUsfm = usfmAttr.containsMatchIn(html)
        fun flush() {
            val clean = segs.filter { it.t != Seg.TEXT || it.s.isNotBlank() }
            if (clean.isNotEmpty()) {
                val out = clean.toMutableList()
                val last = out.lastIndex
                if (out[last].t == Seg.TEXT) out[last] = Seg(Seg.TEXT, out[last].s.trimEnd())
                blocks += Block(kind, out)
            }
            segs = ArrayList()
        }
        fun text(t: String) {
            val s = decode(t).replace(Regex("\\s+"), " ")
            if (s.isEmpty()) return
            val prev = segs.lastOrNull()
            if (prev != null && prev.t == Seg.TEXT) segs[segs.lastIndex] = Seg(Seg.TEXT, prev.s + s)
            else segs += Seg(Seg.TEXT, if (segs.isEmpty() || prev?.t == Seg.VERSE) s.trimStart() else s)
        }
        for (m in tag.findAll(html)) {
            val raw = m.groupValues[4]
            if (raw.isNotEmpty()) {
                val skip = stack.any { it.skip }
                val label = stack.any { it.label }
                if (skip) continue
                if (label) {
                    // Without data-usfm, the label itself is the verse mark.
                    // "1-4" is a group of verses printed as one (The Message does this).
                    val g = Regex("^(\\d+)(?:\\s*[-\u2013]\\s*(\\d+))?").find(raw.trim())
                    if (!hasUsfm && g != null && kind != "s1") {
                        val v = g.groupValues[1].toInt()
                        val z = g.groupValues[2].toIntOrNull() ?: v
                        if (v != verse) { verse = v; segs += Seg(Seg.VERSE, if (z > v) "$v-$z" else "$v") }
                    }
                    continue
                }
                text(raw)
                continue
            }
            val closing = m.groupValues[1] == "/"
            val name = m.groupValues[2].lowercase()
            val attrs = m.groupValues[3]
            if (closing) {
                val i = stack.indexOfLast { it.name == name }
                if (i >= 0) {
                    val wasBlock = stack[i].block
                    while (stack.size > i) stack.removeAt(stack.lastIndex)
                    if (wasBlock) { flush(); kind = "p" }
                }
                continue
            }
            val cls = classAttr.find(attrs)?.groupValues?.get(1).orEmpty()
            val c = cls.split(Regex("\\s+")).toSet()
            val skip = c.any { it == "note" || it == "f" || it == "x" || it.contains("footnote") || it.contains("crossref") || it == "ft" || it == "xt" }
            val label = !skip && (c.contains("label") || c.contains("vlbl") || c.contains("verse-num") || name == "sup")
            val k = if (name == "div" || name == "p" || name.matches(Regex("h[1-6]"))) kindOf(name, cls) else if (name == "span") kindOf(name, cls)?.takeIf { it == "s1" } else null
            val block = k != null && (name != "span")
            if (block) { flush(); kind = k!! }
            if (name == "span" && k == "s1") { flush(); kind = "s1" }
            usfmAttr.find(attrs)?.groupValues?.get(1)?.split('+')?.mapNotNull { it.split('.').getOrNull(2)?.toIntOrNull() }?.takeIf { it.isNotEmpty() }?.let { vs ->
                // MRK.3.1+MRK.3.2+MRK.3.3 is one group of verses printed as one.
                val v = vs.first()
                val z = vs.max()
                if (v != verse && kind != "s1") { verse = v; segs += Seg(Seg.VERSE, if (z > v) "$v-$z" else "$v") }
            }
            if (name !in voidTags && !attrs.trimEnd().endsWith("/")) stack += Open(name, skip, label, block || (name == "span" && k == "s1"))
            if (name == "br") text(" ")
        }
        flush()
        return Chapter(ref.book, ref.chapter, blocks)
    }
}
