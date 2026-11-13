package dev.lanlink

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Picks up songs from Audile's result notifications, which carry the track in their extras. */
class AudileListener : NotificationListenerService() {
    override fun onListenerConnected() {
        try { activeNotifications?.forEach(::take) } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = take(sbn)

    private fun take(sbn: StatusBarNotification) {
        if (!sbn.packageName.startsWith(Songs.AUDILE)) return
        val songs = App.instance.songs
        if (!songs.enabled) return
        val x = sbn.notification.extras
        fun f(k: String) = x.getString("${Songs.AUDILE}.track_metadata.$k") ?: ""
        val title = f("title").ifBlank { return }
        songs.add(listOf(Songs.Song(title, f("artist"), f("album"), f("isrc"))))
    }
}
