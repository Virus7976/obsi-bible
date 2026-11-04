package app.obsibible.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import app.obsibible.BuildConfig
import app.obsibible.ObsiBibleApp
import app.obsibible.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

data class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val size: Long,
    val assetName: String,
    /** True when [url] is a GitHub API asset link, which needs the token and an octet-stream header. */
    val api: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val done: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Updates the app from its own releases. It reads a GitHub release (or a small JSON mirror),
 * downloads the APK inside the app with live progress, and hands it to Android's installer.
 */
class SelfUpdate(private val context: Context, private val settings: SettingsStore) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks when the app comes to the front, at most once an hour. */
    fun checkIfDue() {
        val s = settings.value
        if (s.autoCheckUpdates && System.currentTimeMillis() - s.lastUpdateCheck > 3600_000L) check()
    }

    /** Called when playback fails: a fix may already be out. At most once every ten minutes. */
    fun checkSoon() {
        if (System.currentTimeMillis() - settings.value.lastUpdateCheck > 10 * 60_000L) check()
    }

    fun check() {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        scope.launch {
            runCatching { checkNow() }.onSuccess { info ->
                if (info != null) UpdateNotice.show(context, info)
            }
        }
    }

    /**
     * Asks the update sources now. Returns the new version when there is one to offer, else null.
     * Also used by the background job, so it must not touch the UI.
     */
    suspend fun checkNow(): UpdateInfo? {
        val busy = _state.value
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing || busy is UpdateState.Ready) return null
        _state.value = UpdateState.Checking
        val s = settings.value
        val errors = ArrayList<String>()
        var found: UpdateInfo? = null
        var reached = false
        val sources = listOfNotNull(
            s.updateMirror.takeIf { it.isNotBlank() },
            s.updateRepo.takeIf { it.isNotBlank() }?.let { "https://api.github.com/repos/$it/releases/latest" },
        )
        for (src in sources) {
            runCatching { kotlinx.coroutines.withContext(Dispatchers.IO) { fetch(src, s.updateToken) } }
                .onSuccess { reached = true; if (found == null) found = it }
                .onFailure { errors += friendly(it) }
            if (found != null) break
        }
        settings.update { it.copy(lastUpdateCheck = System.currentTimeMillis()) }
        val info = found
        val offer = info != null && newer(info.version, currentVersion) && info.version != s.ignoredVersion
        _state.value = when {
            offer -> UpdateState.Available(info!!)
            reached -> UpdateState.UpToDate
            else -> UpdateState.Failed(errors.firstOrNull() ?: "No update source is set.")
        }
        if (!reached && errors.isNotEmpty()) throw java.io.IOException(errors.first())
        return if (offer) info else null
    }

    private fun friendly(t: Throwable): String = when (t) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "No internet. Try again later."
        is java.net.SocketTimeoutException -> "GitHub took too long to answer. Try again later."
        else -> t.message ?: "The check failed."
    }

    private fun fetch(url: String, token: String): UpdateInfo? {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json")
            .apply { if (token.isNotBlank() && url.contains("api.github.com")) header("Authorization", "Bearer $token") }.build()
        client.newCall(req).execute().use { r ->
            // The repo is public, so 404 means it has no release yet. Nothing to update to.
            if (r.code == 404) return null
            if (r.code == 403) error("GitHub is busy. Try again in an hour.")
            if (!r.isSuccessful) error("Update source answered ${r.code}.")
            return parse(json.parseToJsonElement(r.body!!.string()).jsonObject, token.isNotBlank())
        }
    }

    companion object {
        private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

        /** Reads either a GitHub release or the small mirror shape {version, notes, url, size}. */
        fun parse(o: JsonObject, hasToken: Boolean, abis: List<String> = Build.SUPPORTED_ABIS.toList()): UpdateInfo? {
            val tag = o.s("tag_name")
            if (tag == null) {
                val v = o.s("version") ?: return null
                val u = o.s("url") ?: return null
                return UpdateInfo(v.removePrefix("v"), o.s("notes").orEmpty(), u, (o["size"] as? JsonPrimitive)?.longOrNull ?: 0L, u.substringAfterLast('/'), false)
            }
            val assets = (o["assets"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                .filter { it.s("name")?.endsWith(".apk", true) == true }
            val asset = pickAsset(assets.map { it.s("name")!! }, abis)?.let { n -> assets.first { it.s("name") == n } } ?: return null
            val api = hasToken && asset.s("url") != null
            val url = if (api) asset.s("url")!! else asset.s("browser_download_url") ?: return null
            return UpdateInfo(tag.removePrefix("v"), o.s("body").orEmpty(), url, (asset["size"] as? JsonPrimitive)?.longOrNull ?: 0L, asset.s("name")!!, api)
        }

        /** The APK for this phone's CPU. A file with no CPU name in it is a universal build. */
        fun pickAsset(names: List<String>, abis: List<String>): String? {
            val all = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            for (abi in abis) names.firstOrNull { it.contains(abi, true) }?.let { return it }
            return names.firstOrNull { n -> all.none { n.contains(it, true) } } ?: names.firstOrNull()
        }

        fun newer(a: String, b: String): Boolean {
            fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
            val x = parts(a)
            val y = parts(b)
            for (i in 0 until maxOf(x.size, y.size)) {
                val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }
    }

    fun ignore(info: UpdateInfo) {
        settings.update { it.copy(ignoredVersion = info.version) }
        _state.value = UpdateState.UpToDate
    }

    fun download(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        _state.value = UpdateState.Downloading(info, 0, info.size)
        scope.launch {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, "obsibible-${info.version}.apk")
                val token = settings.value.updateToken
                val req = Request.Builder().url(info.url).apply {
                    if (info.api) { header("Accept", "application/octet-stream"); header("Authorization", "Bearer $token") }
                }.build()
                client.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) error("Download answered ${r.code}.")
                    val body = r.body!!
                    val total = body.contentLength().takeIf { it > 0 } ?: info.size
                    var done = 0L
                    var last = 0L
                    file.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - last > 120) { last = now; _state.value = UpdateState.Downloading(info, done, total) }
                            }
                        }
                    }
                    if (total > 0 && done != total) error("The download stopped early. Try again.")
                }
                file
            }.onSuccess {
                val ready = UpdateState.Ready(info, it)
                _state.value = ready
                if (installWhenDone) { installWhenDone = false; downloaded = ready; finishInstall() }
            }
                .onFailure { _state.value = UpdateState.Failed(it.message ?: "Download failed.") }
        }
    }

    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Opens the Android screen where the user allows installs from Obsi Bible. */
    fun askPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    // One tap does it all: ask Android for approval first, download while the user taps OK,
    // then install with no second prompt. Android 14 and up can do the approval up front.
    private var sessionId = -1
    private var approved = false
    private var downloaded: UpdateState.Ready? = null
    private var installWhenDone = false

    private fun newSession(): Int {
        val pi = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        return pi.createSession(params)
    }

    private fun resultIntent(id: Int) = PendingIntent.getBroadcast(
        context, id, Intent(context, InstallReceiver::class.java).setPackage(context.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
    )

    /** The Install button. Approval first, then download, then install. */
    fun installFlow(info: UpdateInfo) {
        val method = settings.value.installMethod
        if (method == "system" && !canInstall()) { askPermission(); return }
        _notice.value = null
        sessionId = -1; approved = false; downloaded = null; installWhenDone = true
        if (method == "system" && Build.VERSION.SDK_INT >= 34) {
            runCatching {
                val id = newSession()
                val pi = context.packageManager.packageInstaller
                pi.openSession(id).use { s ->
                    val details = PackageInstaller.PreapprovalDetails.Builder()
                        .setPackageName(context.packageName)
                        .setLabel("Obsi Bible")
                        .setLocale(android.icu.util.ULocale.getDefault())
                        .build()
                    s.requestUserPreapproval(details, resultIntent(id).intentSender)
                }
                sessionId = id
            }
        }
        download(info)
    }

    internal fun onPreapproved() { approved = true; finishInstall() }

    fun install(ready: UpdateState.Ready) {
        val method = settings.value.installMethod
        if (method == "system" && !canInstall()) { askPermission(); return }
        downloaded = ready
        finishInstall()
    }

    /** A short note about the last install, such as "Wireless ADB failed, used the normal installer". */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    /** Shizuku or wireless ADB. If it fails, the normal Android installer takes over. */
    private fun installOutside(ready: UpdateState.Ready, method: String) {
        _state.value = UpdateState.Installing(ready.info)
        scope.launch {
            val problem = when (method) {
                "shizuku" -> when {
                    !ShizukuInstaller.running() -> "Shizuku is not running."
                    !ShizukuInstaller.allowed() -> { ShizukuInstaller.askPermission(); "Allow Obsi Bible in Shizuku first." }
                    else -> ShizukuInstaller.install(ready.file)
                }
                else -> AdbInstaller.get(context).install(ready.file)
            }
            if (problem != null) {
                _notice.value = problem + " Used the normal installer instead."
                if (canInstall()) {
                    sessionId = -1
                    scope.launch { systemInstall(ready) }
                } else {
                    _state.value = UpdateState.Failed(problem)
                    askPermission()
                }
            }
        }
    }

    private fun finishInstall() {
        val ready = downloaded ?: return
        val method = settings.value.installMethod
        if (method != "system") { installOutside(ready, method); return }
        _state.value = UpdateState.Installing(ready.info)
        if (sessionId >= 0 && !approved) return // wait for the OK; onPreapproved() calls back here
        scope.launch { systemInstall(ready) }
    }

    private fun systemInstall(ready: UpdateState.Ready) {
        _state.value = UpdateState.Installing(ready.info)
        runCatching {
            val pi = context.packageManager.packageInstaller
            val id = if (sessionId >= 0) sessionId else newSession()
            sessionId = -1
            pi.openSession(id).use { session ->
                ready.file.inputStream().use { input ->
                    session.openWrite("obsibible.apk", 0, ready.file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                session.commit(resultIntent(id).intentSender)
            }
        }.onFailure { _state.value = UpdateState.Failed(it.message ?: "Could not start the install.") }
    }

    internal fun installFailed(message: String) { _state.value = UpdateState.Failed(message) }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? ObsiBibleApp ?: return
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS ->
                if (intent.getBooleanExtra("android.content.pm.extra.PRE_APPROVAL", false)) app.updates.onPreapproved()
            else -> app.updates.installFailed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "The install did not finish.")
        }
    }
}
