package dev.lanlink.core

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/** Runs the Kotlin core against the real Rust daemon. Set LANLINK_BIN to enable. */
class InteropTest {
    private val bin = System.getenv("LANLINK_BIN")
    private val tmp = File(System.getProperty("java.io.tmpdir"), "lanlink-interop-${System.nanoTime()}")

    class TestIdentity(dir: File) : Identity {
        override val deviceId = UUID.randomUUID().toString().replace("-", "")
        override val name = "testphone"
        override val type = "phone"
        override val cert: X509Certificate
        override val key: PrivateKey
        init {
            val ks = File(dir, "id.p12")
            val p = ProcessBuilder("keytool", "-genkeypair", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=$deviceId, OU=KDE Connect, O=KDE", "-validity", "3650",
                "-alias", "k", "-keystore", ks.path, "-storetype", "PKCS12", "-storepass", "pw123456").inheritIO().start()
            check(p.waitFor() == 0)
            val store = KeyStore.getInstance("PKCS12").apply { ks.inputStream().use { load(it, "pw123456".toCharArray()) } }
            cert = store.getCertificate("k") as X509Certificate
            key = store.getKey("k", "pw123456".toCharArray()) as PrivateKey
        }
    }

    class MemTrust : TrustStore {
        val m = mutableMapOf<String, Trusted>()
        override fun load() = m.values.toList()
        override fun save(t: Trusted) { m[t.id] = t }
        override fun remove(id: String) { m.remove(id) }
    }

    inner class TestHost : Host {
        lateinit var core: Core
        val events = LinkedBlockingQueue<String>()
        val received = LinkedBlockingQueue<ByteArray>()
        override fun log(msg: String) = println("core: $msg")
        override fun changed() {}
        override fun pairRequest(dev: Device, code: String) { events.add("pairRequest:$code") }
        override fun paired(dev: Device) { events.add("paired") }
        override fun unpaired(dev: Device) { events.add("unpaired") }
        override fun connected(dev: Device) { events.add("connected") }
        override fun packet(dev: Device, p: Packet) {
            events.add("packet:${p.type}")
            if (p.type == Proto.SHARE && p.payloadPort > 0) thread {
                val out = ByteArrayOutputStream()
                core.receivePayload(dev, p, out)
                received.add(out.toByteArray())
            }
        }
    }

    private fun env(pb: ProcessBuilder): ProcessBuilder = pb.apply {
        environment()["XDG_CONFIG_HOME"] = File(tmp, "cfg").path
        environment()["XDG_RUNTIME_DIR"] = File(tmp, "run").path
        environment()["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=/nonexistent"
    }

    private fun cli(vararg args: String): String {
        val p = env(ProcessBuilder(bin, *args)).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        println("lanlink ${args.joinToString(" ")} -> $out")
        return out
    }

    private fun daemon(): Process = env(ProcessBuilder(bin, "daemon")).inheritIO().start().also { Thread.sleep(800) }

    private fun status(): JSONObject {
        val s = java.net.UnixDomainSocketAddress.of(File(tmp, "run/lanlink.sock").path)
        java.nio.channels.SocketChannel.open(s).use { ch ->
            ch.write(java.nio.ByteBuffer.wrap("{\"cmd\":\"status\"}\n".toByteArray()))
            val r = java.nio.channels.Channels.newInputStream(ch).bufferedReader().readLine()
            return JSONObject(r).getJSONObject("data")
        }
    }

    private fun waitFor(q: LinkedBlockingQueue<String>, prefix: String): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val e = q.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (e.startsWith(prefix)) return e
        }
        error("timed out waiting for $prefix")
    }

    @Test
    fun interop() {
        assumeTrue("set LANLINK_BIN to run", bin != null)
        File(tmp, "cfg/lanlink").mkdirs(); File(tmp, "run").mkdirs(); File(tmp, "dl").mkdirs()
        File(tmp, "run").setExecutable(true, true)
        File(tmp, "cfg/lanlink/config.toml").writeText(
            "name = \"desk\"\ntcp_port = 1730\ndiscovery = false\ndownload_dir = \"${File(tmp, "dl").path}\"\n")

        val host = TestHost()
        val core = Core(TestIdentity(tmp), MemTrust(), host)
        host.core = core
        core.start(discovery = false)
        var d = daemon()
        try {
            // 1. daemon connects to us (we are TLS client); we request pairing; daemon accepts.
            cli("connect", "127.0.0.1:${core.tcpPort}")
            Thread.sleep(800)
            val dev = core.devices.values.single()
            assertEquals("desk", dev.name)
            val ourCode = core.pair(dev)!!
            Thread.sleep(300)
            assertEquals(ourCode, status().getJSONArray("devices").getJSONObject(0).getString("code"))
            cli("accept", "testphone")
            waitFor(host.events, "paired")
            assertTrue(dev.paired)

            // 2. phone -> desktop file
            val data = Random.nextBytes(2_500_000)
            core.sendPayload(dev, Packet.of(Proto.SHARE, "filename" to "fromphone.bin"), data.inputStream(), data.size.toLong())
            Thread.sleep(500)
            assertArrayEquals(data, File(tmp, "dl/fromphone.bin").readBytes())

            // 3. desktop -> phone file
            val f = File(tmp, "fromdesk.bin").apply { writeBytes(Random.nextBytes(1_234_567)) }
            cli("send", "testphone", f.path)
            assertArrayEquals(f.readBytes(), host.received.poll(10, TimeUnit.SECONDS))

            // 4. ping desktop -> phone; unpair from the desktop
            cli("ping", "testphone")
            waitFor(host.events, "packet:${Proto.PING}")
            cli("unpair", "testphone")
            waitFor(host.events, "unpaired")
            assertTrue(!dev.paired)

            // 5. restart daemon; we connect to it (we are TLS server); daemon requests pairing; we accept.
            d.destroy(); d.waitFor()
            Thread.sleep(300)
            d = daemon()
            thread { try { core.connect(InetSocketAddress("127.0.0.1", 1730)) } catch (e: Exception) { println("connect: $e") } }
            Thread.sleep(800)
            val out = cli("pair", "testphone")
            val req = waitFor(host.events, "pairRequest:")
            assertTrue("codes match: $out vs $req", out.contains(req.removePrefix("pairRequest:")))
            val dev2 = core.devices.values.single()
            core.accept(dev2)
            Thread.sleep(500)
            val st = status().getJSONArray("devices").getJSONObject(0)
            assertTrue(st.getBoolean("paired") && st.getBoolean("online"))
            println("INTEROP OK")
        } finally {
            d.destroy()
            core.stop()
            tmp.deleteRecursively()
        }
    }
}
