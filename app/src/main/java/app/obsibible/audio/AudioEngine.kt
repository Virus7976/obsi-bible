package app.obsibible.audio

import android.content.ComponentName
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import app.obsibible.ObsiBibleApp
import app.obsibible.bible.Ref
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** What the audio sheet and the reader show. */
data class AudioUi(
    val active: Boolean = false,
    val playing: Boolean = false,
    val loading: Boolean = false,
    val bible: String = "",
    val ref: Ref? = null,
    val verse: Int = 0,
    /** The word being spoken, as character offsets in the verse text. Only for voices. */
    val word: IntRange? = null,
    val position: Double = 0.0,
    val duration: Double = 0.0,
    val verses: List<Int> = emptyList(),
    /** True for a chapter recording (time seek bar); false for a voice (verse seek bar). */
    val recorded: Boolean = false,
    val speed: Float = 1f,
    val sleepMinutes: Int = 0,
    val sleepLeft: Long = 0L,
    val title: String = "",
    val voiceName: String = "",
    val credit: String = "",
    val error: String? = null,
)

/** -1 means "at the end of this chapter". */
const val SLEEP_END_OF_CHAPTER = -1

/**
 * The one audio player. Recordings and voices both go through the same ExoPlayer, so speed,
 * seeking, the lock screen and Bluetooth buttons work the same way for all of them.
 *
 * - Recorded BSB: one MP3 per chapter, cached as it plays, with verse start times.
 * - Phone voice and Microsoft voice: one short file per verse, made just ahead of playback
 *   and kept in a cache, with word times for the word highlight.
 */
@OptIn(UnstableApi::class)
class AudioEngine(private val app: ObsiBibleApp) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    val phone = PhoneSpeech(app)

    private val voiceDir = File(app.cacheDir, "voice").apply { mkdirs() }
    private val timingDir = File(app.filesDir, "timings").apply { mkdirs() }
    private val cache = SimpleCache(File(app.cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(600L * 1024 * 1024), StandaloneDatabaseProvider(app))

    private sealed interface Source {
        data class Recorded(val narrator: Narrator) : Source
        data class Phone(val voice: String) : Source
        data class Cloud(val key: String, val region: String, val voice: String) : Source
    }

    /** One spoken verse waiting in the playlist. */
    private data class Line(val verse: Int, val text: String, val source: Source)

    private val lines = ConcurrentHashMap<String, Line>()
    private val words = ConcurrentHashMap<String, List<WordTime>>()
    private var source: Source? = null
    private var verseTexts: Map<Int, String> = emptyMap()
    private var verseTimes: List<VerseTime> = emptyList()
    private var timesEstimated = false
    private var pendingVerse = 0

    private val _ui = MutableStateFlow(AudioUi(speed = app.settings.value.audioSpeed))
    val ui: StateFlow<AudioUi> = _ui.asStateFlow()

    val player: ExoPlayer = ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(RouterFactory()))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    private var controller: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var sleepJob: Job? = null

    init {
        player.setPlaybackSpeed(app.settings.value.audioSpeed)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _ui.update { it.copy(playing = isPlaying) }
                if (isPlaying) startTicker()
            }

            override fun onPlaybackStateChanged(state: Int) {
                _ui.update { it.copy(loading = state == Player.STATE_BUFFERING) }
                if (state == Player.STATE_READY) onReady()
                if (state == Player.STATE_ENDED) onChapterEnd()
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = tick()

            override fun onPlayerError(error: PlaybackException) = onError(error)
        })
    }

    // ---------- starting ----------

    /** Plays [ref]'s chapter in [bible], from [fromVerse] (0 = the start). */
    fun play(bible: String, ref: Ref, fromVerse: Int = 0) = start(bible, ref, fromVerse, pickSource(bible))

    private fun start(bible: String, ref: Ref, fromVerse: Int, src: Source) {
        val chapter = app.bibles.chapter(bible, ref.chapterRef)
        if (chapter == null) {
            _ui.update { it.copy(error = "This chapter is not on the phone yet.") }
            return
        }
        val verses = chapter.verses()
        verseTexts = verses.toMap()
        source = src
        lines.clear()
        verseTimes = emptyList()
        timesEstimated = false
        pendingVerse = fromVerse
        val chRef = ref.chapterRef
        val meta = MediaMetadata.Builder().setTitle(chRef.label).setArtist(voiceName(src)).setAlbumTitle(bibleName(bible)).build()

        when (src) {
            is Source.Recorded -> {
                val item = MediaItem.Builder().setUri(src.narrator.audioUrl(chRef)).setMediaId(chRef.key).setMediaMetadata(meta).build()
                player.setMediaItem(item)
                loadTimes(src.narrator, chRef)
            }
            else -> {
                val items = verses.map { (v, text) ->
                    val key = "${chRef.key}.$v"
                    lines[key] = Line(v, text, src)
                    MediaItem.Builder().setUri(Uri.parse("voice://line/$key")).setMediaId(key)
                        .setMediaMetadata(meta.buildUpon().setTitle("${chRef.label}:$v").build()).build()
                }
                val start = verses.indexOfFirst { it.first >= fromVerse }.coerceAtLeast(0)
                player.setMediaItems(items, start, 0L)
                pendingVerse = 0
            }
        }
        _ui.value = _ui.value.copy(
            active = true, bible = bible, ref = chRef, verse = if (fromVerse > 0) fromVerse else verses.firstOrNull()?.first ?: 1,
            word = null, position = 0.0, duration = 0.0, verses = verses.map { it.first }, recorded = src is Source.Recorded,
            title = bibleName(bible), voiceName = voiceName(src), credit = credit(src), error = null, loading = true,
        )
        player.prepare()
        player.play()
        connectSession()
    }

    private fun pickSource(bible: String): Source {
        val s = app.settings.value
        val (kind, id) = s.audioSource.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        return when {
            kind == "narrator" && Narrators.forBible(bible).isNotEmpty() -> Source.Recorded(Narrators.byId(id) ?: Narrators.all[0])
            kind == "cloud" && s.cloudKey.isNotBlank() && s.cloudRegion.isNotBlank() -> Source.Cloud(s.cloudKey, s.cloudRegion, id.ifBlank { s.cloudVoice })
            kind == "phone" -> Source.Phone(id)
            Narrators.forBible(bible).isNotEmpty() -> Source.Recorded(Narrators.all[0])
            else -> Source.Phone("")
        }
    }

    /** The voice name and credit that [play] would use, for the sheet before anything plays. */
    fun describe(bible: String): Triple<String, String, String> {
        val src = pickSource(bible)
        return Triple(voiceName(src), credit(src), bibleName(bible))
    }

    private fun bibleName(bible: String) = app.bibles.installed.value.firstOrNull { it.id == bible }?.name ?: bible

    private fun voiceName(src: Source?): String = when (src) {
        is Source.Recorded -> src.narrator.name
        is Source.Phone -> "Phone voice"
        is Source.Cloud -> src.voice.substringAfterLast('-').removeSuffix("Neural").removeSuffix("Multilingual").ifBlank { "Microsoft voice" }
        null -> ""
    }

    private fun credit(src: Source?): String = when (src) {
        is Source.Recorded -> "Recorded · public domain"
        is Source.Phone -> "Works offline"
        is Source.Cloud -> "Uses your Microsoft key"
        null -> ""
    }

    /** Media3 shows the lock screen and notification controls once something is connected. */
    private fun connectSession() {
        if (controller != null) return
        controller = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
    }

    // ---------- controls ----------

    fun toggle() {
        if (!_ui.value.active) return
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0, 0); player.play() }
            player.playbackState == Player.STATE_IDLE -> { player.prepare(); player.play() }
            else -> player.play()
        }
    }

    fun pause() = player.pause()

    fun stop() {
        sleepJob?.cancel()
        player.stop()
        player.clearMediaItems()
        _ui.update { AudioUi(speed = it.speed) }
    }

    fun nextVerse() = stepVerse(+1)
    fun previousVerse() = stepVerse(-1)

    private fun stepVerse(d: Int) {
        val u = _ui.value
        if (!u.active) return
        val i = u.verses.indexOf(u.verse)
        if (d < 0 && u.recorded) {
            // Like a music player: early in a verse goes back one, later restarts this verse.
            val start = Timings.startOf(verseTimes, u.verse) ?: 0.0
            if (u.position - start > 2.0) { seekToVerse(u.verse); return }
        }
        val target = u.verses.getOrNull(i + d)
        if (target != null) seekToVerse(target) else if (d > 0) u.ref?.next()?.let { play(u.bible, it) }
    }

    fun seekToVerse(verse: Int) {
        val u = _ui.value
        if (!u.active) return
        if (u.recorded) {
            val t = Timings.startOf(verseTimes, verse)
            if (t == null) { pendingVerse = verse; return }
            player.seekTo((t * 1000).toLong())
        } else {
            val i = u.verses.indexOf(verse)
            if (i >= 0) player.seekTo(i, 0)
        }
        _ui.update { it.copy(verse = verse, word = null) }
        if (!player.playWhenReady) player.play()
    }

    /** [f] is 0..1 along the seek bar. */
    fun seek(f: Float) {
        val u = _ui.value
        if (u.recorded) {
            if (u.duration > 0) player.seekTo((u.duration * f * 1000).toLong())
        } else if (u.verses.isNotEmpty()) {
            val i = (f * (u.verses.size - 1)).toInt().coerceIn(0, u.verses.lastIndex)
            player.seekTo(i, 0)
        }
    }

    fun setSpeed(x: Float) {
        player.setPlaybackSpeed(x)
        app.settings.update { it.copy(audioSpeed = x) }
        _ui.update { it.copy(speed = x) }
    }

    /** Changes the voice. If something is playing, it starts again in the new voice at the same verse. */
    fun setSource(id: String) {
        app.settings.update { it.copy(audioSource = id) }
        val u = _ui.value
        if (u.active && u.ref != null) play(u.bible, u.ref, u.verse)
    }

    /** 0 turns the timer off. [SLEEP_END_OF_CHAPTER] stops at the end of this chapter. */
    fun setSleep(minutes: Int) {
        sleepJob?.cancel()
        _ui.update { it.copy(sleepMinutes = minutes, sleepLeft = if (minutes > 0) minutes * 60_000L else 0L) }
        if (minutes <= 0) return
        sleepJob = main.launch {
            val end = System.currentTimeMillis() + minutes * 60_000L
            while (isActive) {
                val left = end - System.currentTimeMillis()
                _ui.update { it.copy(sleepLeft = left.coerceAtLeast(0)) }
                if (left <= 0) break
                delay(1000)
            }
            fadeOutAndPause()
            _ui.update { it.copy(sleepMinutes = 0, sleepLeft = 0) }
        }
    }

    private suspend fun fadeOutAndPause() {
        for (i in 10 downTo 0) { player.volume = i / 10f; delay(300) }
        player.pause()
        player.volume = 1f
    }

    // ---------- events ----------

    private fun onReady() {
        val u = _ui.value
        if (u.recorded) {
            val d = player.duration.takeIf { it > 0 }?.div(1000.0) ?: 0.0
            _ui.update { it.copy(duration = d) }
            if (verseTimes.isEmpty() && d > 0) {
                verseTimes = Timings.estimate(verseTexts.toList(), d)
                timesEstimated = true
            }
            if (pendingVerse > 0) { val v = pendingVerse; pendingVerse = 0; seekToVerse(v) }
        }
        tick()
    }

    /** The audio went on by itself from one chapter to the next. The reader reads it and clears it. */
    data class Turn(val from: Ref, val to: Ref)
    val turnedTo = kotlinx.coroutines.flow.MutableStateFlow<Turn?>(null)

    private fun onChapterEnd() {
        val u = _ui.value
        if (u.sleepMinutes == SLEEP_END_OF_CHAPTER) {
            _ui.update { it.copy(sleepMinutes = 0, playing = false) }
            return
        }
        val next = u.ref?.next()
        if (app.settings.value.autoContinue && next != null) {
            play(u.bible, next)
            // Tell the reader once, so it can turn the page if it was showing the chapter that ended.
            turnedTo.value = Turn(u.ref, next)
        } else _ui.update { it.copy(playing = false) }
    }

    private fun onError(e: PlaybackException) {
        val src = source
        val cause = generateSequence(e as Throwable) { it.cause }.firstOrNull { it is IOException && it.message?.isNotBlank() == true }?.message
        val offline = e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
            e.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val u = _ui.value
        // Recordings and Microsoft voices need the internet. With none, fall back to the phone voice.
        if ((src is Source.Recorded && offline) || src is Source.Cloud) {
            val msg = if (src is Source.Recorded) "No internet. Using the phone voice." else (cause ?: "The Microsoft voice failed.") + " Using the phone voice."
            val ref = u.ref ?: return
            start(u.bible, ref, u.verse, Source.Phone(app.settings.value.phoneVoice))
            _ui.update { it.copy(error = msg) }
            return
        }
        _ui.update { it.copy(error = cause ?: "Audio stopped: ${e.errorCodeName}", playing = false, loading = false) }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    // ---------- follow-along ----------

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = main.launch {
            while (isActive) {
                tick()
                if (!player.isPlaying) break
                delay(50)
            }
        }
    }

    private fun tick() {
        val u = _ui.value
        if (!u.active) return
        if (u.recorded) {
            val t = player.currentPosition / 1000.0
            val v = if (verseTimes.isEmpty()) u.verse else Timings.verseAt(verseTimes, t)
            _ui.value = u.copy(position = t, verse = v, word = null)
        } else {
            val item = player.currentMediaItem ?: return
            val line = lines[item.mediaId] ?: return
            val t = player.currentPosition / 1000.0
            var ws = words[item.mediaId]
            if (ws == null || ws.isEmpty()) {
                val d = player.duration.takeIf { it > 0 }?.div(1000.0)
                if (d != null) ws = Timings.estimateWords(line.text, d).also { words[item.mediaId] = it }
            }
            val w = ws?.let { Timings.wordAt(it, t) }
            val i = u.verses.indexOf(line.verse)
            _ui.value = u.copy(verse = line.verse, word = w?.let { it.start until it.end }, position = i.toDouble(), duration = (u.verses.size - 1).toDouble())
        }
    }

    // ---------- verse times for recordings ----------

    private fun loadTimes(n: Narrator, ref: Ref) {
        main.launch {
            val times = withContext(Dispatchers.IO) { runCatching { fetchTimes(n, ref) }.getOrNull() }
            if (times.isNullOrEmpty() || _ui.value.ref != ref || source != Source.Recorded(n)) return@launch
            verseTimes = times
            timesEstimated = false
            if (pendingVerse > 0 && player.playbackState == Player.STATE_READY) { val v = pendingVerse; pendingVerse = 0; seekToVerse(v) }
            tick()
        }
    }

    private fun fetchTimes(n: Narrator, ref: Ref): List<VerseTime> {
        val file = File(timingDir, "${n.id}/${ref.book}_${ref.chapter}.json")
        if (file.exists()) return json.decodeFromString(ListSerializer(VerseTime.serializer()), file.readText())
        val req = Request.Builder().url(n.timingUrl(ref)).header("User-Agent", "ObsiBible").build()
        val body = http.newCall(req).execute().use { r -> if (!r.isSuccessful) return emptyList(); r.body!!.string() }
        val arr = (json.parseToJsonElement(body) as? JsonObject)?.get("verses") as? JsonArray ?: return emptyList()
        val times = arr.mapNotNull { it as? JsonObject }.mapNotNull { o ->
            val v = (o["v"] as? JsonPrimitive)?.intOrNull ?: return@mapNotNull null
            val s = (o["start"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val e = (o["end"] as? JsonPrimitive)?.doubleOrNull ?: s
            VerseTime(v, s, e)
        }.sortedBy { it.start }
        if (times.isNotEmpty()) {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(ListSerializer(VerseTime.serializer()), times))
        }
        return times
    }

    // ---------- making voice files ----------

    /** Makes (or finds in the cache) the audio file for one verse. Runs on the player's loader thread. */
    private fun lineFile(key: String): File {
        val line = lines[key] ?: throw IOException("Nothing to say.")
        val (tag, ext) = when (val s = line.source) {
            is Source.Phone -> "phone2|${s.voice}" to "wav"
            is Source.Cloud -> "cloud|${s.voice}" to "mp3"
            is Source.Recorded -> throw IOException("Not a voice line.")
        }
        val name = sha1("$tag|${line.text}")
        val out = File(voiceDir, "$name.$ext")
        val wordsFile = File(voiceDir, "$name.json")
        if (out.exists() && out.length() > 0) {
            if (wordsFile.exists()) runCatching { words[key] = json.decodeFromString(ListSerializer(WordTime.serializer()), wordsFile.readText()) }
            return out
        }
        when (val s = line.source) {
            is Source.Phone -> {
                val w = phone.synth(line.text, s.voice, out)
                words[key] = w
                wordsFile.writeText(json.encodeToString(ListSerializer(WordTime.serializer()), w))
            }
            is Source.Cloud -> CloudSpeech.synth(s.key, s.region, s.voice, line.text, out)
            else -> Unit
        }
        trimVoiceCache()
        return out
    }

    private fun trimVoiceCache() {
        val files = voiceDir.listFiles().orEmpty().sortedBy { it.lastModified() }
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total < 250L * 1024 * 1024) break
            total -= f.length()
            f.delete()
        }
    }

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Sends voice:// to the voice maker and everything else through the download cache. */
    private inner class RouterFactory : DataSource.Factory {
        private val web = CacheDataSource.Factory().setCache(cache)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(app))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

        override fun createDataSource(): DataSource = Router(web.createDataSource())
    }

    private inner class Router(private val web: DataSource) : DataSource {
        private val file = FileDataSource()
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            web.addTransferListener(transferListener)
            file.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val uri = dataSpec.uri
            return if (uri.scheme == "voice") {
                val f = lineFile(uri.lastPathSegment ?: "")
                current = file
                file.open(dataSpec.buildUpon().setUri(Uri.fromFile(f)).build())
            } else {
                current = web
                web.open(dataSpec)
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)
        override fun getUri(): Uri? = current?.uri
        override fun close() { current?.close(); current = null }
    }
}
