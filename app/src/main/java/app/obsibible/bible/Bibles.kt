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

    private fun refresh() {
        _installed.value = dir.listFiles { f -> f.name.endsWith(".db") }.orEmpty().mapNotNull { f ->
            runCatching { info(f.nameWithoutExtension) }.getOrNull()
        }.sortedBy { if (it.id == "BSB") "" else it.abbreviation }
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
    fun search(id: String, query: String, where: Scope, book: String?, limit: Int = 300): Pair<Int, List<Hit>> {
        val db = db(id) ?: return 0 to emptyList()
        val match = matchQuery(query) ?: return 0 to emptyList()
        val (lo, hi) = range(where, book)
        // The range goes into the SQL as numbers: a bound "?" is text, and in SQLite text never equals a number.
        val args = arrayOf(match)
        val cond = "verses MATCH ? AND ord BETWEEN $lo AND $hi"
        val total = db.rawQuery("SELECT count(*) FROM verses WHERE $cond", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        val hits = ArrayList<Hit>()
        db.rawQuery("SELECT book, ch, v, text FROM verses WHERE $cond ORDER BY ord LIMIT $limit", args).use { c ->
            while (c.moveToNext()) hits += Hit(Ref(c.getString(0), c.getInt(1), c.getInt(2)), c.getString(3))
        }
        return total to hits
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
