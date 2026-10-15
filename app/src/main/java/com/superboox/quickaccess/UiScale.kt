package com.superboox.quickaccess

import android.content.Context
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How big the Settings screens are drawn on this tablet.
 *
 * BOOX sets each screen's density to its real pixels per inch, so a dp is the same size on a
 * 6" Palma and a 13.3" Tab X, and Settings looked phone-sized on the big screens (smaller than
 * on most Android tablets, which round their density up). [auto] picks a size from the screen's
 * short side, a little larger on color (Kaleido) screens, whose color filter softens text, and the
 * one size choice ([MenuSize], Look & size) scales that up or down.
 *
 * Settings also ignores a system font size below 100% (the Note Air 6C ships at 85%): it shrank
 * our text but not BOOX's own dialog buttons or our boxes around it. Larger font sizes still apply.
 */
object UiScale {
    /** Settings' size for this screen at the Large size choice. */
    fun auto(context: Context): Float {
        val inches = shortSideInches(context)
        val base = when {
            inches == null -> 1.0f
            inches < 4.0f -> 1.0f // 6" (Palma, Go 6, Poke)
            inches < 5.5f -> 1.1f // 7" to 8" (Leaf, Go 7, Tab Mini)
            inches < 7.2f -> 1.2f // 10.3" (Note Air, Tab Ultra, Go 10.3)
            else -> 1.3f // 13.3" (Tab X, Note Max)
        }
        return if (Devices.colorScreen == true) base + 0.05f else base
    }

    /** Settings' size: [auto] for this screen, scaled by the one size choice (Large = [auto]). */
    fun factor(context: Context): Float =
        auto(context) * Prefs(context).menuSize.scale / MenuSize.LARGE.scale

    /** The configuration Settings draws with: density times [factor], font size at least 100%. */
    fun apply(context: Context, base: Configuration): Configuration {
        val f = factor(context)
        return Configuration(base).apply {
            densityDpi = (base.densityDpi * f).roundToInt()
            screenWidthDp = (base.screenWidthDp / f).toInt()
            screenHeightDp = (base.screenHeightDp / f).toInt()
            smallestScreenWidthDp = (base.smallestScreenWidthDp / f).toInt()
            fontScale = base.fontScale.coerceIn(1f, 1.3f)
        }
    }

    /** The screen's short side in inches, from its pixels and physical dpi; null if the dpi looks wrong. */
    fun shortSideInches(context: Context): Float? {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val dpi = listOf(dm.xdpi, dm.ydpi).filter { it in 100f..600f }.minOrNull()
            ?: dm.densityDpi.toFloat().takeIf { it in 100f..600f } ?: return null
        return min(dm.widthPixels, dm.heightPixels) / dpi
    }

    /** "1860×2480 px · 300 dpi · Settings at 125% · system font 85%" for Troubleshooting and the debug report. */
    fun describe(context: Context): String {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        val dpi = context.applicationContext.resources.displayMetrics.densityDpi
        val pct = (factor(context) * 100).roundToInt()
        val font = (context.applicationContext.resources.configuration.fontScale * 100).roundToInt()
        return "${dm.widthPixels}×${dm.heightPixels} px · $dpi dpi · Settings at $pct% · system font $font%"
    }
}
