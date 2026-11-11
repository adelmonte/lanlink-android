package dev.lanlink

import android.app.Activity
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Tiny helpers for building plain-View layouts in code. */

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

fun Context.attrColor(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.data
}

fun Activity.scrollColumn(build: LinearLayout.() -> Unit): LinearLayout {
    val col = column(this, build)
    val pad = dp(16)
    col.setPadding(pad, pad, pad, pad)
    setContentView(ScrollView(this).apply {
        isFillViewport = true
        fitsSystemWindows = true
        addView(col)
    })
    return col
}

fun column(ctx: Context, build: LinearLayout.() -> Unit = {}) =
    LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; build() }

fun row(ctx: Context, build: LinearLayout.() -> Unit = {}) =
    LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; build() }

fun LinearLayout.text(s: CharSequence, size: Float = 16f, bold: Boolean = false, dim: Boolean = false): TextView {
    val t = TextView(context).apply {
        text = s
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        if (dim) alpha = 0.65f
        setPadding(0, context.dp(4), 0, context.dp(4))
    }
    addView(t)
    return t
}

fun LinearLayout.button(label: String, weight: Float = 0f, onClick: () -> Unit): Button {
    val b = Button(context).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }
    if (weight > 0f) addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight))
    else addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    return b
}

fun LinearLayout.space(h: Int = 12) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(h)))

/** A tappable two-line row, like a settings entry. */
fun LinearLayout.entry(title: String, subtitle: String, onClick: () -> Unit) {
    val c = column(context) {
        text(title, 18f)
        text(subtitle, 14f, dim = true)
        val pad = context.dp(12)
        setPadding(pad, pad, pad, pad)
        isClickable = true
        isFocusable = true
        foreground = context.getDrawable(context.attrColorRes(android.R.attr.selectableItemBackground))
        setOnClickListener { onClick() }
    }
    addView(c)
}

private fun Context.attrColorRes(attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return tv.resourceId
}

fun stateText(d: dev.lanlink.core.Device): String = when {
    d.pair is dev.lanlink.core.PairState.Incoming -> "Wants to pair"
    d.pair is dev.lanlink.core.PairState.Requested -> "Pairing…"
    d.paired && d.online -> "Connected"
    d.paired -> "Offline"
    else -> "Not paired · tap to pair"
}
