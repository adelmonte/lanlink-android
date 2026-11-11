package dev.lanlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.webkit.MimeTypeMap
import dev.lanlink.core.Core
import dev.lanlink.core.Device
import dev.lanlink.core.Host
import dev.lanlink.core.Packet
import dev.lanlink.core.Proto
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Everything Android-specific the core calls into, plus the phone's features. */
class Plugins(private val app: App) : Host {
    lateinit var core: Core
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()
    private val notifIds = AtomicInteger(1000)

    /** UI callbacks, run on the main thread whenever device state changes. */
    val listeners = CopyOnWriteArraySet<() -> Unit>()

    /** Desktop media players, per device. */
    class Media {
        @Volatile var players: List<String> = emptyList()
        val status = ConcurrentHashMap<String, JSONObject>()
        val receivedAt = ConcurrentHashMap<String, Long>()
    }
    val media = ConcurrentHashMap<String, Media>()
    val nowPlaying = NowPlaying(app)

    override fun log(msg: String) { Log.i("lanlink", msg) }

    override fun changed() {
        main.post {
            listeners.forEach { it() }
            val online = core.devices.values.count { it.online && it.paired }
            if (LinkService.running) Notify.updateService(app, online)
            LinkService.instance?.holdWifi(online > 0)
            nowPlaying.update()
        }
    }

    override fun pairRequest(dev: Device, code: String) = Notify.pairRequest(app, dev.id, dev.name, code)
    override fun paired(dev: Device) = Notify.cancelPair(app, dev.id)
    override fun unpaired(dev: Device) { media.remove(dev.id) }
    override fun connected(dev: Device) {
        sendBattery(dev)
        // Subscribes us to player changes, so media controls work without opening the app.
        if (dev.accepts(Proto.MPRIS_REQUEST)) dev.send(Packet.of(Proto.MPRIS_REQUEST, "requestPlayerList" to true))
    }

    override fun packet(dev: Device, p: Packet) {
        when (p.type) {
            Proto.PING -> Notify.simple(app, dev.name, p.str("message") ?: "Ping!")
            Proto.SHARE -> receiveShare(dev, p)
            Proto.SFTP_REQUEST -> if (p.bool("startBrowsing")) startSftp(dev)
            Proto.MPRIS -> mediaUpdate(dev, p)
        }
    }

    // ---- battery ---------------------------------------------------------------

    private var lastBattery: Pair<Int, Boolean>? = null

    private fun battery(): Pair<Int, Boolean>? {
        val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return null
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return Pair(level * 100 / scale.coerceAtLeast(1), plugged)
    }

    private fun batteryPacket(b: Pair<Int, Boolean>) =
        Packet.of(Proto.BATTERY, "currentCharge" to b.first, "isCharging" to b.second, "thresholdEvent" to 0)

    fun sendBattery(dev: Device) { battery()?.let { dev.send(batteryPacket(it)) } }

    fun batteryChanged() {
        val b = battery() ?: return
        if (b == lastBattery) return
        lastBattery = b
        core.devices.values.filter { it.paired && it.online }.forEach { it.send(batteryPacket(b)) }
    }

    // ---- share -----------------------------------------------------------------

    private fun receiveShare(dev: Device, p: Packet) {
        p.str("text")?.let { text ->
            main.post {
                app.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("lanlink", text))
            }
            Notify.simple(app, "Text from ${dev.name}", "Copied to clipboard: $text")
            return
        }
        p.str("url")?.let { url ->
            Notify.simple(app, "Link from ${dev.name}", url, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            return
        }
        val name = p.str("filename")?.substringAfterLast('/')?.ifBlank { null } ?: return
        if (p.payloadPort <= 0) return
        worker.execute { download(dev, p, name) }
    }

    private fun mimeOf(name: String) =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    private fun download(dev: Device, p: Packet, name: String) {
        val nid = notifIds.incrementAndGet()
        val mime = mimeOf(name)
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        try {
            resolver.openOutputStream(uri)!!.use { out ->
                core.receivePayload(dev, p, out, throttled(nid, "Receiving $name", p.payloadSize))
            }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            Notify.finished(app, nid, "Received $name", "From ${dev.name} · in Downloads", uri, mime)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            Notify.finished(app, nid, "Failed to receive $name", e.message ?: e.toString())
        }
    }

    private fun throttled(nid: Int, title: String, total: Long): (Long) -> Unit {
        var lastPct = -1L
        return { done ->
            val pct = if (total > 0) done * 100 / total else 0
            if (pct != lastPct) {
                lastPct = pct
                Notify.progress(app, nid, title, done, total)
            }
        }
    }

    private class Item(val name: String, val size: Long, val stream: InputStream)

    /**
     * Send files. Streams are opened here, on the caller's thread, so that
     * temporary URI grants from a share intent are used before they expire.
     */
    fun sendFiles(dev: Device, uris: List<Uri>) {
        val items = uris.mapNotNull { uri ->
            try {
                var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                var size = -1L
                app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
                Item(name, size, app.contentResolver.openInputStream(uri) ?: return@mapNotNull null)
            } catch (e: Exception) {
                log("can't open $uri: $e"); null
            }
        }
        val total = items.sumOf { it.size.coerceAtLeast(0) }
        worker.execute {
            for (it in items) {
                val nid = notifIds.incrementAndGet()
                try {
                    it.stream.use { s ->
                        val p = Packet.of(Proto.SHARE, "filename" to it.name, "numberOfFiles" to items.size, "totalPayloadSize" to total)
                        core.sendPayload(dev, p, s, it.size, throttled(nid, "Sending ${it.name}", it.size))
                    }
                    Notify.finished(app, nid, "Sent ${it.name}", "To ${dev.name}")
                } catch (e: Exception) {
                    Notify.finished(app, nid, "Failed to send ${it.name}", e.message ?: e.toString())
                }
            }
        }
    }

    fun sendText(dev: Device, text: String) {
        val t = text.trim()
        val isUrl = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://\\S+$").matches(t)
        dev.send(if (isUrl) Packet.of(Proto.SHARE, "url" to t) else Packet.of(Proto.SHARE, "text" to text))
    }

    /** Text meant for the other clipboard: sent as text even when it looks like a link. */
    fun sendClip(dev: Device, text: String) = dev.send(Packet.of(Proto.SHARE, "text" to text))

    // ---- sftp ------------------------------------------------------------------

    private fun startSftp(dev: Device) {
        if (!Environment.isExternalStorageManager()) {
            dev.send(Packet.of(Proto.SFTP, "errorMessage" to "Allow \"All files access\" for lanlink on the phone"))
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${app.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            Notify.simple(app, "${dev.name} wants to browse files", "Tap to allow file access for lanlink", i)
            return
        }
        worker.execute {
            try {
                val port = Sftp.start(app)
                dev.send(Packet.of(
                    Proto.SFTP,
                    "ip" to dev.link?.local?.hostAddress,
                    "port" to port,
                    "user" to Sftp.USER,
                    "password" to Sftp.password,
                    "path" to "/",
                    "multiPaths" to JSONArray(listOf("/")),
                    "pathNames" to JSONArray(listOf("Internal storage")),
                ))
            } catch (e: Exception) {
                log("sftp: $e")
                dev.send(Packet.of(Proto.SFTP, "errorMessage" to "Couldn't start file sharing: ${e.message}"))
            }
        }
    }

    // ---- media -----------------------------------------------------------------

    private fun mediaUpdate(dev: Device, p: Packet) {
        val m = media.getOrPut(dev.id) { Media() }
        p.body.optJSONArray("playerList")?.let { a -> m.players = (0 until a.length()).map { a.getString(it) } }
        p.str("player")?.let { name ->
            val merged = m.status[name] ?: JSONObject()
            for (k in p.body.keys()) merged.put(k, p.body.get(k))
            m.status[name] = merged
            m.receivedAt[name] = System.currentTimeMillis()
        }
        changed()
    }
}
