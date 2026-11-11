package dev.lanlink.core

import java.io.ByteArrayInputStream
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/** This device: id, display name, and the key + self-signed cert other devices pin. */
interface Identity {
    val deviceId: String
    val cert: X509Certificate
    val key: PrivateKey
    val name: String
    val type: String
}

/**
 * Both sides use self-signed certs and pin each other's cert when pairing,
 * so the handshake accepts anything and [Core] checks the pinned cert after.
 */
class Tls(private val id: Identity) {
    private val alias = "lanlink"

    private val keyManager = object : X509ExtendedKeyManager() {
        private fun ok(t: String?) = t == null || t.equals(id.key.algorithm, true) || (t.startsWith("EC") && id.key.algorithm == "EC")
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) =
            if (keyType == null || keyType.any { ok(it) }) alias else null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(alias)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = if (ok(keyType)) alias else null
        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?) =
            chooseClientAlias(keyType, issuers, null)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?) =
            chooseServerAlias(keyType, issuers, null)
        override fun getCertificateChain(a: String?) = arrayOf(id.cert)
        override fun getPrivateKey(a: String?) = id.key
    }

    private val trustAll = object : X509ExtendedTrustManager() {
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?) {}
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, s: Socket?) {}
        override fun checkClientTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
        override fun checkServerTrusted(c: Array<out X509Certificate>?, a: String?, e: SSLEngine?) {}
        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    private val ctx: SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(keyManager), arrayOf(trustAll), SecureRandom())
    }

    private fun wrap(s: Socket, client: Boolean): SSLSocket {
        val ssl = ctx.socketFactory.createSocket(s, s.inetAddress.hostAddress, s.port, true) as SSLSocket
        ssl.useClientMode = client
        if (!client) ssl.needClientAuth = true
        ssl.soTimeout = 15_000
        ssl.startHandshake()
        ssl.soTimeout = 0
        return ssl
    }

    fun client(s: Socket) = wrap(s, true)
    fun server(s: Socket) = wrap(s, false)

    companion object {
        fun peerCert(s: SSLSocket): ByteArray =
            s.session.peerCertificates.firstOrNull()?.encoded ?: error("peer sent no certificate")

        fun parse(der: ByteArray): X509Certificate =
            CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

        fun commonName(der: ByteArray): String? =
            Regex("(?:^|,)\\s*CN=([^,]+)").find(parse(der).subjectX500Principal.getName("RFC2253"))?.groupValues?.get(1)

        /** The 8-character code both screens show while pairing (protocol v8). */
        fun verificationKey(ours: X509Certificate, theirs: ByteArray, timestamp: Long): String {
            var a = ours.publicKey.encoded
            var b = parse(theirs).publicKey.encoded
            if (compareUnsigned(a, b) < 0) a = b.also { b = a }
            val md = MessageDigest.getInstance("SHA-256")
            md.update(a)
            md.update(b)
            md.update(timestamp.toString().toByteArray())
            return md.digest().joinToString("") { "%02x".format(it) }.take(8).uppercase()
        }

        fun fingerprint(der: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(der).take(8).joinToString("") { "%02X".format(it) }

        private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (c != 0) return c
            }
            return a.size - b.size
        }
    }
}
