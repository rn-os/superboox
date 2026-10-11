package com.superboox.quickaccess

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast

/**
 * Keeps an error on the main thread from stopping SuperBoox.
 *
 * Settings, the panel and the accessibility service share one process, so an error anywhere in
 * the interface used to stop the service too, and Android then keeps it stopped until it's switched
 * off and on (builds 63 and 65). Here the main thread's message loop runs inside a guard: an error
 * is logged, the windows it may have broken are rebuilt (Settings redrawn on the same page, the
 * panel and its overlays cleared and the edge strip set up again) and the loop carries on.
 *
 * Errors that keep coming straight back (the same broken state again and again) aren't hidden:
 * after a few in quick succession the process stops as before, and the report shows why.
 */
object MainGuard {
    private const val WINDOW_MS = 10_000L
    private const val MAX_IN_WINDOW = 6

    private val recent = ArrayDeque<Long>()

    fun install() {
        Handler(Looper.getMainLooper()).post {
            while (true) {
                try {
                    Looper.loop()
                    return@post // the loop only returns when the app is quitting
                } catch (t: Throwable) {
                    if (!recover(t)) throw t
                }
            }
        }
    }

    private fun recover(t: Throwable): Boolean {
        val now = SystemClock.uptimeMillis()
        recent.addLast(now)
        while (recent.isNotEmpty() && now - recent.first() > WINDOW_MS) recent.removeFirst()
        if (recent.size > MAX_IN_WINDOW) {
            DebugLog.e("crash", "errors keep repeating; stopping", t)
            return false
        }
        DebugLog.e("recovered", "error in the interface; SuperBoox carried on", t)
        runCatching { QuickAccessService.instance?.restart() }
        runCatching { SettingsActivity.recoverIfOpen() }
        QuickAccessService.instance?.let { s ->
            runCatching { Toast.makeText(s, "SuperBoox hit an error and recovered. A debug report shows what happened.", Toast.LENGTH_LONG).show() }
        }
        return true
    }
}
