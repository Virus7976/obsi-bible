package app.obsibible.bible

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A Bible read over the internet with the user's own key. */
data class RemoteBible(val id: String, val abbreviation: String, val name: String, val language: String, val copyright: String = "")

internal val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()

private fun get(url: String, header: Pair<String, String>): JsonObject {
    val req = Request.Builder().url(url).header(header.first, header.second).header("User-Agent", "ObsiBible").build()
    http.newCall(req).execute().use { r ->
        if (r.code == 401 || r.code == 403) throw IOException("The key was not accepted. Copy it again and paste it.")
        if (r.code == 429) throw IOException("Too many requests today. Try again later.")
        if (!r.isSuccessful) throw IOException("The Bible service answered ${r.code}.")
        return json.parseToJsonElement(r.body!!.string()) as JsonObject
    }
}

/**
 * The ESV from Crossway's official API (api.esv.org). Each user types their own free key. The key
 * may not be shared, so it never ships in the app. Crossway allows at most 500 verses, and no more
 * than half of a book, to be kept on the phone; [Bibles] enforces that.
 */
object EsvApi {
    const val ID = "ESV"
    const val COPYRIGHT = "Scripture quotations are from the ESV® Bible (The Holy Bible, English Standard Version®), © 2001 by Crossway, a publishing ministry of Good News Publishers. Used by permission. All rights reserved."
    val info = BibleInfo(ID, "ESV", "English Standard Version", COPYRIGHT)

    private fun auth(key: String) = "Authorization" to "Token ${key.trim()}"

    fun chapter(key: String, ref: Ref): Chapter {
        val url = "https://api.esv.org/v3/passage/text/".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${ref.bookInfo.name} ${ref.chapter}")
            .addQueryParameter("include-passage-references", "false")
            .addQueryParameter("include-verse-numbers", "true")
            .addQueryParameter("include-first-verse-numbers", "true")
            .addQueryParameter("include-footnotes", "false")
            .addQueryParameter("include-headings", "true")
            .addQueryParameter("include-short-copyright", "false")
            .addQueryParameter("include-selahs", "true")
            .addQueryParameter("indent-paragraphs", "2")
            .addQueryParameter("indent-poetry", "true")
            .addQueryParameter("indent-poetry-lines", "4")
            .addQueryParameter("indent-declares", "4")
            .addQueryParameter("line-length", "0")
            .build().toString()
        val o = get(url, auth(key))
        val text = (o["passages"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw IOException("The ESV has no text for ${ref.label}.")
        return parse(ref, text)
    }

    /**
     * Turns the ESV plain-text format into a chapter. Headings stand alone on a line, a paragraph
     * starts with two spaces, poetry lines with four or more, and verse numbers look like [16].
     */
    fun parse(ref: Ref, text: String): Chapter {
        val blocks = ArrayList<Block>()
        for (raw in text.replace("\r", "").split('\n')) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            val hasVerse = Regex("\\[\\d+\\]").containsMatchIn(line)
            val kind = when {
                indent == 0 && !hasVerse -> "s1"
                indent >= 8 -> "q2"
                indent >= 4 -> "q1"
                else -> "p"
            }
            blocks += Block(kind, if (kind == "s1") listOf(Seg(Seg.TEXT, line)) else segs(line))
        }
        return Chapter(ref.book, ref.chapter, blocks)
    }

    /** Splits "[16] For God so loved [17] For God did not" into verse marks and text. */
    internal fun segs(line: String): List<Seg> {
        val out = ArrayList<Seg>()
        var last = 0
        for (m in Regex("\\[(\\d+)\\]\\s*").findAll(line)) {
            if (m.range.first > last) out += Seg(Seg.TEXT, line.substring(last, m.range.first))
            out += Seg(Seg.VERSE, m.groupValues[1])
            last = m.range.last + 1
        }
        if (last < line.length) out += Seg(Seg.TEXT, line.substring(last))
        return out
    }

    fun search(key: String, query: String): Found {
        val url = "https://api.esv.org/v3/passage/search/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("page-size", "100").build().toString()
        val o = get(url, auth(key))
        val hits = (o["results"] as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            val ref = Ref.parse(r.str("reference"))?.ref ?: return@mapNotNull null
            Hit(ref, r.str("content"))
        }
        val total = (o["total_results"] as? JsonPrimitive)?.intOrNull ?: hits.size
        return Found(total, hits)
    }

    /** Where the chapter's MP3 is. The API answers with a redirect to the file. */
    fun audioLocation(key: String, ref: Ref): String {
        val url = "https://api.esv.org/v3/passage/audio/".toHttpUrl().newBuilder()
            .addQueryParameter("q", "${ref.bookInfo.name} ${ref.chapter}").build()
        val client = http.newBuilder().followRedirects(false).build()
        val req = Request.Builder().url(url).header("Authorization", "Token ${key.trim()}").header("User-Agent", "ObsiBible").build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401 || r.code == 403) throw IOException("The ESV key was not accepted. Copy it again and paste it.")
            return r.header("Location") ?: if (r.isSuccessful) url.toString() else throw IOException("ESV audio answered ${r.code}.")
        }
    }
}

/**
 * Bibles from API.Bible (scripture.api.bible) with the user's own key. Which Bibles a key can read
 * depends on the key's licence; The Message shows here only when the key is allowed to use it.
 */
object ApiBible {
    const val PREFIX = "AB:"
    private const val BASE = "https://rest.api.bible/v1"

    private fun auth(key: String) = "api-key" to key.trim()

    fun bibles(key: String): List<RemoteBible> {
        val o = get("$BASE/bibles", auth(key))
        return (o["data"] as? JsonArray).orEmpty().mapNotNull { e ->
            val b = e as? JsonObject ?: return@mapNotNull null
            val lang = (b["language"] as? JsonObject)?.str("name").orEmpty()
            RemoteBible(b.str("id"), b.str("abbreviationLocal").ifBlank { b.str("abbreviation") }, b.str("nameLocal").ifBlank { b.str("name") }, lang)
        }.sortedWith(compareBy({ it.language != "English" }, { it.abbreviation }))
    }

    fun copyright(key: String, id: String): String =
        runCatching { (get("$BASE/bibles/$id", auth(key))["data"] as? JsonObject)?.str("copyright").orEmpty() }.getOrDefault("")
            .replace(Regex("<[^>]+>"), "").trim()

    fun chapter(key: String, bibleId: String, ref: Ref): Chapter {
        val url = "$BASE/bibles/$bibleId/chapters/${ref.book}.${ref.chapter}".toHttpUrl().newBuilder()
            .addQueryParameter("content-type", "text")
            .addQueryParameter("include-notes", "false")
            .addQueryParameter("include-titles", "true")
            .addQueryParameter("include-chapter-numbers", "false")
            .addQueryParameter("include-verse-numbers", "true")
            .addQueryParameter("include-verse-spans", "false")
            .build().toString()
        val data = get(url, auth(key))["data"] as? JsonObject ?: throw IOException("No text for ${ref.label}.")
        return parse(ref, data.str("content"))
    }

    /** API.Bible text: one paragraph per line, verse numbers as [16], titles on lines of their own. */
    fun parse(ref: Ref, text: String): Chapter {
        val blocks = ArrayList<Block>()
        for (raw in text.replace("\r", "").split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val hasVerse = Regex("\\[\\d+\\]").containsMatchIn(line)
            if (!hasVerse && line.length < 90 && !line.endsWith('.') && !line.endsWith(',') && blocks.lastOrNull()?.kind != "s1")
                blocks += Block("s1", listOf(Seg(Seg.TEXT, line)))
            else blocks += Block("p", EsvApi.segs(line))
        }
        return Chapter(ref.book, ref.chapter, blocks)
    }

    fun search(key: String, bibleId: String, query: String): Found {
        val url = "$BASE/bibles/$bibleId/search".toHttpUrl().newBuilder()
            .addQueryParameter("query", query).addQueryParameter("limit", "100").build().toString()
        val data = get(url, auth(key))["data"] as? JsonObject ?: return Found(0, emptyList())
        val hits = (data["verses"] as? JsonArray).orEmpty().mapNotNull { e ->
            val v = e as? JsonObject ?: return@mapNotNull null
            val ref = Ref.fromKey(v.str("id")) ?: return@mapNotNull null
            Hit(ref, v.str("text"))
        }
        return Found((data["total"] as? JsonPrimitive)?.intOrNull ?: hits.size, hits)
    }
}
