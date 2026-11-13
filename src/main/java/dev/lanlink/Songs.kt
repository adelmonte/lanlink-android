package dev.lanlink

import android.content.ComponentName
import android.provider.Settings
import dev.lanlink.core.Packet
import dev.lanlink.core.Proto
import org.json.JSONArray
import org.json.JSONObject

/**
 * Songs Audile recognized, on their way to the computer, which likes them in
 * music-tui. Each song goes once; ones found while no computer is connected
 * wait here until one is.
 */
class Songs(private val app: App) {
    private val prefs get() = app.getSharedPreferences("songs", android.content.Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Whether Android lets us read Audile's notifications. */
    fun hasAccess(): Boolean {
        val me = ComponentName(app, AudileListener::class.java).flattenToString()
        return Settings.Secure.getString(app.contentResolver, "enabled_notification_listeners")?.split(':')?.contains(me) == true
    }

    val waiting: Int @Synchronized get() = pending().length()

    /** Queue songs not seen before and send what can be sent. Returns how many were new. */
    @Synchronized
    fun add(songs: List<Song>): Int {
        val seen = seen()
        val pending = pending()
        var added = 0
        for (s in songs) {
            if (s.title.isBlank() || s.artist.isBlank() || !seen.add(s.key)) continue
            pending.put(s.json())
            added++
        }
        if (added > 0) prefs.edit().putString("seen", JSONArray(seen.toList()).toString()).putString("pending", pending.toString()).apply()
        flush()
        return added
    }

    /** Send everything waiting to each connected computer that takes songs. */
    @Synchronized
    fun flush() {
        val pending = pending()
        if (pending.length() == 0) return
        val devs = app.core.devices.values.filter { it.paired && it.online && it.accepts(Proto.SONG) }
        if (devs.isEmpty()) return
        val left = JSONArray()
        for (i in 0 until pending.length()) {
            val o = pending.getJSONObject(i)
            val p = { Packet.of(Proto.SONG, "title" to o.optString("title"), "artist" to o.optString("artist"), "album" to o.optString("album"), "isrc" to o.optString("isrc")) }
            if (devs.map { it.send(p()) }.none { it }) left.put(o)
        }
        prefs.edit().putString("pending", left.toString()).apply()
    }

    private fun pending() = JSONArray(prefs.getString("pending", "[]"))

    private fun seen(): MutableSet<String> =
        JSONArray(prefs.getString("seen", "[]")).let { a -> (0 until a.length()).mapTo(LinkedHashSet()) { a.getString(it) } }

    class Song(val title: String, val artist: String, val album: String = "", val isrc: String = "") {
        val key get() = "${artist.trim().lowercase()}\t${title.trim().lowercase()}"
        fun json() = JSONObject().put("title", title.trim()).put("artist", artist.trim()).put("album", album.trim()).put("isrc", isrc.trim())
    }

    companion object {
        const val AUDILE = "com.mrsep.musicrecognizer"

        /** Songs from an Audile CSV export; needs the header row Audile writes by default. */
        fun fromCsv(text: String): List<Song> {
            val rows = csv(text)
            val head = rows.firstOrNull()?.map { it.trim().lowercase() } ?: return emptyList()
            fun col(name: String) = head.indexOf(name).takeIf { it >= 0 } ?: head.indexOfFirst { it.contains(name) }
            val (t, a, al, i) = listOf(col("title"), col("artist"), col("album"), col("isrc"))
            if (t < 0 || a < 0) return emptyList()
            return rows.drop(1).map { r ->
                fun f(c: Int) = r.getOrNull(c)?.takeIf { c >= 0 } ?: ""
                Song(f(t), f(a), f(al), f(i))
            }
        }

        /** RFC 4180: quoted fields may hold commas, quotes ("") and newlines. */
        private fun csv(text: String): List<List<String>> {
            val rows = mutableListOf<List<String>>()
            var row = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                    c == '"' -> quoted = !quoted
                    quoted -> field.append(c)
                    c == ',' -> { row.add(field.toString()); field.clear() }
                    c == '\n' || c == '\r' -> {
                        if (c == '\r' && text.getOrNull(i + 1) == '\n') i++
                        row.add(field.toString()); field.clear()
                        if (row.any { it.isNotEmpty() }) rows.add(row)
                        row = mutableListOf()
                    }
                    else -> field.append(c)
                }
                i++
            }
            row.add(field.toString())
            if (row.any { it.isNotEmpty() }) rows.add(row)
            return rows
        }
    }
}
