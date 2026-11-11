package dev.lanlink

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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
    val a = obtainStyledAttributes(intArrayOf(attr))
    try { return a.getColor(0, 0) } finally { a.recycle() }
}

private fun Int.withAlpha(a: Int) = (this and 0x00FFFFFF) or (a shl 24)

/** Rounded, tappable surface: a faint tint of the text colour, or a solid fill. */
private fun Context.surface(radius: Int, fill: Int? = null): RippleDrawable {
    val fg = attrColor(android.R.attr.textColorPrimary)
    val shape = GradientDrawable().apply {
        cornerRadius = dp(radius).toFloat()
        setColor(fill ?: fg.withAlpha(0x1C))
    }
    return RippleDrawable(ColorStateList.valueOf(fg.withAlpha(0x33)), shape, shape)
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
        setTextColor(context.attrColor(if (dim) android.R.attr.textColorSecondary else android.R.attr.textColorPrimary))
        setLineSpacing(0f, 1.15f)
        setPadding(0, context.dp(4), 0, context.dp(4))
    }
    addView(t)
    return t
}

/** Small accent-coloured section title. */
fun LinearLayout.header(s: String): TextView = text(s.uppercase(), 12f, bold = true).apply {
    setTextColor(context.attrColor(android.R.attr.colorAccent))
    letterSpacing = 0.08f
    setPadding(context.dp(4), context.dp(8), 0, context.dp(8))
}

fun LinearLayout.button(label: String, weight: Float = 0f, primary: Boolean = false, onClick: () -> Unit): Button {
    val b = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 16f
        stateListAnimator = null
        minHeight = context.dp(52)
        if (primary) {
            background = context.surface(26, context.attrColor(android.R.attr.colorAccent))
            setTextColor(0xFF1A0E00.toInt())
            typeface = Typeface.DEFAULT_BOLD
        } else {
            background = context.surface(26)
            setTextColor(context.attrColor(android.R.attr.textColorPrimary))
        }
        setOnClickListener { onClick() }
    }
    val m = context.dp(4)
    val lp = if (weight > 0f) LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight)
    else LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.setMargins(m, m, m, m)
    addView(b, lp)
    return b
}

fun LinearLayout.space(h: Int = 12) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(h)))

/** A tappable two-line card, like a settings entry. */
fun LinearLayout.entry(title: String, subtitle: String, highlight: Boolean = false, onClick: () -> Unit) {
    val c = column(context) {
        text(title, 18f, bold = true)
        text(subtitle, 14f, dim = true).apply {
            if (highlight) setTextColor(context.attrColor(android.R.attr.colorAccent))
        }
        setPadding(context.dp(18), context.dp(12), context.dp(18), context.dp(12))
        isClickable = true
        isFocusable = true
        background = context.surface(18)
        setOnClickListener { onClick() }
    }
    val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    lp.setMargins(0, context.dp(4), 0, context.dp(4))
    addView(c, lp)
}

fun stateText(d: dev.lanlink.core.Device): String = when {
    d.pair is dev.lanlink.core.PairState.Incoming -> "Wants to pair"
    d.pair is dev.lanlink.core.PairState.Requested -> "Pairing…"
    d.paired && d.online -> "Connected"
    d.paired -> "Offline"
    else -> "Not paired · tap to pair"
}
