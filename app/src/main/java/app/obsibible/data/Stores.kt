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

class SettingsStore(context: Context) : JsonFileStore<AppSettings>(File(context.filesDir, "settings.json")) {
    // Light or black picked before looks were shared counts from when it was saved.
    private val _state = MutableStateFlow((readOrNull() ?: AppSettings()).let {
        if (it.styleAt == 0L && it.appearance != AppSettings().appearance) it.copy(styleAt = File(context.filesDir, "settings.json").lastModified().coerceAtLeast(1L)) else it
    })
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    val isFirstRun: Boolean = readOrNull() == null

    override fun encode(value: AppSettings) = storeJson.encodeToString(AppSettings.serializer(), value)
    override fun decode(text: String) = storeJson.decodeFromString(AppSettings.serializer(), text)

    /** A change made here. Picking light, dark or black is stamped, so the other Obsi apps take it too. */
    fun update(block: (AppSettings) -> AppSettings) {
        _state.update { old -> block(old).let { if (it.appearance != old.appearance) it.copy(styleAt = System.currentTimeMillis()) else it } }
        persist(_state.value)
    }

    /** Light, dark or black from another Obsi app. It keeps that app's stamp. */
    fun syncStyle(block: (AppSettings) -> AppSettings) {
        _state.update(block)
        persist(_state.value)
    }

    /** Takes light, dark or black from the newest look in the other Obsi apps. */
    suspend fun pullStyle(context: Context) {
        val newest = app.obsi.core.share.SharedStyle.others(context).maxByOrNull { it.at } ?: return
        if (newest.at <= value.styleAt) return
        // Bible has no dusk. Dusk is closest to dark.
        val mode = when (newest.appearance) { "light", "dark", "oled", "auto" -> newest.appearance; else -> "dark" }
        syncStyle { it.copy(appearance = mode, styleAt = newest.at) }
    }
}
