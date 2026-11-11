package dev.lanlink

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import dev.lanlink.core.Packet
import dev.lanlink.core.PairState
import dev.lanlink.core.Proto
import dev.lanlink.core.Tls

class DeviceActivity : Activity() {
    private val app get() = App.instance
    private lateinit var id: String
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var actions: LinearLayout
    private val listener: () -> Unit = { render() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        id = intent.getStringExtra("device") ?: return finish()
        scrollColumn {
            title = text("", 26f, bold = true)
            status = text("", 15f, dim = true)
            space()
            actions = column(context)
            addView(actions)
        }
    }

    override fun onStart() {
        super.onStart()
        app.plugins.listeners.add(listener)
        render()
    }

    override fun onStop() {
        app.plugins.listeners.remove(listener)
        super.onStop()
    }

    private fun render() {
        val d = app.core.devices[id]
        if (d == null) {
            title.text = "Device gone"
            status.text = "It disconnected before pairing."
            actions.removeAllViews()
            return
        }
        title.text = d.name
        val fp = (d.trusted ?: d.cert)?.let { Tls.fingerprint(it) } ?: ""
        val code = app.core.code(d)
        status.text = buildString {
            append("${d.type} · ${stateText(d)}")
            d.link?.remote?.hostAddress?.let { append("\n$it") }
            if (fp.isNotEmpty()) append("\nFingerprint $fp")
            if (code != null) append("\n\nPair code: $code\nMake sure the other screen shows the same code.")
        }
        actions.removeAllViews()
        val a = actions
        when {
            d.pair is PairState.Incoming -> {
                a.button("Accept", primary = true) { app.core.accept(d); Notify.cancelPair(this, d.id) }
                a.button("Reject") { app.core.reject(d); Notify.cancelPair(this, d.id) }
            }
            d.pair is PairState.Requested -> a.text("Waiting for ${d.name} to accept…", dim = true)
            !d.paired && d.online -> a.button("Pair", primary = true) { app.core.pair(d) }
            d.paired && d.online -> {
                a.button("Send files", primary = true) {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true),
                        PICK,
                    )
                }
                a.button("Send clipboard") {
                    val clip = getSystemService(ClipboardManager::class.java).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                    if (text.isNullOrEmpty()) toast("Clipboard is empty") else { app.plugins.sendClip(d, text); toast("Sent") }
                }
                if (d.accepts(Proto.MOUSEPAD)) a.button("Touchpad & keyboard") { startActivity(TouchpadActivity.intent(this, id)) }
                if (d.accepts(Proto.MPRIS_REQUEST)) a.button("Media") { startActivity(MediaActivity.intent(this, id)) }
                a.button("Ping") { d.send(Packet.of(Proto.PING)); toast("Pinged") }
            }
            d.paired -> a.text("Offline. It reconnects automatically when both are on the same network.", dim = true)
        }
        if (d.paired) {
            a.space(24)
            a.button("Unpair") {
                AlertDialog.Builder(this).setTitle("Unpair ${d.name}?")
                    .setPositiveButton("Unpair") { _, _ -> app.core.unpair(d); render() }
                    .setNegativeButton("Cancel", null).show()
            }
        }
    }

    @Deprecated("plain Activity API")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != PICK || res != RESULT_OK || data == null) return
        val d = app.core.devices[id] ?: return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (uris.isNotEmpty()) {
            app.plugins.sendFiles(d, uris)
            toast("Sending ${uris.size} file${if (uris.size == 1) "" else "s"}")
        }
    }

    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()

    companion object {
        private const val PICK = 1
        fun intent(ctx: Context, id: String) =
            Intent(ctx, DeviceActivity::class.java).putExtra("device", id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
