package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import android.widget.ImageView

/**
 * While a note opens, keeps the screen looking as it did when you tapped it:
 * a still of the current screen sits on top while SuperBoox goes through the
 * BOOX home screen and library underneath, and is lifted once the note is up.
 *
 * The still doesn't take touches, so the gestures that open the note go
 * through to BOOX. If a screenshot isn't possible (Android 10, or the
 * permission wasn't granted), notes open as before, without the cover.
 */
class OpeningCover(private val service: AccessibilityService, private val wm: WindowManager) {

    private val handler = Handler(Looper.getMainLooper())
    private var view: ImageView? = null
    private var token = 0

    /** Covers the screen as it looks now, then runs [then] (straight away if no cover is possible). */
    fun show(then: () -> Unit) {
        hide()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return then()
        val t = ++token
        var ran = false
        val go = Runnable {
            if (!ran && t == token) {
                ran = true
                then()
            }
        }
        // Let the panel's window leave the screen first, so it isn't in the still.
        handler.postDelayed({
            if (t != token) return@postDelayed
            handler.postDelayed(go, SHOT_TIMEOUT_MS)
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    service.mainExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                if (t == token && !ran) attach(Bitmap.wrapHardwareBuffer(buffer, result.colorSpace))
                            } catch (e: Exception) {
                                DebugLog.w("cover", "couldn't show the still: ${e.javaClass.simpleName} ${e.message}")
                            } finally {
                                buffer.close()
                            }
                            go.run()
                        }

                        override fun onFailure(errorCode: Int) {
                            DebugLog.w("cover", "screenshot not available (error $errorCode); opening without cover")
                            go.run()
                        }
                    },
                )
            } catch (e: Exception) {
                DebugLog.w("cover", "screenshot failed: ${e.javaClass.simpleName} ${e.message}")
                go.run()
            }
        }, SETTLE_MS)
    }

    /** Lifts the cover (and drops a cover still being prepared), after [delayMs]. */
    fun hide(delayMs: Long = 0) {
        if (delayMs > 0) {
            val t = token
            handler.postDelayed({ if (t == token) hide() }, delayMs)
            return
        }
        token++
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private fun attach(hw: Bitmap?) {
        // A software copy draws in any window, hardware-accelerated or not.
        val still = hw?.copy(Bitmap.Config.ARGB_8888, false) ?: return
        val (w, h) = wm.currentWindowMetrics.bounds.let { it.width() to it.height() }
        if ((still.width > still.height) != (w > h)) {
            DebugLog.w("cover", "still is ${still.width}x${still.height} but the screen is ${w}x$h; skipping cover")
            return
        }
        val v = ImageView(service).apply {
            setImageBitmap(still)
            scaleType = ImageView.ScaleType.FIT_XY
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        ).apply {
            windowAnimations = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            setFitInsetsTypes(0)
        }
        wm.addView(v, params)
        view = v
        DebugLog.i("cover", "screen held while the note opens")
        // Never leave the screen frozen, whatever happens underneath.
        val t = token
        handler.postDelayed({
            if (t == token && view != null) {
                DebugLog.w("cover", "lifted after ${MAX_COVER_MS / 1000}s")
                hide()
            }
        }, MAX_COVER_MS)
    }

    private companion object {
        const val SETTLE_MS = 150L
        const val SHOT_TIMEOUT_MS = 1_000L
        const val MAX_COVER_MS = 12_000L
    }
}
