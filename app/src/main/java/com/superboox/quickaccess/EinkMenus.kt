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
 *
 * Optimize › Others › "Stay active in the background" is BOOX's own keep-alive switch for an app
 * (its "full PM access"): without it BOOX may stop SuperBoox in the background even when Android's
 * battery optimisation is off. [stayActive] reads it the way SystemUI does, through BOOX's
 * EACAppThemeManager in the framework.
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

    /**
     * Whether Optimize › Others › "Stay active in the background" is on for [pkg], or null when it
     * can't be read (not a BOOX, or a firmware without it). SystemUI reads the same value:
     * EACAppThemeManager.getTheme(pkg, activeType).appConfig.extraConfig.isFullPMAccess.
     */
    fun stayActive(pkg: String): Boolean? {
        if (!available) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("Landroid/onyx/")
            val manager = Class.forName("android.onyx.optimization.EACAppThemeManager")
            val type = manager.getMethod("getActiveThemeType", String::class.java).invoke(null, pkg) as Int
            val theme = manager.getMethod("getTheme", String::class.java, Int::class.javaPrimitiveType).invoke(null, pkg, type) ?: return null
            val config = theme.javaClass.getMethod("getAppConfig").invoke(theme) ?: return null
            val extra = config.javaClass.getMethod("getExtraConfig").invoke(config) ?: return null
            extra.javaClass.getMethod("isFullPMAccess").invoke(extra) as Boolean
        }.onFailure { if (!stayActiveFailed) DebugLog.w("eink", "couldn't read stay active for $pkg", it); stayActiveFailed = true }.getOrNull()
    }

    /** Logged once: the tour reads it every second or so. */
    private var stayActiveFailed = false

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
