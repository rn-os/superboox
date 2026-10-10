package com.superboox.quickaccess

import org.json.JSONArray
import org.json.JSONObject

/**
 * Backup and restore of every SuperBoox setting, as one small JSON file the
 * user saves wherever they like (Settings → Backup & restore). Leaves out what
 * is only this device's live state: recents, last-seen switch states and the
 * Front Light preset in use.
 */
object Backup {
    private const val FORMAT = 1

    private val SKIP = setOf(
        Prefs.K_RECENTS, Prefs.K_QC_STATES, Prefs.K_FL_PRESET, Prefs.K_FL_LAST_ON,
        Prefs.K_FL_MINE_ACTIVE, Prefs.K_LAST_BACKUP,
    )

    /** What a backup file holds, ready to restore. */
    class Snapshot(val created: Long, val app: String, internal val values: Map<String, Any>) {
        val summary: String
            get() {
                fun count(key: String) = (values[key] as? String)?.let { runCatching { JSONArray(it).length() }.getOrNull() }
                return listOfNotNull(
                    count(Prefs.K_PINS)?.let { "$it Quick Access items" },
                    count(Prefs.K_QUICK_CONTROLS)?.let { "$it Quick Controls" },
                    count(Prefs.K_DOCK)?.let { "$it apps" },
                    count(Prefs.K_FL_MINE)?.let { "$it Front Light presets" },
                    "${values.size} settings in all",
                ).joinToString(", ")
            }
    }

    fun export(prefs: Prefs, appVersion: String): String {
        val out = JSONObject()
        for ((key, value) in prefs.sp.all) {
            if (key in SKIP || value == null) continue
            val entry = when (value) {
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is String -> JSONObject().put("t", "s").put("v", value)
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.map { it.toString() }))
                else -> continue
            }
            out.put(key, entry)
        }
        return JSONObject()
            .put("superboox_backup", FORMAT)
            .put("app", appVersion)
            .put("created", System.currentTimeMillis())
            .put("settings", out)
            .toString(2)
    }

    /** Reads a backup file; throws with a readable message if it isn't one. */
    fun parse(text: String): Snapshot {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IllegalArgumentException("That file isn't a SuperBoox backup") }
        if (!root.has("superboox_backup")) throw IllegalArgumentException("That file isn't a SuperBoox backup")
        val settings = root.optJSONObject("settings") ?: JSONObject()
        val values = LinkedHashMap<String, Any>()
        for (key in settings.keys()) {
            if (key in SKIP) continue
            val e = settings.optJSONObject(key) ?: continue
            values[key] = when (e.optString("t")) {
                "b" -> e.getBoolean("v")
                "i" -> e.getInt("v")
                "l" -> e.getLong("v")
                "f" -> e.getDouble("v").toFloat()
                "s" -> e.getString("v")
                "set" -> e.getJSONArray("v").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
                else -> continue
            }
        }
        return Snapshot(root.optLong("created"), root.optString("app"), values)
    }

    /** Replaces the current settings (except this device's live state) with [snapshot]. */
    fun restore(prefs: Prefs, snapshot: Snapshot) {
        val edit = prefs.sp.edit()
        prefs.sp.all.keys.filter { it !in SKIP }.forEach { edit.remove(it) }
        for ((key, value) in snapshot.values) {
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is Int -> edit.putInt(key, value)
                is Long -> edit.putLong(key, value)
                is Float -> edit.putFloat(key, value)
                is String -> edit.putString(key, value)
                is Set<*> -> edit.putStringSet(key, value as Set<String>)
            }
        }
        edit.apply()
        DebugLog.i("backup", "restored ${snapshot.values.size} settings from a backup made ${snapshot.created}")
    }
}
