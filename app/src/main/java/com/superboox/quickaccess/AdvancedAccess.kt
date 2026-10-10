package com.superboox.quickaccess

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Advanced access: ADB-level commands run on the tablet itself through
 * Shizuku (started with Wireless debugging, no computer). Optional; it unlocks
 * features Android keeps from ordinary apps, such as setting BOOX's front light
 * directly so SuperBoox presets leave BOOX's Custom alone.
 *
 * Shizuku has to be started again after every restart; [restartedSinceUse]
 * tells the panel when that is why access is missing.
 */
object AdvancedAccess {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    const val SHIZUKU_RELEASES = "https://github.com/RikkaApps/Shizuku/releases"
    const val REQUEST_CODE = 41

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun installed(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0) }.isSuccess

    /** Shizuku is running (it has been started since the last restart). */
    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /** Running, and SuperBoox is allowed to use it. */
    fun allowed(): Boolean = running() &&
        runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    fun requestPermission() {
        runCatching { Shizuku.requestPermission(REQUEST_CODE) }.onFailure { DebugLog.w("adb", "permission request failed: ${it.message}") }
    }

    /** Independent Front Light presets: turned on, test passed, and access available now. */
    fun independentPresets(prefs: Prefs): Boolean = prefs.independentPresets && prefs.lightTestPassed && allowed()

    // ---- Restart detection ------------------------------------------------------------------

    private fun bootTime(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    /** Records that access worked during this boot. */
    fun markUsed(prefs: Prefs) {
        prefs.sp.edit().putLong(Prefs.K_ADV_BOOT, bootTime()).apply()
    }

    /** Access worked before, but the tablet has restarted since (so Shizuku needs starting again). */
    fun restartedSinceUse(prefs: Prefs): Boolean {
        val last = prefs.sp.getLong(Prefs.K_ADV_BOOT, 0L)
        return last != 0L && abs(bootTime() - last) > 60_000L
    }

    // ---- Commands ---------------------------------------------------------------------------

    /** Runs a shell command as ADB would (off the main thread); [done] gets the exit code and output. */
    fun shell(vararg command: String, done: (Int, String) -> Unit = { _, _ -> }) {
        io.execute {
            val (code, out) = runCatching {
                // Shizuku keeps newProcess private in its API; it's the documented way to run a command.
                val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                m.isAccessible = true
                val p = m.invoke(null, arrayOf(*command), null, null) as Process
                val text = (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trim()
                p.waitFor() to text
            }.getOrElse { -1 to "${it.javaClass.simpleName}: ${(it.cause ?: it).message}" }
            DebugLog.i("adb", "${command.joinToString(" ")} -> $code${if (out.isNotEmpty()) " ($out)" else ""}")
            main.post { done(code, out) }
        }
    }

    // ---- Front light, directly --------------------------------------------------------------

    private const val CTM_BRIGHTNESS = "screen_ctm_brightness"
    private const val CTM_TEMPERATURE = "screen_ctm_temperature"

    /** The light as BOOX records it (apps may read it; only ADB may write it). */
    fun currentLight(context: Context): QuickControls.Levels? {
        val cr = context.contentResolver
        val b = runCatching { Settings.System.getInt(cr, CTM_BRIGHTNESS) }.getOrNull() ?: return null
        val w = runCatching { Settings.System.getInt(cr, CTM_TEMPERATURE) }.getOrNull() ?: 0
        return QuickControls.Levels(b, w)
    }

    /** Sets the light without going through BOOX's presets. */
    fun setLight(levels: QuickControls.Levels, done: (Boolean) -> Unit = {}) {
        val l = levels.clamp()
        shell("settings", "put", "system", CTM_BRIGHTNESS, "${l.brightness}") { c1, _ ->
            shell("settings", "put", "system", CTM_TEMPERATURE, "${l.warmth}") { c2, _ -> done(c1 == 0 && c2 == 0) }
        }
    }
}
