package com.superboox.quickaccess

import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Raw e-ink rendering is restricted to the visible Home writing area.
 *
 * Two ways to show a finished stroke, chosen in Home → More → Pen refresh so they can be compared
 * on the tablet:
 *  - batched (default): after a short pen pause, raw rendering is switched off for one Android
 *    redraw and back on;
 *  - [fastRefresh] (test, after OpenInkBridge's BOOX adapter): raw rendering stays on, the SDK's own
 *    pen-up refresh is off, and each committed stroke is drawn with a fast DU e-ink update.
 */
internal class HomeHardwareInk(
    private val view: View,
    private val color: Int,
    private val width: Float,
    private val fastRefresh: Boolean = false,
    private val onStroke: (FloatArray) -> Unit,
) {
    private var helper: TouchHelper? = null
    private var unavailable = false
    private var finishedPoints: FloatArray? = null
    private val repaint = Runnable { repaintCommittedInk() }
    var drawing = false
        private set

    private val callback = object : RawInputCallback() {
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint?) {
            view.post {
                finishedPoints = null
                drawing = helper != null && !shortcut && point != null
                if (drawing) view.removeCallbacks(repaint)
            }
        }

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint?) {
            view.post {
                val active = drawing && !shortcut && helper != null && view.hasWindowFocus()
                drawing = false
                if (active) {
                    finishedPoints?.let(onStroke)
                    refresh()
                }
                finishedPoints = null
            }
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint?) = Unit
        override fun onRawDrawingTouchPointListReceived(points: TouchPointList?) {
            val rendered = points?.renderPoints.orEmpty()
            val coordinates = FloatArray(rendered.size * 2)
            rendered.forEachIndexed { index, point ->
                coordinates[index * 2] = point.x
                coordinates[index * 2 + 1] = point.y
            }
            view.post { if (drawing) finishedPoints = coordinates }
        }
        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint?) = Unit
        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint?) = Unit
        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint?) = Unit
        override fun onRawErasingTouchPointListReceived(points: TouchPointList?) = Unit
    }

    fun start() {
        if (unavailable || helper != null || !Build.MANUFACTURER.equals("ONYX", true) ||
            !view.isAttachedToWindow || !view.hasWindowFocus()
        ) return
        val bounds = Rect()
        // The SDK maps coordinates through the host View. Screen coordinates would add its offset twice.
        if (!view.getLocalVisibleRect(bounds) || bounds.isEmpty) return
        try {
            // The SDK's own reflection bootstrap fails on Android 16. Limit access to ONYX APIs.
            HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx/")
            val mapped = EpdController.mapToRawTouchPoint(view, RectF(bounds))
            check(mapped != null && mapped.width() > 0f && mapped.height() > 0f) {
                "BOOX could not map the pen region"
            }
            helper = TouchHelper.create(view, TouchHelper.FEATURE_SF_TOUCH_RENDER, callback)
            val pen = checkNotNull(helper)
            pen.enableFingerTouch(false)
            pen.setStrokeColor(color).setStrokeWidth(width)
                .setLimitRect(bounds, emptyList()).openRawDrawing()
            pen.setRawDrawingEnabled(true).setRawDrawingRenderEnabled(true)
            check(pen.isRawDrawingCreated && pen.isRawDrawingRenderEnabled) { "Raw drawing unavailable" }
            // Fast refresh: SuperBoox refreshes after each stroke itself, so BOOX's pen-up refresh would be a second one.
            if (fastRefresh) runCatching { pen.setPenUpRefreshEnabled(false) }
                .onFailure { DebugLog.w("home pen", "pen-up refresh not switched off: ${it.message}") }
            DebugLog.i("home pen", "BOOX raw drawing active (${if (fastRefresh) "fast refresh" else "batched"}): $bounds mapped to $mapped")
        } catch (error: Exception) {
            fail(error)
        } catch (error: LinkageError) {
            fail(error)
        }
    }

    private fun fail(error: Throwable) {
        unavailable = true
        stop()
        DebugLog.w("home pen", "Using Android ink: ${error.javaClass.simpleName}: ${error.message}")
    }

    fun refresh() {
        if (helper == null) {
            view.invalidate()
            return
        }
        view.removeCallbacks(repaint)
        view.postDelayed(repaint, if (fastRefresh) FAST_COALESCE_MS else PEN_IDLE_MS)
    }

    /** Fast refresh: draw the committed ink and push it with a DU update, raw rendering left on. */
    private fun fastRepaint(pen: TouchHelper) {
        try {
            view.invalidate()
            view.postOnAnimation {
                if (helper === pen && !drawing) {
                    runCatching { EpdController.invalidate(view, UpdateMode.DU) }
                        .onFailure { DebugLog.w("home pen", "DU update failed: ${it.message}") }
                }
            }
        } catch (error: Exception) {
            fail(error)
            view.invalidate()
        }
    }

    private fun repaintCommittedInk() {
        if (drawing || !view.hasWindowFocus()) return
        val pen = helper
        if (pen == null) {
            view.invalidate()
            return
        }
        if (fastRefresh) return fastRepaint(pen)
        try {
            // Unfreeze display updates for the committed stroke while keeping the raw input reader active.
            pen.setRawDrawingRenderEnabled(false)
            view.invalidate()
            view.postOnAnimation {
                view.postOnAnimation {
                    if (helper === pen && view.hasWindowFocus()) {
                        runCatching { pen.setRawDrawingRenderEnabled(true) }.onFailure(::fail)
                    }
                }
            }
        } catch (error: Exception) {
            fail(error)
            view.invalidate()
        }
    }

    fun stop() {
        view.removeCallbacks(repaint)
        drawing = false
        val pen = helper ?: return
        helper = null
        runCatching { pen.closeRawDrawing() }
            .onFailure { DebugLog.w("home pen", "Could not close raw drawing: ${it.message}") }
    }

    private companion object {
        const val PEN_IDLE_MS = 220L
        /** Fast refresh: strokes lifted within this time share one update. */
        const val FAST_COALESCE_MS = 40L
    }
}
