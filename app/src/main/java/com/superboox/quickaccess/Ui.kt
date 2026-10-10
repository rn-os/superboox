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
 * Tiny view-building helpers. The UI is built in code: the panel lives in an
 * overlay window owned by a service, and plain black-on-white views with no
 * animation are what render best on e-ink.
 *
 * E-ink rules followed here: pure black and white, no light grays (they turn
 * into mush at 16 gray levels), heavy weights, thick rules, outlined buttons,
 * no shadows or ripples.
 */
object Ink {
    const val INK = Color.BLACK
    const val PAPER = Color.WHITE
    /** Secondary text. Kept dark: lighter grays wash out on e-ink. */
    const val MUTED = 0xFF333333.toInt()
    const val PRESSED = 0xFFBBBBBB.toInt()
    const val DISABLED = 0xFF888888.toInt()

    val MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).roundToInt()
fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density).roundToInt()
fun View.dp(v: Int): Int = context.dp(v)

fun Context.text(
    value: CharSequence,
    sizeSp: Float = 16f,
    bold: Boolean = false,
    color: Int = Ink.INK,
    medium: Boolean = false,
): TextView =
    TextView(this).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        typeface = when {
            bold -> Typeface.DEFAULT_BOLD
            medium -> Ink.MEDIUM
            else -> Typeface.DEFAULT
        }
    }

/** Bold black section label with a heavy rule underneath. */
fun Context.sectionTitle(value: String, scale: Float = 1f): View = vbox().apply {
    setPadding(dp(16 * scale), dp(18 * scale), dp(16 * scale), dp(6 * scale))
    addView(text(value.uppercase(), 14f * scale, bold = true).apply { letterSpacing = 0.06f })
    addView(View(context).apply { setBackgroundColor(Ink.INK) }, lp(MATCH, dp(2)).apply { topMargin = dp(4 * scale) })
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

/** Round outlined button (Front Light − / +): black ring, gray while pressed. */
fun Context.ovalButton(strokeDp: Int = 2): StateListDrawable {
    fun oval(fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(strokeDp), Ink.INK)
    }
    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), oval(Ink.PRESSED))
        addState(intArrayOf(), oval(Ink.PAPER))
    }
}

/** Same, for outlined shapes: keeps the black border while pressed. */
private fun Context.outlinedStates(radiusDp: Int): StateListDrawable = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), outline(2, radiusDp, Ink.PRESSED))
    addState(intArrayOf(-android.R.attr.state_enabled), outline(2, radiusDp, Ink.PAPER).apply { setStroke(dp(2), Ink.DISABLED) })
    addState(intArrayOf(), outline(2, radiusDp, Ink.PAPER))
}

/** Outlined black-on-white text button (replaces the gray Material button). */
fun Context.inkButton(label: String, sizeSp: Float = 15f, onClick: () -> Unit): TextView =
    text(label, sizeSp, bold = true).apply {
        gravity = Gravity.CENTER
        minHeight = dp(44)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = outlinedStates(6)
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

/** Square outlined icon button, e.g. the ↑ ↓ 🗑 controls in edit mode. */
fun Context.inkIconButton(iconRes: Int, label: String, sizeDp: Int = 44, enabled: Boolean = true, onClick: () -> Unit): View =
    android.widget.FrameLayout(this).apply {
        contentDescription = label
        background = outlinedStates(6)
        isEnabled = enabled
        isClickable = true
        isFocusable = true
        val pad = sizeDp / 5
        setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
        addView(icon(getDrawable(iconRes), sizeDp - 2 * pad).apply {
            imageTintList = ColorStateList.valueOf(if (enabled) Ink.INK else Ink.DISABLED)
        })
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)).apply { marginStart = dp(6) }
        setOnClickListener { if (enabled) onClick() }
    }

fun lp(w: Int, h: Int, weight: Float = 0f): LinearLayout.LayoutParams = LinearLayout.LayoutParams(w, h, weight)

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
