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

    /**
     * Independent Front Light presets: turned on and the test passed, by a way
     * that's available now (SuperBoox itself, or Shizuku while it's running).
     */
    fun independentPresets(prefs: Prefs): Boolean = prefs.independentPresets && prefs.lightTestPassed &&
        when (lightMethod(prefs)) {
            METHOD_APP -> true
            METHOD_SHELL -> allowed()
            else -> false
        }

    /** How the light test passed: [METHOD_APP] (no Shizuku needed) or [METHOD_SHELL]. */
    fun lightMethod(prefs: Prefs): String? = prefs.sp.getString(Prefs.K_ADV_METHOD, null)

    fun setLightMethod(prefs: Prefs, method: String?) {
        prefs.sp.edit().apply { if (method == null) remove(Prefs.K_ADV_METHOD) else putString(Prefs.K_ADV_METHOD, method) }.apply()
    }

    const val METHOD_APP = "app"
    const val METHOD_SHELL = "shell"

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

    /** Runs a shell command as ADB would, on the calling (background) thread. */
    fun shellNow(command: String): Pair<Int, String> = runCatching {
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
        val text = (p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()).trim()
        p.waitFor() to text
    }.getOrElse { -1 to "${it.javaClass.simpleName}: ${(it.cause ?: it).message}" }

    /**
     * Read-only look at where BOOX's front light lives (for the debug report):
     * the backlight devices, their values and permissions, and related services.
     */
    fun lightProbe(): List<String> {
        if (!allowed()) return listOf("not available (Advanced access isn't connected)")
        val out = ArrayList<String>()
        fun run(label: String, cmd: String) {
            val (code, text) = shellNow(cmd)
            out += "$label (exit $code):"
            text.lines().take(60).forEach { out += "  $it" }
        }
        run("id", "id")
        run("backlight devices", "ls -l /sys/class/backlight/ 2>&1")
        run("backlight values", "for d in /sys/class/backlight/*; do echo \"== \$d\"; ls -lL \$d/brightness \$d/max_brightness 2>&1; echo \"brightness=\$(cat \$d/brightness 2>&1) max=\$(cat \$d/max_brightness 2>&1)\"; done")
        run("light services", "service list 2>&1 | grep -i -E 'light|onyx|bright|ctm|eink'")
        run("light properties", "getprop | grep -i -E 'light|bright|ctm|warm|cold' | head -40")
        return out
    }

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

    /**
     * BOOX's light hardware (build 45 report): brightness and colour temperature,
     * 0–32 each, writable by anyone as far as file permissions go. Android's
     * security rules may still stop an app; Shizuku runs as the shell, which can.
     */
    private const val BL_BRIGHTNESS = "/sys/class/backlight/onyx_bl_br/brightness"
    private const val BL_WARMTH = "/sys/class/backlight/onyx_bl_ct/brightness"

    /** The light as it is: from the hardware if readable, else BOOX's own record. */
    fun currentLight(context: Context): QuickControls.Levels? {
        runCatching {
            val b = java.io.File(BL_BRIGHTNESS).readText().trim().toInt()
            val w = java.io.File(BL_WARMTH).readText().trim().toInt()
            return QuickControls.Levels(b, w)
        }
        val cr = context.contentResolver
        val b = runCatching { Settings.System.getInt(cr, CTM_BRIGHTNESS) }.getOrNull() ?: return null
        val w = runCatching { Settings.System.getInt(cr, CTM_TEMPERATURE) }.getOrNull() ?: 0
        return QuickControls.Levels(b, w)
    }

    private fun writeAsApp(l: QuickControls.Levels): Boolean = runCatching {
        java.io.FileOutputStream(BL_BRIGHTNESS).use { it.write("${l.brightness}".toByteArray()) }
        java.io.FileOutputStream(BL_WARMTH).use { it.write("${l.warmth}".toByteArray()) }
        true
    }.getOrElse {
        DebugLog.i("light", "SuperBoox can't write the light itself: ${it.javaClass.simpleName} ${it.message}")
        false
    }

    private fun writeAsShell(l: QuickControls.Levels, done: (Boolean) -> Unit) {
        if (!allowed()) return done(false)
        shell("sh", "-c", "echo ${l.brightness} > $BL_BRIGHTNESS && echo ${l.warmth} > $BL_WARMTH") { code, _ -> done(code == 0) }
    }

    /**
     * Sets the light without going through BOOX's presets, by [method]
     * (null: SuperBoox itself first, then Shizuku). [done] gets the way that worked, or null.
     */
    fun setLight(levels: QuickControls.Levels, method: String? = null, done: (String?) -> Unit = {}) {
        val l = levels.clamp()
        if (method == METHOD_SHELL) return writeAsShell(l) { done(if (it) METHOD_SHELL else null) }
        io.execute {
            val ok = writeAsApp(l)
            main.post {
                when {
                    ok -> done(METHOD_APP)
                    method == METHOD_APP -> done(null)
                    else -> writeAsShell(l) { done(if (it) METHOD_SHELL else null) }
                }
            }
        }
    }
}
