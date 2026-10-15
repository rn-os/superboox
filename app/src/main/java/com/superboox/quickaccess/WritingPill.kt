package com.superboox.quickaccess

import android.content.Context
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * The Writing mode pill: a small outlined switch over BOOX Notes. Tap switches Writing mode;
 * hold opens its settings (where the pill can be turned off); drag moves it to either edge.
 * Drawn like the panel's own controls: solid black and white, 2dp outline, no gray fills, so
 * it reads on colour and black-and-white screens. Solid black while Writing mode is on.
 */
class WritingPillView(
    context: Context,
    private val onTap: () -> Unit,
    private val onHold: () -> Unit,
    private val onMoved: (rawX: Float, rawY: Float) -> Unit,
    private val onDropped: (rawX: Float, rawY: Float) -> Unit,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var held = false
    private val holdRunnable = Runnable { held = true; onHold() }

    init {
        contentDescription = "Writing mode"
        isClickable = true
    }

    fun render(on: Boolean) = with(context) {
        removeAllViews()
        val ink = if (on) Ink.PAPER else Ink.INK
        background = outline(2, 22, if (on) Ink.INK else Ink.PAPER)
        addView(hbox().apply {
            setPadding(dp(12), dp(8), dp(14), dp(8))
            addView(icon(getDrawable(R.drawable.ic_qc_writing), 20).apply { imageTintList = ColorStateList.valueOf(ink) })
            addView(text(if (on) "Writing mode on" else "Writing mode", 14f, bold = true, color = ink).apply { setPadding(dp(8), 0, 0, 0) })
        })
        contentDescription = if (on) "Writing mode is on. Tap to turn off, hold for settings." else "Writing mode. Tap to turn on, hold for settings."
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; held = false
                handler.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (held) return true
                if (!dragging && (abs(e.rawX - downX) > slop * 2 || abs(e.rawY - downY) > slop * 2)) {
                    dragging = true
                    handler.removeCallbacks(holdRunnable)
                }
                if (dragging) onMoved(e.rawX, e.rawY)
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(holdRunnable)
                if (dragging) onDropped(e.rawX, e.rawY) else if (!held) onTap()
            }
            MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(holdRunnable)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(holdRunnable)
        super.onDetachedFromWindow()
    }
}

/** The one-time offer, made the first time the user is in BOOX Notes after the feature arrives. */
object WritingPillPrompt {
    fun build(context: Context, prefs: Prefs, onYes: () -> Unit, onNo: () -> Unit): View = with(context) {
        val off = WritingMode.turnsOff(prefs)
        val what = (WritingMode.RADIOS + WritingMode.LIGHT).filter { it in off && (it != WritingMode.LIGHT || WritingMode.hasLight(prefs)) }
            .map(WritingMode::label)
        val list = when (what.size) {
            0 -> "your distractions"
            1 -> what[0]
            else -> what.dropLast(1).joinToString(", ") + " and " + what.last()
        }
        FrameLayout(this).apply {
            background = outline(3, 12)
            addView(vbox().apply {
                setPadding(dp(18), dp(16), dp(18), dp(16))
                addView(hbox().apply {
                    addView(icon(getDrawable(R.drawable.ic_qc_writing), 24))
                    addView(text("Writing mode", 18f, bold = true).apply { setPadding(dp(10), 0, 0, 0) })
                })
                addView(text("One tap turns off $list so nothing interrupts your writing, and puts it all back when you're done.", 15f).apply { setPadding(0, dp(10), 0, 0) })
                addView(text("Add a small Writing mode pill to BOOX Notes?", 15f, bold = true).apply { setPadding(0, dp(10), 0, 0) })
                addView(text("Hold the pill any time to change this or turn it off.", 13f, color = Ink.MUTED).apply { setPadding(0, dp(6), 0, 0) })
                addView(hbox().apply {
                    setPadding(0, dp(14), 0, 0)
                    addView(inkButton("No thanks", 15f) { onNo() }, lp(0, WRAP, 1f))
                    addView(inkButton("Add the pill", 15f) { onYes() }, lp(0, WRAP, 1f).apply { marginStart = dp(10) })
                })
            })
        }
    }
}
