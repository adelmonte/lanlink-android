package dev.lanlink

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.system.Os
import android.system.OsConstants
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.lanlink.core.Core
import dev.lanlink.core.Identity
import dev.lanlink.core.Proto
import dev.lanlink.core.Trusted
import dev.lanlink.core.TrustStore
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

class App : Application() {
    lateinit var identity: AndroidIdentity
    lateinit var plugins: Plugins
    lateinit var core: Core
    lateinit var songs: Songs

    override fun onCreate() {
        super.onCreate()
        instance = this
        Notify.channels(this)
        identity = AndroidIdentity(this)
        plugins = Plugins(this)
        core = Core(identity, PrefsTrust(this), plugins)
        plugins.core = core
        songs = Songs(this)
        core.tune = { s ->
            // Probe an idle link after 15 s, then every 5 s, give up after 3: Linux TCP_KEEPIDLE/INTVL/CNT.
            try {
                ParcelFileDescriptor.fromSocket(s).use { pfd ->
                    Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_TCP, 4, 15)
                    Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_TCP, 5, 5)
                    Os.setsockoptInt(pfd.fileDescriptor, OsConstants.IPPROTO_TCP, 6, 3)
                }
            } catch (e: Exception) { plugins.log("keepalive: $e") }
        }
    }

    val prefs get() = getSharedPreferences("lanlink", MODE_PRIVATE)

    /** Addresses to try directly when broadcast discovery doesn't reach them. */
    var manualHosts: List<String>
        get() = JSONArray(prefs.getString("hosts", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } }
        set(v) = prefs.edit().putString("hosts", JSONArray(v).toString()).apply()

    companion object {
        lateinit var instance: App
            private set
    }
}

/** Identity backed by the Android keystore: the private key never leaves the TEE. */
class AndroidIdentity(private val ctx: Context) : Identity {
    private val prefs = ctx.getSharedPreferences("identity", Context.MODE_PRIVATE)
    private val alias = "lanlink-tls"
    override val deviceId: String
    override val cert: X509Certificate
    override val key: PrivateKey
    override val type = "phone"
    override val name: String get() = Proto.sanitizeName(prefs.getString("name", null) ?: defaultName())

    fun rename(n: String) = prefs.edit().putString("name", Proto.sanitizeName(n)).apply()

    private fun defaultName(): String =
        Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME) ?: Build.MODEL

    init {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        var id = prefs.getString("deviceId", null)
        if (id == null || !ks.containsAlias(alias)) {
            id = UUID.randomUUID().toString().replace("-", "")
            if (ks.containsAlias(alias)) ks.deleteEntry(alias)
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                // NONE is needed: the TLS stack hands the keystore pre-hashed data.
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                .setCertificateSubject(X500Principal("CN=$id, OU=KDE Connect, O=KDE"))
                .setCertificateSerialNumber(BigInteger.valueOf(now))
                .setCertificateNotBefore(Date(now - 365 * day))
                .setCertificateNotAfter(Date(now + 3650 * day))
                .build()
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply { initialize(spec) }.generateKeyPair()
            prefs.edit().putString("deviceId", id).apply()
        }
        deviceId = id
        cert = ks.getCertificate(alias) as X509Certificate
        key = ks.getKey(alias, null) as PrivateKey
    }
}

class PrefsTrust(ctx: Context) : TrustStore {
    private val prefs = ctx.getSharedPreferences("trusted", Context.MODE_PRIVATE)

    override fun load(): List<Trusted> = prefs.all.values.mapNotNull { v ->
        try {
            val o = JSONObject(v as String)
            Trusted(o.getString("id"), o.getString("name"), o.getString("type"), Base64.decode(o.getString("cert"), Base64.NO_WRAP))
        } catch (_: Exception) { null }
    }

    override fun save(t: Trusted) {
        val o = JSONObject().put("id", t.id).put("name", t.name).put("type", t.type)
            .put("cert", Base64.encodeToString(t.cert, Base64.NO_WRAP))
        prefs.edit().putString(t.id, o.toString()).apply()
    }

    override fun remove(id: String) = prefs.edit().remove(id).apply()
}
