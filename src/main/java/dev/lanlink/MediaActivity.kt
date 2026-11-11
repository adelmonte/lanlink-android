package dev.lanlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import dev.lanlink.core.Packet
import dev.lanlink.core.Proto

/** Remote for the desktop's media players (MPRIS on the other end). */
class MediaActivity : Activity() {
    private val app get() = App.instance
    private lateinit var id: String
    private lateinit var players: Spinner
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var cover: ImageView
    private lateinit var trackTitle: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var position: SeekBar
    private lateinit var time: TextView
    private lateinit var volume: SeekBar
    private lateinit var playPause: Button
    private var player: String? = null
    private var userSeeking = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { renderPosition(); ticker.postDelayed(this, 500) }
    }
    private val listener: () -> Unit = { render() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        id = intent.getStringExtra("device") ?: return finish()
        adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, mutableListOf())
        scrollColumn {
            players = Spinner(context).apply {
                this.adapter = this@MediaActivity.adapter
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, i: Long) {
                        val name = this@MediaActivity.adapter.getItem(pos)
                        if (name != player) {
                            player = name
                            request("requestNowPlaying" to true, "requestVolume" to true)
                            render()
                        }
                    }
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            }
            addView(players)
            space(16)
            cover = ImageView(context).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                visibility = View.GONE
            }
            addView(cover, LinearLayout.LayoutParams(context.dp(220), context.dp(220)).apply { gravity = Gravity.CENTER_HORIZONTAL })
            space(16)
            trackTitle = text("", 22f, bold = true)
            artist = text("", 17f)
            album = text("", 15f, dim = true)
            space(16)
            position = SeekBar(context)
            addView(position)
            time = text("", 13f, dim = true)
            space(8)
            addView(row(context) {
                button("⏮", 1f) { action("Previous") }
                playPause = button("⏯", 1f) { action("PlayPause") }
                button("⏭", 1f) { action("Next") }
            })
            space(16)
            text("Volume", 13f, dim = true)
            volume = SeekBar(context).apply { max = 100 }
            addView(volume)
        }
        position.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, v: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(b: SeekBar) { userSeeking = true }
            override fun onStopTrackingTouch(b: SeekBar) {
                userSeeking = false
                request("SetPosition" to b.progress.toLong())
            }
        })
        volume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) request("setVolume" to v) }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
    }

    override fun onStart() {
        super.onStart()
        app.plugins.listeners.add(listener)
        app.core.devices[id]?.send(Packet.of(Proto.MPRIS_REQUEST, "requestPlayerList" to true))
        render()
        ticker.post(tick)
    }

    override fun onStop() {
        app.plugins.listeners.remove(listener)
        ticker.removeCallbacks(tick)
        super.onStop()
    }

    private fun request(vararg kv: Pair<String, Any?>) {
        val p = player ?: return
        app.core.devices[id]?.send(Packet.of(Proto.MPRIS_REQUEST, "player" to p, *kv))
    }

    private fun action(a: String) = request("action" to a)

    private fun media() = app.plugins.media[id]

    private fun render() {
        val m = media()
        val names = m?.players ?: emptyList()
        if (names != (0 until adapter.count).map { adapter.getItem(it) }) {
            adapter.clear()
            adapter.addAll(names)
            if (player !in names) player = names.firstOrNull()?.also { request("requestNowPlaying" to true, "requestVolume" to true) }
            player?.let { players.setSelection(names.indexOf(it)) }
        }
        val st = player?.let { m?.status?.get(it) }
        if (st == null) {
            trackTitle.text = if (names.isEmpty()) "Nothing playing" else ""
            artist.text = if (names.isEmpty()) "Start a media player on the computer." else ""
            album.text = ""
            cover.visibility = View.GONE
            return
        }
        val art = app.plugins.artFor(st.optString("albumArtUrl"))
        cover.setImageBitmap(art)
        cover.visibility = if (art != null) View.VISIBLE else View.GONE
        trackTitle.text = st.optString("title").ifEmpty { st.optString("nowPlaying") }
        artist.text = st.optString("artist")
        album.text = st.optString("album")
        playPause.text = if (st.optBoolean("isPlaying")) "⏸" else "▶"
        position.isEnabled = st.optBoolean("canSeek")
        if (!volume.isPressed) volume.progress = st.optInt("volume", 100)
        renderPosition()
    }

    private fun renderPosition() {
        val p = player ?: return
        val m = media() ?: return
        val st = m.status[p] ?: return
        val len = st.optLong("length", 0)
        var pos = st.optLong("pos", 0)
        if (st.optBoolean("isPlaying")) pos += System.currentTimeMillis() - (m.receivedAt[p] ?: System.currentTimeMillis())
        if (len > 0) pos = pos.coerceAtMost(len)
        position.max = len.coerceAtLeast(1).toInt()
        if (!userSeeking) position.progress = pos.toInt()
        time.text = if (len > 0) "${fmt(pos)} / ${fmt(len)}" else fmt(pos)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    companion object {
        fun intent(ctx: Context, id: String) = Intent(ctx, MediaActivity::class.java).putExtra("device", id)
    }
}
