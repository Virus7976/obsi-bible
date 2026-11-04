package app.obsibible.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import rikka.shizuku.Shizuku
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.Random
import android.sun.security.x509.AlgorithmId
import android.sun.security.x509.CertificateAlgorithmId
import android.sun.security.x509.CertificateExtensions
import android.sun.security.x509.CertificateIssuerName
import android.sun.security.x509.CertificateSerialNumber
import android.sun.security.x509.CertificateSubjectName
import android.sun.security.x509.CertificateValidity
import android.sun.security.x509.CertificateVersion
import android.sun.security.x509.CertificateX509Key
import android.sun.security.x509.KeyIdentifier
import android.sun.security.x509.PrivateKeyUsageExtension
import android.sun.security.x509.SubjectKeyIdentifierExtension
import android.sun.security.x509.X500Name
import android.sun.security.x509.X509CertImpl
import android.sun.security.x509.X509CertInfo

/** Installs an APK through Shizuku, which runs `pm install` with the rights of the adb shell. */
object ShizukuInstaller {
    const val REQUEST_CODE = 4107

    /** Shizuku is installed and running. */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun allowed(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun askPermission() { runCatching { Shizuku.requestPermission(REQUEST_CODE) } }

    /** Returns null on success, or a short message saying what went wrong. */
    fun install(file: File): String? = runCatching {
        // Shizuku keeps newProcess private since 13, but it is still there and this is how its own samples run commands.
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("pm", "install", "-r", "-S", file.length().toString()), null, null) as Process
        file.inputStream().use { input -> p.outputStream.use { out -> input.copyTo(out) } }
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        p.waitFor()
        if (out.contains("Success", ignoreCase = true)) null else out.trim().ifBlank { "Shizuku could not install the file." }
    }.getOrElse { "Shizuku failed: ${it.message}" }
}

/**
 * Wireless debugging on this same phone. Pair once with the code Android shows, then the app can
 * run `cmd package install` itself. The keys stay in the app's private folder.
 */
class AdbInstaller private constructor(private val context: Context) {
    private class Manager(private val dir: File) : AbsAdbConnectionManager() {
        private val priv: PrivateKey
        private val cert: Certificate

        init {
            setApi(Build.VERSION.SDK_INT)
            val keyFile = File(dir, "adb_key.pk8")
            val certFile = File(dir, "adb_cert.der")
            var p: PrivateKey? = null
            var c: Certificate? = null
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    p = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    c = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
                }
            }
            if (p == null || c == null) {
                val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom.getInstance("SHA1PRNG")) }.generateKeyPair()
                val subject = "CN=Obsi Bible"
                val algorithm = "SHA512withRSA"
                val from = Date()
                val to = Date(System.currentTimeMillis() + 10L * 365 * 86_400_000)
                val ext = CertificateExtensions()
                ext.set("SubjectKeyIdentifier", SubjectKeyIdentifierExtension(KeyIdentifier(pair.public).identifier))
                val name = X500Name(subject)
                ext.set("PrivateKeyUsage", PrivateKeyUsageExtension(from, to))
                val info = X509CertInfo()
                info.set("version", CertificateVersion(2))
                info.set("serialNumber", CertificateSerialNumber(Random().nextInt() and Int.MAX_VALUE))
                info.set("algorithmID", CertificateAlgorithmId(AlgorithmId.get(algorithm)))
                info.set("subject", CertificateSubjectName(name))
                info.set("key", CertificateX509Key(pair.public))
                info.set("validity", CertificateValidity(from, to))
                info.set("issuer", CertificateIssuerName(name))
                info.set("extensions", ext)
                val impl = X509CertImpl(info)
                impl.sign(pair.private, algorithm)
                p = pair.private
                c = impl
                keyFile.writeBytes(pair.private.encoded)
                certFile.writeBytes(impl.encoded)
            }
            priv = p!!
            cert = c!!
        }

        override fun getPrivateKey(): PrivateKey = priv
        override fun getCertificate(): Certificate = cert
        override fun getDeviceName(): String = "Obsi Bible"
    }

    private val manager: AbsAdbConnectionManager = Manager(context.filesDir)

    fun paired(): Boolean = File(context.filesDir, "adb_paired").exists()

    private fun canWriteSecure() =
        context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS") == PackageManager.PERMISSION_GRANTED

    /** Turns wireless debugging back on by itself, once the app has been allowed to (see [pair]). */
    private fun switchOnWireless() {
        if (!canWriteSecure()) return
        runCatching { android.provider.Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) }
    }

    /**
     * Pair once with the port and code shown in Developer options, Wireless debugging, Pair device
     * with pairing code. Afterwards the app lets itself change the debugging switch, so it can turn
     * wireless debugging back on later. Returns null on success, or a short message.
     */
    fun pair(port: Int, code: String): String? {
        val ok = runCatching { manager.pair("127.0.0.1", port, code) }.getOrElse { return "Pairing failed: ${it.message}" }
        if (!ok) return "Pairing failed. Check the code and the port, then try again."
        File(context.filesDir, "adb_paired").writeText("1")
        runCatching {
            if (manager.isConnected || manager.autoConnect(context, 8000)) {
                manager.openStream("shell:pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS").use { it.openInputStream().readBytes() }
            }
        }
        return null
    }

    /** Returns null on success, or a short message saying what went wrong. */
    fun install(file: File): String? = runCatching {
        if (!paired()) return "Wireless ADB is not set up yet. Pair it in Settings, Updates."
        if (!manager.isConnected) {
            var connected = runCatching { manager.autoConnect(context, 8000) }.getOrDefault(false)
            if (!connected) {
                switchOnWireless()
                connected = runCatching { manager.autoConnect(context, 12000) }.getOrDefault(false)
            }
            if (!connected) return "Could not reach wireless debugging. Turn it on in Developer options."
        }
        manager.openStream("exec:cmd package install -S ${file.length()}").use { stream ->
            val out = stream.openOutputStream()
            file.inputStream().use { it.copyTo(out) }
            out.flush()
            val reply = stream.openInputStream().bufferedReader().readText()
            if (reply.contains("Success", ignoreCase = true)) null else reply.trim().ifBlank { "ADB could not install the file." }
        }
    }.getOrElse { "ADB failed: ${it.message}" }

    companion object {
        @Volatile private var instance: AdbInstaller? = null
        fun get(context: Context): AdbInstaller =
            instance ?: synchronized(this) { instance ?: AdbInstaller(context.applicationContext).also { instance = it } }
    }
}
