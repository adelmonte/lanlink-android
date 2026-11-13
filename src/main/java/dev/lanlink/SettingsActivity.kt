package dev.lanlink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.Toast

class SettingsActivity : Activity() {
    private val songs get() = App.instance.songs
    private lateinit var body: LinearLayout

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        scrollColumn {
            text("Settings", 26f, bold = true)
            space()
            header("Audile")
            body = column(context)
            addView(body)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        body.removeAllViews()
        body.text("Like every song Audile recognizes in music-tui on your computer.", 15f, dim = true)
        val on = songs.enabled
        body.entry("Auto-like Audile songs", if (on) "On" else "Off", highlight = on) {
            songs.enabled = !on
            render()
        }
        if (on && !songs.hasAccess()) {
            body.text("lanlink needs notification access to see what Audile finds.", 15f)
            body.button("Allow notification access", primary = true) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }
        body.space()
        body.text("Songs Audile found before: in Audile, export the library as CSV, then pick that file here.", 15f, dim = true)
        body.button("Import Audile export") {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK)
        }
        val n = songs.waiting
        if (n > 0) body.text("$n waiting for your computer to connect.", 14f, dim = true)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data
        if (req != PICK || res != RESULT_OK || uri == null) return
        val list = try {
            contentResolver.openInputStream(uri)?.use { Songs.fromCsv(it.readBytes().toString(Charsets.UTF_8)) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val msg = if (list.isEmpty()) "No songs found. Export with the header row on."
        else "${songs.add(list)} new of ${list.size} songs"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        render()
    }

    private companion object {
        const val PICK = 1
    }
}
