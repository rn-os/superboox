package com.superboox.quickaccess

import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * BOOX's own e-ink menus, opened over the app that is in front ("This app" in the panel).
 *
 * Neither is an activity: BOOX's SystemUI draws them as an overlay when it receives
 * a broadcast (found from a Note Air 6C capture and the launcher/SystemUI code):
 *  - Optimize: the per-app "Customize" panel (Refresh · Display · Color · Others).
 *    The BOOX launcher sends [ACTION_OPTIMIZE] with [EXTRA_COMPONENT] = "package/activity"
 *    when an app is held → Optimize.
 *  - EinkWise: BOOX's E-Ink Center ([ACTION_EINK_CENTER]), device-wide, no extras.
 * SystemUI's receiver is exported with no permission or sender check, so any app may send them.
 * Changes there apply live to the running app (SystemUI never stops it).
 */
object EinkMenus {
    const val ACTION_OPTIMIZE = "com.onyx.SHOW_EAC_SETTINGS_VIEW_REQUEST"
    const val EXTRA_COMPONENT = "args_component"
    const val ACTION_EINK_CENTER = "action.open.eink.center.request"

    /** Only BOOX (ONYX) devices have these menus. */
    val available: Boolean
        get() = Build.MANUFACTURER.equals("ONYX", ignoreCase = true) || Build.BRAND.equals("ONYX", ignoreCase = true)

    /**
     * The app "This app" is about, or null when it shouldn't show: nothing known, SuperBoox
     * itself, the system UI, or a home screen (BOOX's launcher or the default one).
     */
    fun target(context: Context, pkg: String?): String? {
        if (!available || pkg.isNullOrEmpty()) return null
        if (pkg == context.packageName || pkg == BooxNotes.BOOX_HOME_PACKAGE || pkg == "com.android.systemui") return null
        if (pkg == homePackage(context)) return null
        return pkg.takeIf { component(context, it) != null }
    }

    /** The app's launcher activity as "package/activity", which BOOX's Optimize keys its settings by. */
    fun component(context: Context, pkg: String): String? =
        runCatching { context.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToString() }.getOrNull()

    fun openOptimize(context: Context, pkg: String): Boolean {
        val component = component(context, pkg) ?: return false.also { DebugLog.w("eink", "optimize: no launcher activity for $pkg") }
        return send(context, Intent(ACTION_OPTIMIZE).putExtra(EXTRA_COMPONENT, component), "optimize $component")
    }

    fun openEinkWise(context: Context): Boolean = send(context, Intent(ACTION_EINK_CENTER), "einkwise")

    private fun send(context: Context, intent: Intent, what: String): Boolean = runCatching {
        context.sendBroadcast(intent)
        DebugLog.i("eink", "sent $what")
        true
    }.getOrElse {
        DebugLog.w("eink", "couldn't send $what", it)
        false
    }

    private fun homePackage(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
    }.getOrNull()
}
