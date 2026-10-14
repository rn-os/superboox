package com.superboox.quickaccess

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * Brings the edge strip back after the tablet restarts, without opening SuperBoox.
 *
 * Android should bind an enabled accessibility service by itself after a restart, but on BOOX it
 * doesn't: BOOX stops it right after it starts, and it stays listed as on with nothing running until
 * it's switched off and on again (Note Air 6C, Android 16). [Receiver] gets BOOT_COMPLETED and
 * schedules [CheckJob]s; if the service isn't running when one runs:
 *  - with WRITE_SECURE_SETTINGS (granted once through Advanced access, or `adb shell pm grant`;
 *    it survives restarts and updates, so Shizuku isn't needed afterwards) switches it off and on
 *    the way Android's own switch does;
 *  - otherwise posts a notification that opens SuperBoox, so the user notices before reaching for
 *    the strip.
 * Settings → "After a restart" turns this off ([Prefs.restoreAfterRestart]).
 */
object BootRestore {
    private const val CHANNEL = "after_restart"
    private const val NOTIFICATION_ID = 7
    private const val JOB_BASE = 7300
    private const val WAIT_MS = 8_000L
    private const val POLL_MS = 1_000L
    private const val RECHECK_MS = 6_000L

    private val main = Handler(Looper.getMainLooper())

    fun component(context: Context) = ComponentName(context, QuickAccessService::class.java)

    /** SuperBoox can switch its own service off and on (WRITE_SECURE_SETTINGS). */
    fun canSwitch(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun enabledList(context: Context): List<String> =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }

    private fun isOurs(context: Context, entry: String): Boolean {
        val cn = component(context)
        return entry.equals(cn.flattenToString(), true) || entry.equals(cn.flattenToShortString(), true)
    }

    fun isEnabled(context: Context): Boolean = enabledList(context).any { isOurs(context, it) }

    /**
     * Switches the service off and on by writing Android's setting directly, as its own switch does
     * (needs [canSwitch]). Android binds it again a moment later. [done] gets whether it was written.
     */
    fun switchOffAndOn(context: Context, done: (Boolean) -> Unit = {}) {
        val cr = context.contentResolver
        val others = enabledList(context).filterNot { isOurs(context, it) }
        val withUs = (others + component(context).flattenToString()).joinToString(":")
        runCatching {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
        }.onFailure {
            DebugLog.w("boot", "couldn't switch the service off: ${it.message}")
            return done(false)
        }
        main.postDelayed({
            val ok = runCatching {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, withUs)
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }.onFailure { DebugLog.w("boot", "couldn't switch the service back on: ${it.message}") }.isSuccess
            done(ok)
        }, 1_000)
    }

    /** Grants WRITE_SECURE_SETTINGS through Shizuku, once; Android keeps it across restarts and updates. */
    fun grantWithAdvancedAccess(context: Context, done: (Boolean) -> Unit) {
        if (!AdvancedAccess.allowed()) return done(false)
        AdvancedAccess.shell("pm", "grant", context.packageName, Manifest.permission.WRITE_SECURE_SETTINGS) { _, _ ->
            done(canSwitch(context))
        }
    }

    /** The command for doing the same from a computer. */
    fun adbCommand(context: Context) = "adb shell pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}"

    /** Checks after a restart: after BOOX's sweep, and once more in case it was late. */
    private val CHECKS = listOf(1 to 25_000L, 2 to 90_000L)

    /**
     * After a restart. The check can't run here: on the Note Air 6C (Android 16) Android binds the
     * service, then BOOX kills every app a second later (am_kill "eac_enable_status_changed") and
     * Android counts the service as crashed, so it stays off. That kill takes this process with it,
     * so the checks are jobs ([CheckJob]), which the system runs in a fresh process.
     */
    internal fun onBoot(context: Context) {
        val app = context.applicationContext
        DebugLog.init(app)
        val prefs = Prefs(app)
        val enabled = isEnabled(app)
        DebugLog.i("boot", "tablet restarted: service ${if (enabled) "on" else "off"} in Android's settings, " +
            "${if (QuickAccessService.instance != null) "running" else "not running yet"}, " +
            "restore ${if (prefs.restoreAfterRestart) "on" else "off"}, can switch it: ${canSwitch(app)}")
        if (!prefs.restoreAfterRestart || !enabled) return
        val js = app.getSystemService(JobScheduler::class.java) ?: return
        CHECKS.forEach { (id, delay) ->
            val job = JobInfo.Builder(JOB_BASE + id, ComponentName(app, CheckJob::class.java))
                .setMinimumLatency(delay)
                .setOverrideDeadline(delay + 30_000L)
                .build()
            runCatching { js.schedule(job) }.onFailure { DebugLog.w("boot", "couldn't schedule check $id: ${it.message}") }
        }
    }

    /** One check: waits briefly for the service, then brings it back or tells the user. */
    internal fun check(context: Context, finished: () -> Unit) {
        val app = context.applicationContext
        DebugLog.init(app)
        if (!Prefs(app).restoreAfterRestart || !isEnabled(app)) return finished()
        val start = System.currentTimeMillis()
        val wait = object : Runnable {
            override fun run() {
                val waited = System.currentTimeMillis() - start
                if (QuickAccessService.instance != null) {
                    DebugLog.i("boot", "check: service running")
                    return finished()
                }
                if (waited < WAIT_MS) {
                    main.postDelayed(this, POLL_MS)
                    return
                }
                if (!canSwitch(app)) {
                    DebugLog.w("boot", "check: service isn't running after the restart; notifying")
                    notifyOff(app)
                    return finished()
                }
                DebugLog.w("boot", "check: service isn't running after the restart; switching it off and on")
                switchOffAndOn(app) { ok ->
                    main.postDelayed({
                        if (QuickAccessService.instance != null) DebugLog.i("boot", "service back on after switching it off and on")
                        else {
                            DebugLog.w("boot", "service still not running after switching it (written: $ok); notifying")
                            notifyOff(app)
                        }
                        finished()
                    }, RECHECK_MS)
                }
            }
        }
        main.post(wait)
    }

    private fun notifyOff(context: Context) {
        if (!canNotify(context)) return DebugLog.w("boot", "notifications aren't allowed; nothing shown")
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "After a restart", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when the edge strip didn't come back after the tablet restarted"
            setSound(null, null)
            enableVibration(false)
        })
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_panel)
            .setContentTitle("SuperBoox's edge strip is off")
            .setContentText("It didn't start after the restart. Tap to turn it back on.")
            .setStyle(Notification.BigTextStyle().bigText("It didn't start after the restart. Tap to open SuperBoox, then Restart service."))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }.onFailure { DebugLog.w("boot", "couldn't notify: ${it.message}") }
    }

    /** The service is running again: the notification has done its job. */
    fun clearNotification(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) }
    }

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) onBoot(context)
        }
    }

    class CheckJob : JobService() {
        override fun onStartJob(params: JobParameters): Boolean {
            check(this) { jobFinished(params, false) }
            return true
        }

        override fun onStopJob(params: JobParameters): Boolean = false
    }
}
