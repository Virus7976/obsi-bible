package app.obsibible.bible

import app.obsibible.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Finds The Message in a key's list of Bibles. */
object MessageFinder {
    fun isMessage(b: RemoteBible) =
        b.abbreviation.equals("MSG", true) || b.abbreviation.startsWith("MSG", true) || b.name.contains("The Message", true) ||
            b.name.equals("Message", true)

    /** One plain sentence about what a working key has. */
    fun summary(service: String, list: List<RemoteBible>, added: Boolean): String {
        if (list.any(::isMessage)) return if (added) "Your $service key works. The Message is added to your Bibles." else "Your $service key works. It has The Message."
        if (list.isEmpty()) return "Your $service key works, but it has no Bibles yet. Choose Bibles for it on the $service site."
        val names = list.take(6).joinToString(", ") { it.abbreviation.ifBlank { it.name } } + if (list.size > 6) " and ${list.size - 6} more" else ""
        return "Your $service key works, but The Message is not on it. It has: $names."
    }
}

/**
 * Checks the API.Bible and YouVersion keys each time they change. It says what each key has,
 * and adds The Message to the Bible list by itself when the key is allowed to use it.
 */
@OptIn(FlowPreview::class)
class KeyScan(private val settings: SettingsStore, scope: CoroutineScope) {
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    /** "API.Bible" or "YouVersion" to one sentence about that key. */
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()

    init {
        scope.launch {
            settings.state.map { it.apiBibleKey }.distinctUntilChanged().debounce(1200).collect { scan("API.Bible", it) }
        }
        scope.launch {
            settings.state.map { it.youVersionKey }.distinctUntilChanged().debounce(1200).collect { scan("YouVersion", it) }
        }
    }

    /** Checks one key again, for a "Check again" button. */
    fun rescan(scope: CoroutineScope, service: String) {
        val s = settings.value
        scope.launch { scan(service, if (service == "YouVersion") s.youVersionKey else s.apiBibleKey) }
    }

    private suspend fun scan(service: String, key: String) {
        if (key.isBlank()) { _status.value = _status.value - service; return }
        _status.value = _status.value + (service to "Checking your $service key…")
        val text = try {
            withContext(Dispatchers.IO) {
                val yv = service == "YouVersion"
                val list = if (yv) YouVersion.bibles(key) else ApiBible.bibles(key)
                val msg = list.firstOrNull(MessageFinder::isMessage)
                if (msg != null) {
                    val id = if (yv) YouVersion.PREFIX + msg.id else msg.id
                    if (settings.value.onlineBibles.none { it.substringBefore('|') == id }) {
                        val copyright = if (yv) msg.copyright else ApiBible.copyright(key, msg.id)
                        val entry = listOf(id, msg.abbreviation, msg.name, copyright).joinToString("|") { it.replace('|', '/').replace('\n', ' ') }
                        settings.update { s -> s.copy(onlineBibles = s.onlineBibles + entry) }
                    }
                }
                MessageFinder.summary(service, list, msg != null)
            }
        } catch (e: Exception) {
            e.message ?: "Could not reach $service. Check the internet."
        }
        _status.value = _status.value + (service to text)
    }
}
