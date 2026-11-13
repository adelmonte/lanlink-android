package dev.lanlink

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout

class MainActivity : Activity() {
    private val app get() = App.instance
    private lateinit var nameView: android.widget.TextView
    private lateinit var setup: LinearLayout
    private lateinit var list: LinearLayout
    private val listener: () -> Unit = { render() }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        LinkService.start(this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        scrollColumn {
            nameView = text("", 26f, bold = true)
            nameView.setOnClickListener { rename() }
            text("This phone · tap the name to change it", 13f, dim = true)
            space()
            setup = column(context)
            addView(setup)
            header("Devices")
            list = column(context)
            addView(list)
            space()
            row(context) {
                button("Refresh", 1f) { LinkService.refresh(this@MainActivity) }
                button("Add by IP", 1f) { addHost() }
                button("Settings", 1f) { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
            }.also { addView(it) }
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
        nameView.text = app.identity.name

        setup.removeAllViews()
        if (!Environment.isExternalStorageManager()) {
            setup.button("Allow file access (so desktops can browse this phone)") {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) {
            setup.button("Keep running in the background") {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
            }
        }
        if (setup.childCount > 0) setup.space()

        list.removeAllViews()
        val devs = app.core.devices.values.sortedWith(compareBy({ !it.paired }, { !it.online }, { it.name.lowercase() }))
        if (devs.isEmpty()) {
            list.text("Nothing yet. Make sure lanlink is running on your computer and both are on the same Wi-Fi.", 15f, dim = true)
        }
        for (d in devs) {
            list.entry(d.name, "${d.type} · ${stateText(d)}", highlight = d.paired && d.online) { startActivity(DeviceActivity.intent(this, d.id)) }
        }
    }

    private fun rename() {
        val e = EditText(this).apply { setText(app.identity.name); setSingleLine() }
        AlertDialog.Builder(this).setTitle("Device name").setView(e)
            .setPositiveButton("Save") { _, _ ->
                app.identity.rename(e.text.toString())
                render()
                // Reconnect so desktops pick up the new name.
                app.core.devices.values.forEach { it.link?.close() }
                LinkService.refresh(this)
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun addHost() {
        val e = EditText(this).apply {
            hint = "192.168.1.20"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
        }
        val current = app.manualHosts
        AlertDialog.Builder(this).setTitle("Add device by IP")
            .setMessage(if (current.isEmpty()) "Use this if the computer isn't found automatically." else "Saved: ${current.joinToString(", ")}")
            .setView(e)
            .setPositiveButton("Add") { _, _ ->
                val h = e.text.toString().trim()
                if (h.isNotEmpty()) {
                    app.manualHosts = (current + h).distinct()
                    LinkService.refresh(this)
                }
            }
            .setNeutralButton("Clear saved") { _, _ -> app.manualHosts = emptyList() }
            .setNegativeButton("Cancel", null).show()
    }
}
