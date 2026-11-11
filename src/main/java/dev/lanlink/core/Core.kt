package dev.lanlink.core

import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

sealed class PairState {
    data object None : PairState()
    /** We asked; waiting for them. */
    data class Requested(val ts: Long) : PairState()
    /** They asked; waiting for the user. */
    data class Incoming(val ts: Long) : PairState()
}

data class Trusted(val id: String, val name: String, val type: String, val cert: ByteArray)

interface TrustStore {
    fun load(): List<Trusted>
    fun save(t: Trusted)
    fun remove(id: String)
}

/** The app side: storage, notifications, and handling of feature packets. */
interface Host {
    fun log(msg: String)
    fun changed()
    fun pairRequest(dev: Device, code: String)
    fun paired(dev: Device)
    fun unpaired(dev: Device)
    fun connected(dev: Device)
    /** A non-pairing packet from a paired device. Called on the link's reader thread. */
    fun packet(dev: Device, p: Packet)
}

class Link(val socket: SSLSocket, val generation: Long) {
    private val out = socket.outputStream
    private val writer = Executors.newSingleThreadExecutor()
    val remote: InetAddress get() = socket.inetAddress
    val local: InetAddress get() = socket.localAddress

    fun send(p: Packet): Boolean {
        if (writer.isShutdown) return false
        writer.execute {
            try {
                out.write(p.serialize())
                out.flush()
            } catch (e: IOException) {
                close()
            }
        }
        return true
    }

    fun close() {
        writer.shutdown()
        try { socket.close() } catch (_: IOException) {}
    }
}

class Device(val id: String) {
    @Volatile var name: String = id
    @Volatile var type: String = "unknown"
    @Volatile var incoming: Set<String> = emptySet()
    /** Cert on the current link. */
    @Volatile var cert: ByteArray? = null
    /** Cert pinned at pairing; non-null = paired. */
    @Volatile var trusted: ByteArray? = null
    @Volatile var link: Link? = null
    @Volatile var pair: PairState = PairState.None

    val paired get() = trusted != null
    val online get() = link != null
    fun accepts(type: String) = type in incoming
    fun send(p: Packet) = link?.send(p) ?: false
}

class Core(val identity: Identity, private val trust: TrustStore, private val host: Host) {
    val devices = ConcurrentHashMap<String, Device>()
    val tls = Tls(identity)
    @Volatile var tcpPort = 0
        private set
    private var server: ServerSocket? = null
    private var udp: DatagramSocket? = null
    private val timers = Executors.newSingleThreadScheduledExecutor()
    private val connecting = ConcurrentHashMap.newKeySet<String>()
    private var generation = 0L
    @Volatile private var running = false

    init {
        for (t in trust.load()) {
            devices[t.id] = Device(t.id).apply { name = t.name; type = t.type; trusted = t.cert }
        }
    }

    /** Platform hook for socket options plain Java can't set (keepalive timing). */
    var tune: (Socket) -> Unit = {}

    // ---- lifecycle -----------------------------------------------------------

    fun start(discovery: Boolean = true) {
        if (running) return
        running = true
        val ss = Proto.TCP_PORTS.firstNotNullOfOrNull { port ->
            try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } } catch (_: IOException) { null }
        } ?: error("no free TCP port in 1716-1764")
        server = ss
        tcpPort = ss.localPort
        thread(name = "lanlink-accept", isDaemon = true) {
            while (running) {
                val s = try { ss.accept() } catch (_: IOException) { break }
                thread(isDaemon = true) {
                    try { incoming(s) } catch (e: Exception) { host.log("incoming ${s.inetAddress}: $e"); s.closeQuietly() }
                }
            }
        }
        if (discovery) startDiscovery()
        host.log("listening on tcp $tcpPort")
    }

    fun stop() {
        running = false
        server?.closeQuietly()
        udp?.close()
        for (d in devices.values) d.link?.close()
    }

    private fun startDiscovery() {
        val sock = try {
            DatagramSocket(null).apply { reuseAddress = true; broadcast = true; bind(InetSocketAddress(Proto.UDP_PORT)) }
        } catch (e: IOException) {
            host.log("discovery: can't bind udp ${Proto.UDP_PORT}: $e"); return
        }
        udp = sock
        thread(name = "lanlink-udp", isDaemon = true) {
            val buf = ByteArray(64 * 1024)
            while (running) {
                val dp = DatagramPacket(buf, buf.size)
                try { sock.receive(dp) } catch (_: IOException) { break }
                val p = try { Packet.parse(String(dp.data, 0, dp.length, Charsets.UTF_8)) } catch (_: Exception) { continue }
                if (p.type != Proto.IDENTITY) continue
                val id = p.str("deviceId") ?: continue
                val port = p.long("tcpPort")?.toInt() ?: continue
                if (id == identity.deviceId || devices[id]?.online == true || !connecting.add(id)) continue
                thread(isDaemon = true) {
                    try { connect(InetSocketAddress(dp.address, port), p.body) } catch (e: Exception) { host.log("connect ${dp.address}: $e") }
                    finally { connecting.remove(id) }
                }
            }
        }
        timers.scheduleWithFixedDelay({ broadcast() }, 0, 60, TimeUnit.SECONDS)
    }

    /** Announce ourselves; desktops that hear it connect to us. Also unicasts to [extra] addresses. */
    fun broadcast(extra: List<String> = emptyList()) {
        if (!running) return
        thread(isDaemon = true) {
            val data = Packet.identity(identity, tcpPort).serialize()
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    val targets = listOf("255.255.255.255") + extra
                    for (t in targets) {
                        try { s.send(DatagramPacket(data, data.size, InetAddress.getByName(t), Proto.UDP_PORT)) } catch (_: IOException) {}
                    }
                }
            } catch (e: IOException) {
                host.log("broadcast: $e")
            }
        }
    }

    // ---- connections ---------------------------------------------------------

    /** They connected to us: read their plaintext identity, then we're the TLS client. */
    private fun incoming(s: Socket) {
        s.keepAlive = true
        tune(s)
        s.soTimeout = 10_000
        val p = Packet.parse(readRawLine(s.getInputStream()))
        require(p.type == Proto.IDENTITY) { "expected identity, got ${p.type}" }
        if (p.str("deviceId") == identity.deviceId) { s.close(); return }
        runLink(tls.client(s), p.body)
    }

    /** We connect to them: send our identity, then we're the TLS server. Blocks for the link's lifetime. */
    fun connect(addr: InetSocketAddress, pre: JSONObject? = null) {
        val s = Socket()
        try {
            s.connect(addr, 5_000)
            s.keepAlive = true
            tune(s)
            s.getOutputStream().write(Packet.identity(identity, tcpPort).serialize())
            runLink(tls.server(s), pre)
        } catch (e: Exception) {
            s.closeQuietly()
            throw e
        }
    }

    fun connectAsync(host: String, port: Int = Proto.UDP_PORT) {
        thread(isDaemon = true) {
            try { connect(InetSocketAddress(host, port)) } catch (e: Exception) { this.host.log("connect $host: $e") }
        }
    }

    private fun runLink(ssl: SSLSocket, pre: JSONObject?) {
        val cert = Tls.peerCert(ssl)
        val version = pre?.optInt("protocolVersion", Proto.VERSION) ?: Proto.VERSION
        val reader = BufferedReader(InputStreamReader(ssl.inputStream, Charsets.UTF_8))
        val body = if (version >= 8) {
            ssl.outputStream.write(Packet.identity(identity, null).serialize())
            ssl.outputStream.flush()
            ssl.soTimeout = 10_000
            val p = Packet.parse(reader.readLine() ?: error("closed during identity"))
            ssl.soTimeout = 0
            require(p.type == Proto.IDENTITY) { "expected identity inside TLS" }
            p.body
        } else {
            pre ?: error("no identity")
        }
        val id = body.optString("deviceId")
        if (id == identity.deviceId) { ssl.close(); return }
        require(Proto.validDeviceId(id) || version < 8) { "invalid device id $id" }
        require(version < 8 || Tls.commonName(cert) == id) { "certificate CN doesn't match $id" }

        val dev = devices.getOrPut(id) { Device(id) }
        val t = dev.trusted
        if (t != null && !t.contentEquals(cert)) {
            ssl.close()
            error("${dev.name} presented a different certificate than when paired; refusing")
        }
        val link: Link
        synchronized(this) {
            link = Link(ssl, ++generation)
            dev.link?.close() // newer link wins
            dev.name = Proto.sanitizeName(body.optString("deviceName", id))
            dev.type = body.optString("deviceType", "unknown")
            val caps = body.optJSONArray("incomingCapabilities")
            dev.incoming = if (caps == null) emptySet() else (0 until caps.length()).map { caps.optString(it) }.toSet()
            dev.cert = cert
            dev.link = link
            dev.pair = PairState.None
            if (dev.paired) trust.save(Trusted(id, dev.name, dev.type, cert))
        }
        host.log("connected ${dev.name} (${ssl.inetAddress.hostAddress})")
        host.changed()
        if (dev.paired) host.connected(dev)
        try {
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val p = try { Packet.parse(line) } catch (e: Exception) { host.log("bad packet: $e"); continue }
                try { handle(dev, p) } catch (e: Exception) { host.log("handling ${p.type}: $e") }
            }
        } catch (_: IOException) {
        } finally {
            link.close()
            synchronized(this) {
                if (dev.link === link) {
                    dev.link = null
                    dev.pair = PairState.None
                    if (!dev.paired) devices.remove(id)
                }
            }
            host.log("disconnected ${dev.name}")
            host.changed()
        }
    }

    // ---- packets & pairing ---------------------------------------------------

    private fun handle(dev: Device, p: Packet) {
        if (p.type == Proto.PAIR) return handlePair(dev, p)
        if (!dev.paired) {
            dev.send(Packet.of(Proto.PAIR, "pair" to false))
            return
        }
        host.packet(dev, p)
    }

    private fun handlePair(dev: Device, p: Packet) {
        if (!p.bool("pair")) {
            val was = dev.paired
            dev.pair = PairState.None
            if (was) {
                forget(dev)
                host.unpaired(dev)
            }
            host.changed()
            return
        }
        val st = dev.pair
        when {
            st is PairState.Requested -> {
                trustNow(dev)
                host.paired(dev)
                host.connected(dev)
            }
            dev.paired -> dev.send(Packet.of(Proto.PAIR, "pair" to true))
            st is PairState.Incoming -> {}
            else -> {
                val ts = p.long("timestamp") ?: (System.currentTimeMillis() / 1000)
                val state = PairState.Incoming(ts)
                dev.pair = state
                expire(dev, state)
                host.pairRequest(dev, code(dev) ?: "")
            }
        }
        host.changed()
    }

    private fun trustNow(dev: Device) {
        val cert = dev.cert ?: return
        dev.trusted = cert
        dev.pair = PairState.None
        trust.save(Trusted(dev.id, dev.name, dev.type, cert))
    }

    private fun forget(dev: Device) {
        dev.trusted = null
        trust.remove(dev.id)
        if (!dev.online) devices.remove(dev.id)
    }

    private fun expire(dev: Device, state: PairState) {
        timers.schedule({
            if (dev.pair == state) {
                dev.pair = PairState.None
                host.changed()
            }
        }, 30, TimeUnit.SECONDS)
    }

    /** Verification code for a pending pair request, if any. */
    fun code(dev: Device): String? {
        val ts = when (val s = dev.pair) {
            is PairState.Requested -> s.ts
            is PairState.Incoming -> s.ts
            PairState.None -> return null
        }
        val c = dev.cert ?: return null
        return try { Tls.verificationKey(identity.cert, c, ts) } catch (_: Exception) { null }
    }

    fun pair(dev: Device): String? {
        if (dev.paired || !dev.online) return null
        val ts = System.currentTimeMillis() / 1000
        val state = PairState.Requested(ts)
        dev.pair = state
        dev.send(Packet.of(Proto.PAIR, "pair" to true, "timestamp" to ts))
        expire(dev, state)
        host.changed()
        return code(dev)
    }

    fun accept(dev: Device) {
        if (dev.pair !is PairState.Incoming) return
        dev.send(Packet.of(Proto.PAIR, "pair" to true))
        trustNow(dev)
        host.paired(dev)
        host.connected(dev)
        host.changed()
    }

    fun reject(dev: Device) {
        dev.pair = PairState.None
        dev.send(Packet.of(Proto.PAIR, "pair" to false))
        host.changed()
    }

    fun unpair(dev: Device) {
        dev.send(Packet.of(Proto.PAIR, "pair" to false))
        dev.pair = PairState.None
        forget(dev)
        host.changed()
    }

    // ---- payloads ------------------------------------------------------------

    /**
     * Send [p] with a payload: listen on a payload port, tell the peer, stream [input].
     * Blocking; call from a worker thread.
     */
    fun sendPayload(dev: Device, p: Packet, input: InputStream, size: Long, progress: (Long) -> Unit = {}) {
        val pinned = dev.trusted ?: error("not paired")
        val ss = Proto.PAYLOAD_PORTS.firstNotNullOfOrNull { port ->
            try { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } } catch (_: IOException) { null }
        } ?: error("no free payload port")
        ss.use {
            ss.soTimeout = 60_000
            p.payloadSize = size
            p.payloadPort = ss.localPort
            check(dev.send(p)) { "${dev.name} is offline" }
            val s = ss.accept()
            val ssl = tls.server(s)
            ssl.use {
                check(Tls.peerCert(ssl).contentEquals(pinned)) { "payload socket presented the wrong certificate" }
                ssl.soTimeout = 30_000
                copy(input, ssl.outputStream, size, progress)
                ssl.outputStream.flush()
            }
        }
    }

    /** Fetch the payload announced in [p] into [out]. Blocking. */
    fun receivePayload(dev: Device, p: Packet, out: OutputStream, progress: (Long) -> Unit = {}) {
        val pinned = dev.trusted ?: error("not paired")
        val ip = dev.link?.remote ?: error("${dev.name} went offline")
        require(p.payloadPort > 0) { "packet has no payload" }
        val s = Socket()
        s.connect(InetSocketAddress(ip, p.payloadPort), 10_000)
        val ssl = tls.client(s)
        ssl.use {
            check(Tls.peerCert(ssl).contentEquals(pinned)) { "payload socket presented the wrong certificate" }
            ssl.soTimeout = 30_000
            val got = copy(ssl.inputStream, out, p.payloadSize, progress)
            check(p.payloadSize < 0 || got == p.payloadSize) { "got $got of ${p.payloadSize} bytes" }
        }
    }

    private fun copy(input: InputStream, out: OutputStream, size: Long, progress: (Long) -> Unit): Long {
        val buf = ByteArray(64 * 1024)
        var done = 0L
        while (size < 0 || done < size) {
            val want = if (size < 0) buf.size else minOf(buf.size.toLong(), size - done).toInt()
            val n = try { input.read(buf, 0, want) } catch (e: IOException) { if (size < 0) -1 else throw e }
            if (n < 0) break
            out.write(buf, 0, n)
            done += n
            progress(done)
        }
        return done
    }

    companion object {
        /** Read one '\n'-terminated line without buffering past it (TLS bytes follow). */
        fun readRawLine(input: InputStream): String {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("connection closed")
                if (b == '\n'.code) return out.toString("UTF-8")
                out.write(b)
                if (out.size() > 64 * 1024) throw IOException("identity too long")
            }
        }
    }
}

private fun java.io.Closeable.closeQuietly() = try { close() } catch (_: IOException) {}
