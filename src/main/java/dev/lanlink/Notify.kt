package dev.lanlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri

object Notify {
    const val SERVICE = "service"
    const val PAIR = "pair"
    const val TRANSFER = "transfer"
    const val MISC = "misc"
    const val SERVICE_ID = 1

    fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(SERVICE, "Running in background", NotificationManager.IMPORTANCE_MIN),
            NotificationChannel(PAIR, "Pair requests", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(TRANSFER, "File transfers", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(MISC, "Pings, links and text", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun builder(ctx: Context, channel: String) =
        Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat)

    private fun activity(ctx: Context, req: Int, i: Intent) =
        PendingIntent.getActivity(ctx, req, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun service(ctx: Context, online: Int): Notification =
        builder(ctx, SERVICE)
            .setContentTitle(if (online == 0) "No devices connected" else "$online device${if (online == 1) "" else "s"} connected")
            .setContentIntent(activity(ctx, 0, Intent(ctx, MainActivity::class.java)))
            .setOngoing(true)
            .build()

    fun updateService(ctx: Context, online: Int) = nm(ctx).notify(SERVICE_ID, service(ctx, online))

    private fun idFor(device: String, salt: Int = 0) = (device.hashCode() and 0xffff) * 16 + salt + 100

    fun pairRequest(ctx: Context, device: String, name: String, code: String) {
        fun action(a: String) = PendingIntent.getBroadcast(
            ctx, idFor(device) + a.length,
            Intent(ctx, ActionReceiver::class.java).setAction(a).putExtra("device", device),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = builder(ctx, PAIR)
            .setContentTitle("Pair request from $name")
            .setContentText("Check both screens show $code")
            .setContentIntent(activity(ctx, idFor(device), DeviceActivity.intent(ctx, device)))
            .addAction(Notification.Action.Builder(null, "Accept", action(ActionReceiver.ACCEPT)).build())
            .addAction(Notification.Action.Builder(null, "Reject", action(ActionReceiver.REJECT)).build())
            .setTimeoutAfter(30_000)
            .setAutoCancel(true)
            .build()
        nm(ctx).notify(idFor(device), n)
    }

    fun cancelPair(ctx: Context, device: String) = nm(ctx).cancel(idFor(device))

    fun simple(ctx: Context, title: String, text: String, tap: Intent? = null) {
        val b = builder(ctx, MISC).setContentTitle(title).setContentText(text).setAutoCancel(true)
            .setStyle(Notification.BigTextStyle().bigText(text))
        if (tap != null) b.setContentIntent(activity(ctx, (title + text).hashCode(), tap))
        nm(ctx).notify((title + text).hashCode(), b.build())
    }

    fun progress(ctx: Context, id: Int, title: String, done: Long, total: Long) {
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val n = builder(ctx, TRANSFER).setContentTitle(title).setProgress(100, pct, total <= 0)
            .setOngoing(true).setOnlyAlertOnce(true).build()
        nm(ctx).notify(id, n)
    }

    fun finished(ctx: Context, id: Int, title: String, text: String, open: Uri? = null, mime: String? = null) {
        val b = builder(ctx, TRANSFER).setContentTitle(title).setContentText(text).setAutoCancel(true)
        if (open != null) {
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(open, mime ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            b.setContentIntent(activity(ctx, id, i))
        }
        nm(ctx).notify(id, b.build())
    }
}

/** Accept/Reject buttons on pair notifications. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val app = App.instance
        val dev = app.core.devices[i.getStringExtra("device") ?: return] ?: return
        when (i.action) {
            ACCEPT -> app.core.accept(dev)
            REJECT -> app.core.reject(dev)
        }
        Notify.cancelPair(ctx, dev.id)
    }

    companion object {
        const val ACCEPT = "dev.lanlink.ACCEPT"
        const val REJECT = "dev.lanlink.REJECT"
    }
}
