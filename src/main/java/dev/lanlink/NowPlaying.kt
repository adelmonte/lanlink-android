package dev.lanlink

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import dev.lanlink.core.Packet
import dev.lanlink.core.Proto

/**
 * Shows the computer's current player in Android's own media controls (quick
 * settings, lock screen, headset buttons) through a MediaSession. Main thread only.
 */
class NowPlaying(private val app: App) {
    private var session: MediaSession? = null
    private var device: String? = null
    private var player: String? = null
    private var shown: String? = null

    private val callback = object : MediaSession.Callback() {
        override fun onPlay() = action("Play")
        override fun onPause() = action("Pause")
        override fun onStop() = action("Stop")
        override fun onSkipToNext() = action("Next")
        override fun onSkipToPrevious() = action("Previous")
        override fun onSeekTo(pos: Long) = request("SetPosition" to pos)
    }

    fun action(a: String) {
        val d = device ?: return
        val p = player ?: return
        app.plugins.optimistic(d, p, a)
        request("action" to a)
    }

    private fun request(vararg kv: Pair<String, Any?>) {
        val p = player ?: return
        app.core.devices[device ?: return]?.send(Packet.of(Proto.MPRIS_REQUEST, "player" to p, *kv))
    }

    /** Prefer whatever is playing; otherwise stay on the player already shown. */
    private fun pick(): Pair<String, String>? {
        val candidates = mutableListOf<Pair<String, String>>()
        for ((id, m) in app.plugins.media) {
            val d = app.core.devices[id] ?: continue
            if (!d.paired || !d.online) continue
            for (name in m.players) {
                val st = m.status[name] ?: continue
                if (st.optString("title").isEmpty() && st.optString("nowPlaying").isEmpty()) continue
                if (st.optBoolean("isPlaying")) return Pair(id, name)
                candidates += Pair(id, name)
            }
        }
        return candidates.firstOrNull { it.first == device && it.second == player } ?: candidates.firstOrNull()
    }

    fun update() {
        val cur = pick() ?: return clear()
        val dev = app.core.devices[cur.first] ?: return clear()
        val m = app.plugins.media[cur.first] ?: return clear()
        val st = m.status[cur.second] ?: return clear()
        val received = m.receivedAt[cur.second] ?: System.currentTimeMillis()
        val cover = app.plugins.artFor(st.optString("albumArtUrl"))
        val sig = "${cur.first}|${cur.second}|$received|${cover != null}"
        if (sig == shown) return
        shown = sig
        device = cur.first
        player = cur.second

        val title = st.optString("title").ifEmpty { st.optString("nowPlaying") }
        val artist = st.optString("artist")
        val length = st.optLong("length", 0)
        val playing = st.optBoolean("isPlaying")

        val s = session ?: MediaSession(app, "lanlink").also {
            it.setCallback(callback)
            session = it
        }
        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, st.optString("album"))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (length > 0) length else -1)
                .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover)
                .build()
        )
        var actions = PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
        if (st.optBoolean("canGoNext")) actions = actions or PlaybackState.ACTION_SKIP_TO_NEXT
        if (st.optBoolean("canGoPrevious")) actions = actions or PlaybackState.ACTION_SKIP_TO_PREVIOUS
        if (st.optBoolean("canSeek") && length > 0) actions = actions or PlaybackState.ACTION_SEEK_TO
        val age = System.currentTimeMillis() - received
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    st.optLong("pos", 0),
                    if (playing) 1f else 0f,
                    SystemClock.elapsedRealtime() - age,
                )
                .build()
        )
        s.isActive = true

        fun button(icon: Int, label: String, act: String): Notification.Action {
            val pi = PendingIntent.getBroadcast(
                app, act.hashCode(),
                Intent(app, ActionReceiver::class.java).setAction(ActionReceiver.MEDIA).putExtra("do", act),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return Notification.Action.Builder(Icon.createWithResource(app, icon), label, pi).build()
        }
        val n = Notification.Builder(app, Notify.MEDIA)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(artist)
            .setSubText(dev.name)
            .setLargeIcon(cover)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(button(android.R.drawable.ic_media_previous, "Previous", "Previous"))
            .addAction(
                if (playing) button(android.R.drawable.ic_media_pause, "Pause", "Pause")
                else button(android.R.drawable.ic_media_play, "Play", "Play")
            )
            .addAction(button(android.R.drawable.ic_media_next, "Next", "Next"))
            .setContentIntent(
                PendingIntent.getActivity(
                    app, 2, MediaActivity.intent(app, cur.first).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            )
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(playing)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(Notify.MEDIA_ID, n)
    }

    private fun clear() {
        if (session == null) return
        app.getSystemService(NotificationManager::class.java).cancel(Notify.MEDIA_ID)
        session?.release()
        session = null
        device = null
        player = null
        shown = null
    }
}
