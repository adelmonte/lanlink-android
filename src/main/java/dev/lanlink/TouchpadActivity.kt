package dev.lanlink

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import dev.lanlink.core.Packet
import dev.lanlink.core.Proto
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sign

class TouchpadActivity : Activity() {
    private lateinit var pad: TouchpadView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        val dev = App.instance.core.devices[intent.getStringExtra("device")] ?: return finish()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        title = dev.name
        pad = TouchpadView(this) { body -> dev.send(Packet(Proto.MOUSEPAD, body)) }
        val root = column(this) {
            fitsSystemWindows = true
            addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(row(context) {
                button("Left", 1f) { pad.send("singleclick") }
                button("Middle", 1f) { pad.send("middleclick") }
                button("Right", 1f) { pad.send("rightclick") }
                button("Keyboard", 1f) { pad.toggleKeyboard() }
            })
        }
        setContentView(root)
    }

    companion object {
        fun intent(ctx: Context, id: String) = Intent(ctx, TouchpadActivity::class.java).putExtra("device", id)
    }
}

/**
 * One finger moves, tap clicks, two-finger tap right-clicks, three-finger tap
 * middle-clicks, two fingers scroll, long-press then move drags. Also a text
 * target for the soft keyboard.
 */
class TouchpadView(ctx: Context, private val out: (JSONObject) -> Unit) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private var downAt = 0L
    private var maxPointers = 0
    private var moved = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var scrollAcc = 0f
    private var dragging = false
    private var keyboardShown = false
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15 * density
        color = ctx.attrColor(android.R.attr.textColorSecondary)
    }

    private val longPress = Runnable {
        if (moved < slop && maxPointers == 1) {
            dragging = true
            send("singlehold")
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun send(flag: String) = out(JSONObject().put(flag, true))

    override fun onDraw(c: Canvas) {
        c.drawText("Touchpad", width / 2f, height / 2f, hint)
        c.drawText("tap · two-finger tap = right click · long-press to drag", width / 2f, height / 2f + 24 * density, hint)
    }

    private fun centroid(e: MotionEvent, skip: Int = -1): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        var n = 0
        for (i in 0 until e.pointerCount) {
            if (i == skip) continue
            x += e.getX(i); y += e.getY(i); n++
        }
        return if (n == 0) Pair(lastX, lastY) else Pair(x / n, y / n)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = e.eventTime
                maxPointers = 1
                moved = 0f
                scrollAcc = 0f
                lastX = e.x
                lastY = e.y
                postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                centroid(e).let { lastX = it.first; lastY = it.second }
                removeCallbacks(longPress)
            }
            MotionEvent.ACTION_MOVE -> {
                val (x, y) = centroid(e)
                val dx = x - lastX
                val dy = y - lastY
                lastX = x
                lastY = y
                moved += abs(dx) + abs(dy)
                if (moved > slop && !dragging) removeCallbacks(longPress)
                if (moved <= slop && !dragging) return true
                when (e.pointerCount) {
                    1 -> {
                        // Phone px -> dp, then 1.5 desktop px per dp slow, up to 4.5 fast.
                        val speed = hypot(dx, dy) / density
                        val gain = (1.5f + 3f * minOf(speed / 12f, 1f)) / density
                        if (dx != 0f || dy != 0f) out(JSONObject().put("dx", (dx * gain).toDouble()).put("dy", (dy * gain).toDouble()))
                    }
                    2 -> {
                        scrollAcc += dy
                        val step = 14 * density
                        while (abs(scrollAcc) >= step) {
                            val s = sign(scrollAcc)
                            scrollAcc -= s * step
                            // Natural scrolling: content follows the finger.
                            out(JSONObject().put("scroll", true).put("dx", 0).put("dy", if (s > 0) 10 else -10))
                        }
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> centroid(e, e.actionIndex).let { lastX = it.first; lastY = it.second }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    send("singlerelease")
                } else if (e.eventTime - downAt < 250 && moved < slop * 2) {
                    when (maxPointers) {
                        1 -> send("singleclick")
                        2 -> send("rightclick")
                        3 -> send("middleclick")
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPress)
                if (dragging) { dragging = false; send("singlerelease") }
            }
        }
        return true
    }

    // ---- keyboard --------------------------------------------------------------

    fun toggleKeyboard() {
        val imm = context.getSystemService(InputMethodManager::class.java)
        requestFocus()
        if (keyboardShown) imm.hideSoftInputFromWindow(windowToken, 0) else imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        keyboardShown = !keyboardShown
    }

    private fun special(code: Int) = out(JSONObject().put("specialKey", code))
    private fun typed(s: String) { if (s.isNotEmpty()) out(JSONObject().put("key", s)) }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(info: EditorInfo): InputConnection {
        // "Visible password" stops most keyboards from composing/autocorrecting,
        // so every keystroke arrives immediately.
        info.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        info.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence, pos: Int): Boolean {
                val s = text.toString()
                if (s == "\n") special(12) else typed(s)
                return true
            }
            override fun setComposingText(text: CharSequence, pos: Int) = true
            override fun finishComposingText() = true
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before.coerceAtLeast(1)) { special(1) }
                return true
            }
            override fun sendKeyEvent(e: KeyEvent): Boolean {
                if (e.action == KeyEvent.ACTION_DOWN) handleKey(e)
                return true
            }
            override fun performEditorAction(action: Int): Boolean {
                special(12)
                return true
            }
        }
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val sk = when (e.keyCode) {
            KeyEvent.KEYCODE_DEL -> 1
            KeyEvent.KEYCODE_TAB -> 2
            KeyEvent.KEYCODE_DPAD_LEFT -> 4
            KeyEvent.KEYCODE_DPAD_UP -> 5
            KeyEvent.KEYCODE_DPAD_RIGHT -> 6
            KeyEvent.KEYCODE_DPAD_DOWN -> 7
            KeyEvent.KEYCODE_PAGE_UP -> 8
            KeyEvent.KEYCODE_PAGE_DOWN -> 9
            KeyEvent.KEYCODE_MOVE_HOME -> 10
            KeyEvent.KEYCODE_MOVE_END -> 11
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 12
            KeyEvent.KEYCODE_FORWARD_DEL -> 13
            KeyEvent.KEYCODE_ESCAPE -> 14
            in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> 21 + (e.keyCode - KeyEvent.KEYCODE_F1)
            else -> 0
        }
        val mods = JSONObject().put("ctrl", e.isCtrlPressed).put("alt", e.isAltPressed).put("super", e.isMetaPressed)
        if (sk != 0) {
            out(mods.put("specialKey", sk).put("shift", e.isShiftPressed))
            return true
        }
        val ch = e.getUnicodeChar(e.metaState and (KeyEvent.META_SHIFT_MASK or KeyEvent.META_CAPS_LOCK_ON))
        if (ch > 0) {
            out(mods.put("key", String(Character.toChars(ch))).put("shift", (e.isCtrlPressed || e.isAltPressed) && e.isShiftPressed))
            return true
        }
        return false
    }

    // Hardware keyboards (USB/Bluetooth) plugged into the phone.
    override fun onKeyDown(keyCode: Int, e: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK) super.onKeyDown(keyCode, e) else handleKey(e) || super.onKeyDown(keyCode, e)
}
