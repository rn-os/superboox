package com.superboox.quickaccess

import org.json.JSONArray
import org.json.JSONObject

/**
 * Writing mode: one switch that turns off the radios and the front light, and puts back
 * exactly what it switched when it's turned off again.
 *
 * What it switched is kept in [Prefs.K_WM_RESTORE], so the mode survives sleep, a restart
 * and the service being rebound. Only things that were actually on are recorded: Bluetooth
 * that was already off stays off afterwards.
 */
object WritingMode {
    const val LIGHT = "light"
    const val DND = "dnd"

    /** Radios the mode can turn off, in the order they're switched. */
    val RADIOS = listOf("wifi", "bluetooth", "location")

    /** What a new install turns off. */
    private val DEFAULT_OFF = setOf("wifi", "bluetooth", LIGHT)

    fun isOn(prefs: Prefs): Boolean = prefs.sp.getBoolean(Prefs.K_WM_ON, false)

    fun setOn(prefs: Prefs, on: Boolean) {
        val edit = prefs.sp.edit().putBoolean(Prefs.K_WM_ON, on)
        if (!on) edit.remove(Prefs.K_WM_RESTORE)
        edit.apply()
    }

    /** The things ([RADIOS] ids and [LIGHT]) the mode turns off. */
    fun turnsOff(prefs: Prefs): Set<String> {
        val raw = prefs.sp.getString(Prefs.K_WM_OFF, null) ?: return DEFAULT_OFF
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(DEFAULT_OFF)
    }

    fun setTurnsOff(prefs: Prefs, ids: Set<String>) {
        prefs.sp.edit().putString(Prefs.K_WM_OFF, JSONArray(ids.toList()).toString()).apply()
    }

    /** Also turn Do Not Disturb on. */
    fun turnsOnDnd(prefs: Prefs): Boolean = prefs.sp.getBoolean(Prefs.K_WM_DND, false)

    fun setTurnsOnDnd(prefs: Prefs, on: Boolean) {
        prefs.sp.edit().putBoolean(Prefs.K_WM_DND, on).apply()
    }

    /**
     * False once the Control Center was seen without Front Light presets on a tablet where
     * they have never been seen: it has no front light, so the mode leaves it out.
     */
    fun hasLight(prefs: Prefs): Boolean =
        QuickControls.savedPreset(prefs) != null || !prefs.sp.getBoolean(Prefs.K_WM_NO_LIGHT, false)

    fun noLightSeen(prefs: Prefs) {
        if (QuickControls.savedPreset(prefs) == null) prefs.sp.edit().putBoolean(Prefs.K_WM_NO_LIGHT, true).apply()
    }

    // ---- What to put back --------------------------------------------------------------------

    private fun restore(prefs: Prefs): JSONObject =
        runCatching { JSONObject(prefs.sp.getString(Prefs.K_WM_RESTORE, "{}") ?: "{}") }.getOrDefault(JSONObject())

    /** Records that the mode switched [id]; [value] is what to put back ("on", "off", a preset name or "mine:<id>"). */
    fun remember(prefs: Prefs, id: String, value: String) {
        if (!isOn(prefs)) return
        prefs.sp.edit().putString(Prefs.K_WM_RESTORE, restore(prefs).put(id, value).toString()).apply()
    }

    /** Everything the mode switched, with what to put back. */
    fun switched(prefs: Prefs): Map<String, String> {
        val o = restore(prefs)
        return o.keys().asSequence().associateWith { o.getString(it) }
    }

    /** "Wi‑Fi, Bluetooth and Front Light are off", for the bar under the Quick Controls. */
    fun summary(prefs: Prefs): String {
        val done = switched(prefs)
        val off = (RADIOS + LIGHT).filter { it in done }.map(::label)
        val parts = ArrayList<String>()
        if (off.isNotEmpty()) parts += "${list(off)} ${if (off.size == 1) "is" else "are"} off"
        if (DND in done) parts += "Do Not Disturb is on"
        return if (parts.isEmpty()) "Everything was already off" else parts.joinToString("; ")
    }

    fun label(id: String): String = when (id) {
        LIGHT -> "Front Light"
        else -> QuickControls.byId(id)?.label ?: id
    }

    private fun list(items: List<String>): String = when (items.size) {
        1 -> items[0]
        else -> items.dropLast(1).joinToString(", ") + " and " + items.last()
    }
}
