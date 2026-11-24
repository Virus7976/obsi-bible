package app.obsibible.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File

internal val storeJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
}

/** Small JSON-file store. One file, rewritten on change. Plenty for a personal library. */
abstract class JsonFileStore<T>(private val file: File) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    protected abstract fun encode(value: T): String
    protected abstract fun decode(text: String): T

    protected fun readOrNull(): T? = runCatching { decode(file.readText()) }.getOrNull()

    protected fun persist(value: T) {
        scope.launch {
            lock.withLock {
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(encode(value))
                    tmp.renameTo(file)
                }
            }
        }
    }
}

/**
 * Obsi Bible's own settings: the Bible you read, your place, voices, online Bible keys and the highlights
 * file. The look, Obsidian, templates and updates are in the shared settings, like the other Obsi apps.
 * Before 0.13 everything was in settings.json. The first start of 0.13 copies that file here once, so
 * nothing you set is lost.
 */
class PrefsStore(context: Context) : JsonFileStore<BiblePrefs>(File(context.filesDir, "bible.json")) {
    /** True on the first start after the move to the shared settings. The app moves the vault over then. */
    val moved: Boolean

    init {
        val mine = File(context.filesDir, "bible.json")
        val old = File(context.filesDir, "settings.json")
        moved = !mine.exists() && old.exists()
        if (moved) runCatching { old.copyTo(mine) }
    }

    private val _state = MutableStateFlow(readOrNull() ?: BiblePrefs())
    val state: StateFlow<BiblePrefs> = _state.asStateFlow()
    val value: BiblePrefs get() = _state.value

    override fun encode(value: BiblePrefs) = storeJson.encodeToString(BiblePrefs.serializer(), value)
    override fun decode(text: String) = storeJson.decodeFromString(BiblePrefs.serializer(), text)

    fun update(block: (BiblePrefs) -> BiblePrefs) {
        _state.update(block)
        persist(_state.value)
    }
}
