package app.obsibible.bible

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** A Bible on the phone. */
data class BibleInfo(val id: String, val abbreviation: String, val name: String, val copyright: String)

/** One search hit. */
data class Hit(val ref: Ref, val text: String)

/** What a word search found. [fixed] is the query after a typo was fixed. [partial] means not every word is in each verse. */
data class Found(val total: Int, val hits: List<Hit>, val fixed: String? = null, val partial: Boolean = false)

/** The most verses of one online Bible kept on the phone. */
object OnlineCap { const val VERSES = 500; const val REFRESH_MS = 30L * 24 * 3600 * 1000 }

/** Where a search looks. */
enum class Scope(val label: String) { All("All"), Old("Old Testament"), New("New Testament"), Book("This book") }

sealed interface InstallState {
    data object Idle : InstallState
    data class Working(val name: String, val done: Int, val total: Int) : InstallState
    data class Failed(val message: String) : InstallState
}

/**
 * The Bibles stored on the phone. Each one is a SQLite file in files/bibles/<ID>.db with the
 * chapters ready to show and a full-text index for search. Everything here works offline.
 */
class Bibles(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dir = File(context.filesDir, "bibles").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true }
    private val open = HashMap<String, SQLiteDatabase>()
    private val chapterCache = object : LinkedHashMap<String, Chapter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Chapter>?) = size > 12
    }

    private val _installed = MutableStateFlow<List<BibleInfo>>(emptyList())
    val installed: StateFlow<List<BibleInfo>> = _installed.asStateFlow()

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val install: StateFlow<InstallState> = _install.asStateFlow()

    /** Bumps when a Bible finishes installing, so screens reload. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        scope.launch {
            refresh()
            if (_installed.value.none { it.id == "BSB" }) installBundled()
        }
    }

    fun has(id: String) = File(dir, "$id.db").exists()

    private var local: List<BibleInfo> = emptyList()

    private fun refresh() {
        local = dir.listFiles { f -> f.name.endsWith(".db") }.orEmpty().mapNotNull { f ->
            runCatching { info(f.nameWithoutExtension) }.getOrNull()
        }.sortedBy { if (it.id == "BSB") "" else it.abbreviation }
        _installed.value = local + online()
    }

    // ---------- online Bibles (the user's own keys) ----------

    /** The user's keys, and the Bibles they added ("id|abbr|name|copyright"; a YouVersion id starts with YV:). */
    data class Keys(val esv: String = "", val apiBible: String = "", val youVersion: String = "", val added: List<String> = emptyList())
    var keys: () -> Keys = { Keys() }

    private val _onlineError = MutableStateFlow<String?>(null)
    /** Why the last online chapter could not load, or null. */
    val onlineError: StateFlow<String?> = _onlineError.asStateFlow()

    fun isOnline(id: String) = id == EsvApi.ID || id.startsWith(ApiBible.PREFIX) || id.startsWith(YouVersion.PREFIX)

    /**
     * The phone's own voice may read any Bible: the words stay on the phone, for personal use.
     * Microsoft's voices send the words to Microsoft. For online Bibles that is the user's choice.
     */
    fun allowsCloudVoice(id: String, cloudForOnline: Boolean) = cloudForOnline || !isOnline(id)

    /** Which service an online Bible comes from, for the credit line. */
    fun serviceOf(id: String) = when {
        id == EsvApi.ID -> "Crossway"
        id.startsWith(ApiBible.PREFIX) -> "API.Bible"
        id.startsWith(YouVersion.PREFIX) -> "YouVersion"
        else -> ""
    }

    private fun online(): List<BibleInfo> {
        val k = keys()
        val out = ArrayList<BibleInfo>()
        if (k.esv.isNotBlank()) out += EsvApi.info
        k.added.forEach { e ->
            val p = e.split('|')
            if (p.size < 3) return@forEach
            val yv = p[0].startsWith(YouVersion.PREFIX)
            if (yv && k.youVersion.isBlank() || !yv && k.apiBible.isBlank()) return@forEach
            out += BibleInfo(if (yv) p[0] else ApiBible.PREFIX + p[0], p[1], p[2], p.getOrElse(3) { "" })
        }
        return out
    }

    /** Call when the keys change. */
    fun refreshOnline() { _installed.value = local + online(); _version.value++ }

    private fun onlineDir(id: String) = File(context.filesDir, "online/" + id.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    /** Saved text is read again after 30 days, as API.Bible asks. File name: BOOK.CH.VERSES.SAVEDMILLIS.json */
    private fun fresh(f: File): Boolean {
        val saved = f.name.split('.').getOrNull(3)?.toLongOrNull() ?: return false
        return System.currentTimeMillis() - saved < OnlineCap.REFRESH_MS
    }

    private fun onlineChapter(id: String, ref: Ref): Chapter? {
        val d = onlineDir(id)
        val old = d.listFiles { f -> f.name.startsWith("${ref.book}.${ref.chapter}.") }?.firstOrNull()
        if (old != null && fresh(old)) {
            runCatching { json.decodeFromString(Chapter.serializer(), old.readText()) }.getOrNull()?.let { old.setLastModified(System.currentTimeMillis()); return it }
        }
        // Never reach the network from the main thread; screens load chapters in the background.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return null
        val k = keys()
        return try {
            val ch = when {
                id == EsvApi.ID -> EsvApi.chapter(k.esv, ref)
                id.startsWith(YouVersion.PREFIX) -> YouVersion.chapter(k.youVersion, id.removePrefix(YouVersion.PREFIX), ref)
                else -> ApiBible.chapter(k.apiBible, id.removePrefix(ApiBible.PREFIX), ref)
            }
            _onlineError.value = null
            old?.delete()
            val count = ch.verses().size
            File(d, "${ref.book}.${ref.chapter}.$count.${System.currentTimeMillis()}.json").writeText(json.encodeToString(Chapter.serializer(), ch))
            trimCache(d)
            ch
        } catch (e: Exception) {
            old?.delete() // too old to keep
            _onlineError.value = when (e) {
                is java.net.UnknownHostException -> "No internet. This chapter is not on the phone yet."
                is YouVersion.NotFound -> "${ref.chapterRef.label} is not in this Bible."
                else -> e.message ?: "Could not reach the Bible service."
            }
            null
        }
    }

    /**
     * Keeps at most 500 verses, and at most half of any book's chapters, on the phone. Crossway
     * sets this for the ESV; the same cap is used for every online Bible to stay safe.
     */
    private fun trimCache(d: File) {
        val files = d.listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.lastModified() }.toMutableList()
        fun verses(f: File) = f.name.split('.').getOrNull(2)?.toIntOrNull() ?: 30
        for ((book, list) in files.groupBy { it.name.substringBefore('.') }) {
            val max = ((Books.get(book)?.chapters ?: 2) / 2).coerceAtLeast(1)
            list.dropLast(max).forEach { it.delete(); files.remove(it) }
        }
        var total = files.sumOf { verses(it) }
        while (total > OnlineCap.VERSES && files.size > 1) {
            val f = files.removeAt(0)
            total -= verses(f)
            f.delete()
        }
    }

    fun info(id: String): BibleInfo? {
        val db = db(id) ?: return null
        val meta = HashMap<String, String>()
        db.rawQuery("SELECT key, value FROM meta", null).use { c -> while (c.moveToNext()) meta[c.getString(0)] = c.getString(1) }
        if (meta["done"] != "1") return null
        return BibleInfo(id, meta["abbreviation"] ?: id, meta["name"] ?: id, meta["copyright"].orEmpty())
    }

    @Synchronized
    private fun db(id: String): SQLiteDatabase? {
        open[id]?.let { if (it.isOpen) return it }
        val f = File(dir, "$id.db")
        if (!f.exists()) return null
        return SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { open[id] = it }
    }

    /** The chapter, or null when this Bible is not installed (yet). */
    fun chapter(id: String, ref: Ref): Chapter? {
        val key = "$id/${ref.book}.${ref.chapter}"
        synchronized(chapterCache) { chapterCache[key]?.let { return it } }
        if (isOnline(id)) {
            val ch = onlineChapter(id, ref) ?: return null
            synchronized(chapterCache) { chapterCache[key] = ch }
            return ch
        }
        val db = db(id) ?: return null
        val doc = db.rawQuery("SELECT doc FROM chapters WHERE book=? AND ch=?", arrayOf(ref.book, ref.chapter.toString())).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: return null
        val ch = json.decodeFromString(Chapter.serializer(), doc)
        synchronized(chapterCache) { chapterCache[key] = ch }
        return ch
    }

    fun verseText(id: String, ref: Ref): String? {
        val ch = chapter(id, ref) ?: return null
        val vs = ch.verses()
        val end = if (ref.verseEnd > ref.verse) ref.verseEnd else ref.verse
        return vs.filter { it.first in ref.verse..end }.joinToString(" ") { it.second }.ifBlank { null }
    }

    /**
     * Word search. Every word must appear; the last word also matches as a prefix,
     * so results show while typing. Results come in Bible order.
     */
    /**
     * Word search, best match first. A typo is fixed by itself ([Found.fixed]). When no verse has
     * every word, verses with most of the words are shown ([Found.partial]).
     */
    fun search(id: String, query: String, where: Scope, book: String?, limit: Int = 300): Found {
        if (isOnline(id)) {
            val k = keys()
            return runCatching {
                when {
                    id == EsvApi.ID -> EsvApi.search(k.esv, query)
                    id.startsWith(ApiBible.PREFIX) -> ApiBible.search(k.apiBible, id.removePrefix(ApiBible.PREFIX), query)
                    else -> Found(0, emptyList()) // YouVersion has no word search
                }
            }.getOrElse { Found(0, emptyList()) }
        }
        val db = db(id) ?: return Found(0, emptyList())
        val (lo, hi) = range(where, book)
        val phrase = Regex("\"([^\"]+)\"").containsMatchIn(query)
        val typed = SearchRank.words(normalize(query.replace("\"", " ")))
        if (typed.isEmpty()) return Found(0, emptyList())

        fun run(match: String, words: List<String>, cap: Int): Pair<Int, List<Hit>> {
            // The range goes into the SQL as numbers: a bound "?" is text, and in SQLite text never equals a number.
            val cond = "verses MATCH ? AND ord BETWEEN $lo AND $hi"
            val total = db.rawQuery("SELECT count(*) FROM verses WHERE $cond", arrayOf(match)).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
            if (total == 0) return 0 to emptyList()
            val rows = ArrayList<Triple<Hit, Int, Long>>()
            db.rawQuery("SELECT book, ch, v, text, norm, ord FROM verses WHERE $cond ORDER BY ord LIMIT $cap", arrayOf(match)).use { c ->
                while (c.moveToNext()) rows += Triple(Hit(Ref(c.getString(0), c.getInt(1), c.getInt(2)), c.getString(3)), SearchRank.score(c.getString(4), words), c.getLong(5))
            }
            val ranked = rows.sortedWith(compareByDescending<Triple<Hit, Int, Long>> { it.second }.thenBy { it.third }).map { it.first }
            return total to ranked.take(limit)
        }

        val match = matchQuery(query) ?: return Found(0, emptyList())
        val (total, hits) = run(match, typed, 3000)
        if (total > 0 || phrase) return Found(total, hits)

        // a typo: fix each word against the words this Bible has
        SearchRank.correct(typed, vocabulary(id))?.let { fixed ->
            val q2 = fixed.joinToString(" ")
            matchQuery(q2)?.let { m ->
                val (t2, h2) = run(m, fixed, 3000)
                if (t2 > 0) return Found(t2, h2, fixed = q2)
            }
        }
        // no verse has every word: show verses with most of them
        if (typed.size > 1) {
            val any = typed.mapIndexed { i, w -> if (i == typed.lastIndex) "norm:$w*" else "norm:$w" }.joinToString(" OR ")
            val (t3, h3) = run(any, typed, 3000)
            if (t3 > 0) return Found(t3, h3, partial = true)
        }
        return Found(0, emptyList())
    }

    private val vocab = HashMap<String, Map<String, Int>>()

    /** Every word in a Bible with how often it is used. Built once, the first time a typo needs it. */
    @Synchronized
    fun vocabulary(id: String): Map<String, Int> {
        vocab[id]?.let { return it }
        val db = db(id) ?: return emptyMap()
        val counts = HashMap<String, Int>(20000)
        db.rawQuery("SELECT norm FROM verses", null).use { c ->
            while (c.moveToNext()) for (w in c.getString(0).split(' ')) if (w.length >= 2) counts[w] = (counts[w] ?: 0) + 1
        }
        vocab[id] = counts
        return counts
    }

    private fun range(where: Scope, book: String?): Pair<Long, Long> = when (where) {
        Scope.All -> 0L to Long.MAX_VALUE
        Scope.Old -> 0L to ord(39, 999, 999)
        Scope.New -> ord(40, 0, 0) to Long.MAX_VALUE
        Scope.Book -> {
            val n = Books.get(book ?: "")?.number ?: 1
            ord(n, 0, 0) to ord(n, 999, 999)
        }
    }

    // ---------- installing ----------

    private fun installBundled() {
        runCatching {
            context.assets.open("bibles/BSB.zip").use { input ->
                installZip(
                    input, BibleInfo(
                        "BSB", "BSB", "Berean Standard Bible",
                        "The Holy Bible, Berean Standard Bible, BSB is produced in cooperation with Bible Hub, Discovery Bible, OpenBible.com, and the Berean Bible Translation Committee. This text of God's Word has been dedicated to the public domain.",
                    ),
                )
            }
        }.onFailure { _install.value = InstallState.Failed(it.message ?: "Could not set up the Bible.") }
    }

    /** Installs a zip of USFM files (one per book). Writes to a temp file, then swaps it in. */
    fun installZip(input: InputStream, info: BibleInfo) {
        _install.value = InstallState.Working(info.name, 0, 66)
        val tmp = File(dir, "${info.id}.db.tmp").apply { delete() }
        val db = SQLiteDatabase.openOrCreateDatabase(tmp, null)
        try {
            createTables(db)
            db.beginTransaction()
            var done = 0
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (e.isDirectory || !(e.name.endsWith(".usfm", true) || e.name.endsWith(".sfm", true))) continue
                    val text = zip.readBytes().toString(Charsets.UTF_8)
                    for (ch in Usfm.parse(text)) addChapter(db, ch)
                    done++
                    _install.value = InstallState.Working(info.name, done, 66)
                }
            }
            if (done == 0) error("That file has no Bible books in it.")
            for ((k, v) in mapOf("id" to info.id, "abbreviation" to info.abbreviation, "name" to info.name, "copyright" to info.copyright, "done" to "1")) {
                db.insert("meta", null, ContentValues().apply { put("key", k); put("value", v) })
            }
            db.setTransactionSuccessful()
        } finally {
            if (db.inTransaction()) db.endTransaction()
            db.close()
        }
        synchronized(this) { open.remove(info.id)?.close() }
        val target = File(dir, "${info.id}.db")
        target.delete()
        File(dir, "${info.id}.db-journal").delete()
        if (!tmp.renameTo(target)) error("Could not save the Bible.")
        synchronized(chapterCache) { chapterCache.keys.removeAll { it.startsWith("${info.id}/") } }
        refresh()
        _install.value = InstallState.Idle
        _version.value++
    }

    private fun createTables(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)")
        db.execSQL("CREATE TABLE chapters (book TEXT, ch INTEGER, doc TEXT, PRIMARY KEY (book, ch))")
        val cols = "book, ch, v, ord, text, norm, notindexed=book, notindexed=ch, notindexed=v, notindexed=ord, notindexed=text"
        runCatching { db.execSQL("CREATE VIRTUAL TABLE verses USING fts4($cols, tokenize=unicode61)") }
            .onFailure { db.execSQL("CREATE VIRTUAL TABLE verses USING fts4($cols)") }
    }

    private fun addChapter(db: SQLiteDatabase, ch: Chapter) {
        db.insert("chapters", null, ContentValues().apply {
            put("book", ch.book); put("ch", ch.number); put("doc", json.encodeToString(Chapter.serializer(), ch))
        })
        val n = Books.get(ch.book)?.number ?: return
        for ((v, text) in ch.verses()) {
            db.insert("verses", null, ContentValues().apply {
                put("book", ch.book); put("ch", ch.number); put("v", v); put("ord", ord(n, ch.number, v))
                put("text", text); put("norm", normalize(text))
            })
        }
    }

    companion object {
        fun ord(book: Int, ch: Int, v: Int): Long = book * 1_000_000L + ch * 1_000L + v

        /** Lowercase words only. Curly quotes and dashes become spaces, so "God's" finds "god". */
        fun normalize(s: String): String =
            s.lowercase().replace(Regex("[\\u2018\\u2019'\\u201C\\u201D\"\\u2014\\u2013\\-.,;:!?()\\[\\]]"), " ").replace(Regex("\\s+"), " ").trim()

        /** Turns typed words into an FTS query. Null when nothing is searchable. */
        fun matchQuery(typed: String): String? {
            val phrase = Regex("\"([^\"]+)\"").find(typed)?.groupValues?.get(1)
            if (phrase != null) {
                val words = normalize(phrase).split(' ').filter { it.isNotBlank() }
                return if (words.isEmpty()) null else "norm:\"" + words.joinToString(" ") + "\""
            }
            val words = normalize(typed).split(' ').filter { it.isNotBlank() && it.all(Char::isLetterOrDigit) }
            if (words.isEmpty()) return null
            return words.mapIndexed { i, w -> if (i == words.lastIndex) "norm:$w*" else "norm:$w" }.joinToString(" ")
        }
    }
}
