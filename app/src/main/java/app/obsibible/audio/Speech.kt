package app.obsibible.audio

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A voice the user can pick. [offline] voices work with no internet. */
data class VoiceInfo(val id: String, val name: String, val detail: String, val offline: Boolean)

/**
 * The phone's own text-to-speech. Each verse is spoken into a WAV file, so the player can
 * pause, seek and change speed like any recording. The phone reports when each word starts,
 * which drives the word-by-word highlight.
 */
class PhoneSpeech(context: Context) {
    private val ready = CountDownLatch(1)
    @Volatile private var ok = false
    private val lock = Any()

    private class Job {
        val done = CountDownLatch(1)
        @Volatile var rate = 0
        @Volatile var failed = false
        val words = java.util.Collections.synchronizedList(ArrayList<Triple<Int, Int, Int>>())
    }

    private val jobs = ConcurrentHashMap<String, Job>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ok = status == TextToSpeech.SUCCESS
        ready.countDown()
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { jobs[id]?.done?.countDown() }
            @Deprecated("old API") override fun onError(id: String) { jobs[id]?.apply { failed = true; done.countDown() } }
            override fun onError(id: String, errorCode: Int) { jobs[id]?.apply { failed = true; done.countDown() } }
            override fun onBeginSynthesis(id: String, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                jobs[id]?.rate = sampleRateInHz
            }
            override fun onRangeStart(id: String, start: Int, end: Int, frame: Int) {
                jobs[id]?.words?.add(Triple(start, end, frame))
            }
        })
    }

    private fun awaitReady(): Boolean {
        ready.await(8, TimeUnit.SECONDS)
        return ok
    }

    /** English voices on this phone, best first. */
    fun voices(language: String = "en"): List<VoiceInfo> {
        if (!awaitReady()) return emptyList()
        val all = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet())
        return all.filter { it.locale.language == language && !it.features.orEmpty().contains("notInstalled") }
            .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { -it.quality }, { it.locale.toString() }, { it.name }))
            .map { v ->
                val region = v.locale.displayCountry.ifBlank { v.locale.displayLanguage }
                VoiceInfo(v.name, friendly(v.name, region), region + if (v.isNetworkConnectionRequired) " · needs internet" else " · offline", !v.isNetworkConnectionRequired)
            }
    }

    private fun friendly(name: String, region: String): String {
        // Google names look like en-us-x-iol-local. Keep the short voice code so they can be told apart.
        val code = Regex("-x-([a-z]+)").find(name)?.groupValues?.get(1)
        return if (code != null) "$region ${code.uppercase()}" else name
    }

    /**
     * Speaks [text] into [out] (WAV). Returns the word times, or an estimate when the phone
     * gives no word events. Blocks until done, so call it off the main thread.
     */
    fun synth(text: String, voiceId: String, out: File): List<WordTime> {
        if (!awaitReady()) throw IOException("The phone voice is not ready.")
        val job = Job()
        val id = UUID.randomUUID().toString()
        synchronized(lock) {
            val voice = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet()).let { vs ->
                vs.firstOrNull { it.name == voiceId } ?: vs.filter { it.locale.language == "en" && !it.isNetworkConnectionRequired }.maxByOrNull { it.quality }
            }
            if (voice != null) tts.voice = voice else tts.language = Locale.US
            tts.setSpeechRate(1f)
            jobs[id] = job
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            val r = tts.synthesizeToFile(text, Bundle(), tmp, id)
            if (r != TextToSpeech.SUCCESS) { jobs.remove(id); throw IOException("The phone voice could not start.") }
            val finished = job.done.await(90, TimeUnit.SECONDS)
            jobs.remove(id)
            if (!finished || job.failed || !tmp.exists() || tmp.length() < 100) { tmp.delete(); throw IOException("The phone voice failed.") }
            tmp.renameTo(out)
        }
        val header = Wav.read(out)
        val rate = if (job.rate > 0) job.rate else header?.rate ?: 0
        val words = job.words.toList()
        return if (rate > 0 && words.size >= 2) {
            words.mapNotNull { t -> wordTime(t, text.length, rate) }.sortedBy { it.at }.ifEmpty { Timings.estimateWords(text, header?.seconds ?: 0.0) }
        } else {
            Timings.estimateWords(text, header?.seconds ?: 0.0)
        }
    }

    /** Says a short sample out loud, for the voice picker. */
    fun preview(voiceId: String, text: String) {
        Thread {
            if (!awaitReady()) return@Thread
            synchronized(lock) {
                tts.voices.orEmpty().firstOrNull { it.name == voiceId }?.let { tts.voice = it }
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
            }
        }.start()
    }

    fun stopPreview() { runCatching { tts.stop() } }
}

/**
 * Turns one word event into a word time. The docs give (start, end, frame), but Google's engine
 * was seen sending (frame, start, end). The two numbers that make a range inside the text are
 * the word; the other one is the audio frame.
 */
fun wordTime(t: Triple<Int, Int, Int>, textLength: Int, rate: Int): WordTime? {
    val (a, b, c) = t
    fun range(s: Int, e: Int) = s in 0 until e && e <= textLength
    return when {
        range(b, c) && !range(a, b) -> WordTime(a.toDouble() / rate, b, c)
        range(a, b) && !range(b, c) -> WordTime(c.toDouble() / rate, a, b)
        range(a, b) -> WordTime(c.toDouble() / rate, a, b)
        range(b, c) -> WordTime(a.toDouble() / rate, b, c)
        else -> null
    }
}

/** Reads the size facts out of a WAV header. */
object Wav {
    data class Header(val rate: Int, val channels: Int, val bits: Int, val seconds: Double)

    fun read(f: File): Header? = runCatching {
        RandomAccessFile(f, "r").use { r ->
            val b = ByteArray(44)
            r.readFully(b)
            if (String(b, 0, 4) != "RIFF") return null
            fun le16(i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
            fun le32(i: Int) = le16(i) or (le16(i + 2) shl 16)
            val ch = le16(22).coerceAtLeast(1)
            val rate = le32(24)
            val bits = le16(34).coerceAtLeast(8)
            val bytes = (f.length() - 44).coerceAtLeast(0)
            Header(rate, ch, bits, if (rate > 0) bytes.toDouble() / (rate * ch * (bits / 8)) else 0.0)
        }
    }.getOrNull()
}

/**
 * Microsoft's neural voices through the official Azure Speech service. The user brings their own
 * key and region (the free tier covers a lot of listening). The service gives no word times here,
 * so words are spread over the verse by length.
 */
object CloudSpeech {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private val json = Json { ignoreUnknownKeys = true }

    fun voices(key: String, region: String): List<VoiceInfo> {
        val req = Request.Builder().url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/voices/list")
            .header("Ocp-Apim-Subscription-Key", key.trim()).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("The Microsoft key or region is wrong.")
            if (!r.isSuccessful) throw IOException("Microsoft answered ${r.code}.")
            val arr = json.parseToJsonElement(r.body!!.string()) as? JsonArray ?: return emptyList()
            return arr.mapNotNull { it as? JsonObject }.filter { (it["Locale"] as? JsonPrimitive)?.contentOrNull?.startsWith("en-") == true }
                .map { o ->
                    fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    VoiceInfo(s("ShortName"), s("DisplayName"), s("LocaleName").ifBlank { s("Locale") } + " · " + s("Gender"), false)
                }.sortedWith(compareBy({ !it.detail.contains("United States") }, { it.name }))
        }
    }

    fun synth(key: String, region: String, voice: String, text: String, out: File) {
        val lang = voice.split('-').take(2).joinToString("-").ifBlank { "en-US" }
        val safe = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val ssml = "<speak version='1.0' xml:lang='$lang'><voice name='$voice'>$safe</voice></speak>"
        val req = Request.Builder().url("https://${region.trim()}.tts.speech.microsoft.com/cognitiveservices/v1")
            .header("Ocp-Apim-Subscription-Key", key.trim())
            .header("X-Microsoft-OutputFormat", "audio-24khz-48kbitrate-mono-mp3")
            .header("User-Agent", "ObsiBible")
            .post(ssml.toRequestBody("application/ssml+xml".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (r.code == 401) throw IOException("The Microsoft key or region is wrong.")
            if (r.code == 429) throw IOException("Microsoft says too many requests. Try again soon.")
            if (!r.isSuccessful) throw IOException("Microsoft answered ${r.code}.")
            out.parentFile?.mkdirs()
            val tmp = File(out.path + ".part")
            tmp.outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
            tmp.renameTo(out)
        }
    }
}
