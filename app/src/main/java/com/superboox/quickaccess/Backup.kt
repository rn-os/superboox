package com.superboox.quickaccess

import android.content.Context
import android.util.Base64
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
        Prefs.K_RECENTS, Prefs.K_RECENT_HIDDEN, Prefs.K_QC_STATES, Prefs.K_FL_PRESET, Prefs.K_FL_LAST_ON,
        Prefs.K_FL_MINE_ACTIVE, Prefs.K_LAST_BACKUP, Prefs.K_FL_LEVELS, Prefs.K_FL_BOOX_CUSTOM,
        Prefs.K_ADV_BOOT, Prefs.K_ADV_TEST, Prefs.K_TOUR_DONE, Prefs.K_WHATS_NEW, Prefs.K_LAST_CRASH, Prefs.K_NOTES_TAB, Prefs.K_FL_SIGNATURES, Prefs.K_FL_DIRECT_BEFORE, Prefs.K_ADV_METHOD,
        Prefs.K_WM_ON, Prefs.K_WM_RESTORE, Prefs.K_WM_NO_LIGHT,
        // A picked folder only works on the tablet that picked it.
        Prefs.K_DIGEST_EXPORT_URI, Prefs.K_DIGEST_EXPORTED,
    )

    /** What a backup file holds, ready to restore. */
    class Snapshot(
        val created: Long,
        val app: String,
        internal val values: Map<String, Any>,
        internal val ink: JSONObject,
    ) {
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

    fun export(context: Context, prefs: Prefs, appVersion: String): String {
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
        val ink = JSONObject()
        Digests.captures(context).mapNotNull { it.inkId }.distinct().forEach { id ->
            val file = Digests.inkFile(context, id)
            if (file?.isFile == true) ink.put(id, Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
        }
        return JSONObject()
            .put("capture_ink", ink)
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
        return Snapshot(root.optLong("created"), root.optString("app"), values,
            root.optJSONObject("capture_ink") ?: JSONObject())
    }

    /** Replaces the current settings (except this device's live state) with [snapshot]. */
    fun restore(context: Context, prefs: Prefs, snapshot: Snapshot) {
        for (id in snapshot.ink.keys()) {
            val file = Digests.inkFile(context, id) ?: continue
            if (file.isFile) continue
            val data = Base64.decode(snapshot.ink.getString(id), Base64.NO_WRAP)
            val info = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, info)
            require(info.outMimeType == "image/png" && info.outWidth in 1..1600 && info.outHeight in 1..1600) {
                "Invalid handwriting image in backup"
            }
            file.parentFile?.mkdirs()
            val atomic = android.util.AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(data)
                atomic.finishWrite(stream)
            } catch (error: Exception) {
                atomic.failWrite(stream)
                throw error
            }
        }
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
