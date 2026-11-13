package dev.lanlink.core

import org.json.JSONArray
import org.json.JSONObject

/** Protocol constants. Must match the desktop daemon (src/protocol.rs). */
object Proto {
    const val VERSION = 8
    const val UDP_PORT = 1716
    val TCP_PORTS = 1716..1764
    val PAYLOAD_PORTS = 1739..1764

    const val IDENTITY = "kdeconnect.identity"
    const val PAIR = "kdeconnect.pair"
    const val PING = "kdeconnect.ping"
    const val BATTERY = "kdeconnect.battery"
    const val SHARE = "kdeconnect.share.request"
    const val SHARE_UPDATE = "kdeconnect.share.request.update"
    const val SFTP = "kdeconnect.sftp"
    const val SFTP_REQUEST = "kdeconnect.sftp.request"
    const val MPRIS = "kdeconnect.mpris"
    const val MPRIS_REQUEST = "kdeconnect.mpris.request"
    const val MOUSEPAD = "kdeconnect.mousepad.request"
    const val MOUSEPAD_ECHO = "kdeconnect.mousepad.echo"
    const val MOUSEPAD_KEYBOARD = "kdeconnect.mousepad.keyboardstate"
    /** A song the phone recognized (lanlink only). */
    const val SONG = "lanlink.song"

    /** What the phone accepts. */
    val INCOMING = listOf(PING, SHARE, SHARE_UPDATE, SFTP_REQUEST, MPRIS, MOUSEPAD_ECHO, MOUSEPAD_KEYBOARD)
    /** What the phone sends. */
    val OUTGOING = listOf(PING, BATTERY, SHARE, SFTP, MPRIS_REQUEST, MOUSEPAD, SONG)

    fun validDeviceId(id: String) = id.length in 32..38 && id.all { it.isLetterOrDigit() && it.code < 128 || it == '_' }

    /** Same rules KDE Connect applies to names. */
    fun sanitizeName(name: String): String {
        val s = name.filter { it !in "\"',;:.!?()[]<>" }.take(32).trim()
        return s.ifEmpty { "lanlink" }
    }
}

class Packet(val type: String, val body: JSONObject = JSONObject()) {
    var id: Long = System.currentTimeMillis()
    var payloadSize: Long = -1
    var payloadPort: Int = -1

    fun serialize(): ByteArray {
        val o = JSONObject().put("id", id).put("type", type).put("body", body)
        if (payloadSize >= 0) o.put("payloadSize", payloadSize)
        if (payloadPort > 0) o.put("payloadTransferInfo", JSONObject().put("port", payloadPort))
        return (o.toString() + "\n").toByteArray(Charsets.UTF_8)
    }

    fun str(k: String): String? = if (body.has(k) && !body.isNull(k)) body.optString(k) else null
    fun bool(k: String) = body.optBoolean(k, false)
    fun long(k: String): Long? = if (body.has(k)) body.optLong(k) else null

    override fun toString() = "Packet($type, $body)"

    companion object {
        fun parse(line: String): Packet {
            val o = JSONObject(line)
            val p = Packet(o.getString("type"), o.optJSONObject("body") ?: JSONObject())
            p.id = o.optLong("id", 0)
            p.payloadSize = o.optLong("payloadSize", -1)
            p.payloadPort = o.optJSONObject("payloadTransferInfo")?.optInt("port", -1) ?: -1
            return p
        }

        fun of(type: String, vararg kv: Pair<String, Any?>): Packet {
            val b = JSONObject()
            for ((k, v) in kv) b.put(k, v)
            return Packet(type, b)
        }

        fun identity(id: Identity, tcpPort: Int?): Packet {
            val b = JSONObject()
                .put("deviceId", id.deviceId)
                .put("deviceName", id.name)
                .put("deviceType", id.type)
                .put("protocolVersion", Proto.VERSION)
                .put("incomingCapabilities", JSONArray(Proto.INCOMING))
                .put("outgoingCapabilities", JSONArray(Proto.OUTGOING))
            if (tcpPort != null) b.put("tcpPort", tcpPort)
            return Packet(Proto.IDENTITY, b)
        }
    }
}
