package dev.lanlink

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.lanlink.core.Device

/**
 * Target for Android's share sheet, the text-selection menu and the clipboard
 * tile: pick a device and send.
 */
class ShareActivity : Activity() {
    private val app get() = App.instance
    private var waitingForClipboard = false
    /** Selected or clipboard text goes to the other clipboard as-is, never opened as a link. */
    private var plain = false

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        LinkService.start(this)
        when (intent.action) {
            // Android only lets the focused app read the clipboard, so wait for focus.
            CLIPBOARD -> { plain = true; waitingForClipboard = true }
            Intent.ACTION_PROCESS_TEXT -> {
                plain = true
                choose(emptyList(), intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString())
            }
            else -> choose(streams(), intent.getStringExtra(Intent.EXTRA_TEXT))
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || !waitingForClipboard) return
        waitingForClipboard = false
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        if (text.isNullOrEmpty()) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show()
            return finish()
        }
        choose(emptyList(), text)
    }

    private fun choose(uris: List<Uri>, text: String?) {
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
        if (uris.isNotEmpty()) app.plugins.sendFiles(d, uris)
        else if (text != null) { if (plain) app.plugins.sendClip(d, text) else app.plugins.sendText(d, text) }
        Toast.makeText(this, "Sending to ${d.name}", Toast.LENGTH_SHORT).show()
        finish()
    }

    companion object {
        const val CLIPBOARD = "dev.lanlink.SEND_CLIPBOARD"
    }
}
