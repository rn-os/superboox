package com.superboox.quickaccess

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * The thin touch strip along the screen edge. Plays the role of the
 * Supernote's capacitive side bar: a swipe (or double-tap) here opens the panel.
 *
 * Once a touch starts inside this window, Android keeps delivering the rest of
 * the gesture here even after the finger leaves the strip, so a swipe inward
 * is fully visible to us.
 */
@SuppressLint("ViewConstructor")
class EdgeHandleView(
    context: Context,
    private val edge: Edge,
    private val trigger: Trigger,
    private val visible: Boolean,
    /** Settings › Gesture is open: draw the whole strip in black so its size and place can be seen. */
    private val solid: Boolean = false,
    private val onTrigger: () -> Unit,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ink.INK }
    private val swipeThreshold = context.dp(28).toFloat()
    private val tapSlop = context.dp(12).toFloat()

    private var downX = 0f
    private var downY = 0f
    private var fired = false
    private var lastTapUp = 0L

    override fun onDraw(canvas: Canvas) {
        if (solid) {
            val r = dp(4).toFloat()
            // Rounded on the inner side only: hugs the screen edge.
            if (edge == Edge.RIGHT) canvas.drawRoundRect(0f, 0f, width + r, height.toFloat(), r, r, paint)
            else canvas.drawRoundRect(-r, 0f, width.toFloat(), height.toFloat(), r, r, paint)
            return
        }
        if (!visible) return
        // A short dark pill hugging the screen edge.
        val w = dp(4).toFloat()
        val inset = dp(2).toFloat()
        val left = if (edge == Edge.RIGHT) width - w - inset else inset
        val r = w / 2
        canvas.drawRoundRect(left, height * 0.2f, left + w, height * 0.8f, r, r, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.rawX
                downY = event.rawY
                fired = false
            }

            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = event.rawX - downX
                val dy = event.rawY - downY
                val inward = if (edge == Edge.RIGHT) -dx else dx
                val hit = when (trigger) {
                    Trigger.SWIPE_IN -> inward > swipeThreshold && inward > abs(dy)
                    Trigger.SWIPE_ALONG -> abs(dy) > swipeThreshold * 1.5f && abs(dy) > 2 * abs(dx)
                    Trigger.DOUBLE_TAP -> false
                }
                if (hit) fire()
            }

            MotionEvent.ACTION_UP -> if (trigger == Trigger.DOUBLE_TAP && !fired) {
                val moved = abs(event.rawX - downX) > tapSlop || abs(event.rawY - downY) > tapSlop
                val now = SystemClock.uptimeMillis()
                if (!moved && now - lastTapUp < DOUBLE_TAP_MS) {
                    lastTapUp = 0
                    fire()
                } else if (!moved) {
                    lastTapUp = now
                }
            }
        }
        return true
    }

    private fun fire() {
        fired = true
        onTrigger()
    }

    private companion object {
        const val DOUBLE_TAP_MS = 400L
    }
}
