package com.superboox.quickaccess

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Tiny view-building helpers. The UI is built in code: it lives in an overlay
 * window owned by a service, and plain black-on-white views with no
 * animation are what render best on e-ink.
 */
object Ink {
    const val INK = Color.BLACK
    const val PAPER = Color.WHITE
    const val MUTED = 0xFF555555.toInt()
    const val PRESSED = 0xFFCCCCCC.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun View.dp(v: Int): Int = context.dp(v)

fun Context.text(value: CharSequence, sizeSp: Float = 16f, bold: Boolean = false, color: Int = Ink.INK): TextView =
    TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

fun Context.sectionTitle(value: String): TextView =
    text(value.uppercase(), 13f, bold = true, color = Ink.MUTED).apply {
        letterSpacing = 0.08f
        setPadding(dp(16), dp(16), dp(16), dp(6))
    }

fun Context.vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

fun Context.divider(thicknessDp: Int = 1): View = View(this).apply {
    setBackgroundColor(Ink.INK)
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(thicknessDp))
}

fun Context.icon(drawable: Drawable?, sizeDp: Int = 24, tint: Boolean = true): ImageView = ImageView(this).apply {
    setImageDrawable(drawable)
    if (tint) imageTintList = ColorStateList.valueOf(Ink.INK)
    scaleType = ImageView.ScaleType.FIT_CENTER
    layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
}

fun Context.outline(strokeDp: Int = 2, radiusDp: Int = 8, fill: Int = Ink.PAPER): GradientDrawable =
    GradientDrawable().apply {
        setColor(fill)
        setStroke(dp(strokeDp), Ink.INK)
        cornerRadius = dp(radiusDp).toFloat()
    }

/** Instant gray while pressed; no ripple (ripples smear on e-ink). */
fun View.pressable(normal: Drawable = ColorDrawable(Color.TRANSPARENT)) {
    background = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(Ink.PRESSED))
        addState(intArrayOf(), normal)
    }
    isClickable = true
    isFocusable = true
}

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
