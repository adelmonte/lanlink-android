package dev.lanlink

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.lanlink.core.Device

/** Target for Android's share sheet: pick a device and send. */
class ShareActivity : Activity() {
    private val app get() = App.instance

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        LinkService.start(this)
        val uris = streams()
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        if (uris.isEmpty() && text.isNullOrEmpty()) return finish()

        val targets = app.core.devices.values.filter { it.paired && it.online }.sortedBy { it.name.lowercase() }
        if (targets.size == 1) return send(targets[0], uris, text)

        scrollColumn {
            text("Send to…", 22f, bold = true)
            space()
            if (targets.isEmpty()) {
                text("No paired device is online right now.", dim = true)
                button("Close") { finish() }
            }
            for (d in targets) entry(d.name, d.type) { send(d, uris, text) }
        }
    }

    @Suppress("DEPRECATION")
    private fun streams(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: emptyList()
        else -> emptyList()
    }

    private fun send(d: Device, uris: List<Uri>, text: String?) {
        if (uris.isNotEmpty()) app.plugins.sendFiles(d, uris) else if (text != null) app.plugins.sendText(d, text)
        Toast.makeText(this, "Sending to ${d.name}", Toast.LENGTH_SHORT).show()
        finish()
    }
}
