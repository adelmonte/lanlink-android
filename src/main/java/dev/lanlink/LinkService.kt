package dev.lanlink

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import kotlin.concurrent.thread

/** Keeps connections alive while the app isn't open. */
class LinkService : Service() {
    private var multicast: WifiManager.MulticastLock? = null
    private var wifi: WifiManager.WifiLock? = null
    private val net = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        // Links over a network that's gone would otherwise look connected forever.
        override fun onLost(network: Network) = App.instance.core.devices.values.forEach { it.link?.close() }
    }
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = App.instance.plugins.batteryChanged()
    }

    override fun onBind(i: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        running = true
        instance = this
        startForeground(Notify.SERVICE_ID, Notify.service(this, 0), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        multicast = getSystemService(WifiManager::class.java).createMulticastLock("lanlink").apply {
            setReferenceCounted(false)
            acquire()
        }
        @Suppress("DEPRECATION") // the replacement mode only applies while the screen is on
        wifi = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lanlink").apply {
            setReferenceCounted(false)
        }
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        thread {
            try { App.instance.core.start() } catch (e: Exception) { App.instance.plugins.log("start: $e") }
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(net)
            refresh()
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == REFRESH) refresh()
        return START_STICKY
    }

    private fun refresh() {
        val app = App.instance
        val hosts = app.manualHosts
        app.core.broadcast(hosts)
        val online = app.core.devices.values.mapNotNull { it.link?.remote?.hostAddress }.toSet()
        for (h in hosts) if (h !in online) app.core.connectAsync(h)
    }

    /** Keep Wi-Fi out of power save while a computer is connected, so the link survives the screen turning off. */
    fun holdWifi(hold: Boolean) {
        val w = wifi ?: return
        if (hold && !w.isHeld) w.acquire() else if (!hold && w.isHeld) w.release()
    }

    override fun onDestroy() {
        running = false
        instance = null
        wifi?.let { if (it.isHeld) it.release() }
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(net) } catch (_: Exception) {}
        unregisterReceiver(batteryReceiver)
        multicast?.release()
        Sftp.stop()
        App.instance.core.stop()
        super.onDestroy()
    }

    companion object {
        const val REFRESH = "dev.lanlink.REFRESH"
        @Volatile var running = false
        @Volatile var instance: LinkService? = null

        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, LinkService::class.java))
        fun refresh(ctx: Context) = ctx.startForegroundService(Intent(ctx, LinkService::class.java).setAction(REFRESH))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED || i.action == Intent.ACTION_MY_PACKAGE_REPLACED) LinkService.start(ctx)
    }
}
