package com.superboox.quickaccess

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

enum class Edge { LEFT, RIGHT }

/** Overall size of the panel's text, icons and touch targets. */
enum class MenuSize(val title: String, val scale: Float) {
    SMALL("Small", 0.85f),
    STANDARD("Standard", 1.0f),
    LARGE("Large", 1.2f),
    XL("Extra large", 1.4f),
}

enum class Trigger(val title: String) {
    SWIPE_IN("Swipe in from the edge"),
    SWIPE_ALONG("Swipe up or down along the edge"),
    DOUBLE_TAP("Double-tap the edge"),
}

/** All settings and pinned items, stored in one SharedPreferences file. */
class Prefs(context: Context) {
    private val appContext = context.applicationContext
    val sp: SharedPreferences = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var edge: Edge
        get() = enumOr(sp.getString(K_EDGE, null), Edge.RIGHT)
        set(v) = sp.edit().putString(K_EDGE, v.name).apply()

    var trigger: Trigger
        // Swipe up/down along the edge, like the Supernote side bar.
        get() = enumOr(sp.getString(K_TRIGGER, null), Trigger.SWIPE_ALONG)
        set(v) = sp.edit().putString(K_TRIGGER, v.name).apply()

    /** Width of the invisible touch strip at the screen edge. */
    var handleWidthDp: Int
        get() = sp.getInt(K_HANDLE_WIDTH, 16)
        set(v) = sp.edit().putInt(K_HANDLE_WIDTH, v).apply()

    /** Height of the strip as a percentage of the screen height. */
    var handleHeightPct: Int
        get() = sp.getInt(K_HANDLE_HEIGHT, 35)
        set(v) = sp.edit().putInt(K_HANDLE_HEIGHT, v).apply()

    /** Top of the strip as a percentage of the screen height. */
    var handleTopPct: Int
        get() = sp.getInt(K_HANDLE_TOP, 30)
        set(v) = sp.edit().putInt(K_HANDLE_TOP, v).apply()

    var showHandle: Boolean
        get() = sp.getBoolean(K_SHOW_HANDLE, true)
        set(v) = sp.edit().putBoolean(K_SHOW_HANDLE, v).apply()

    var handleEnabled: Boolean
        get() = sp.getBoolean(K_HANDLE_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_HANDLE_ENABLED, v).apply()

    var panelWidthPct: Int
        get() = sp.getInt(K_PANEL_WIDTH, 45)
        set(v) = sp.edit().putInt(K_PANEL_WIDTH, v).apply()

    var menuSize: MenuSize
        get() = enumOr(sp.getString(K_MENU_SIZE, null), MenuSize.LARGE) // easier to hit on e-ink
        set(v) = sp.edit().putString(K_MENU_SIZE, v.name).apply()

    /** Quick Controls and apps reopen on the page last shown instead of page 1. */
    var rememberPages: Boolean
        get() = sp.getBoolean(K_REMEMBER_PAGES, false)
        set(v) = sp.edit().putBoolean(K_REMEMBER_PAGES, v).apply()

    /** Pinned apps show the app's own (color) icon instead of the uniform black app glyph. */
    var appOwnIcons: Boolean
        get() = sp.getBoolean(K_APP_OWN_ICONS, false)
        set(v) = sp.edit().putBoolean(K_APP_OWN_ICONS, v).apply()

    /**
     * "Previous app" in the apps grid. It's an item in the dock list
     * ([PREVIOUS_APP]), so it can be moved like any app.
     */
    var previousAppInDock: Boolean
        get() {
            // Build 25 stored it as a plain switch (always first): carry that over once.
            if (sp.getBoolean(K_PREVIOUS_APP, false)) {
                sp.edit().remove(K_PREVIOUS_APP).apply()
                if (dock.none { it.value == PREVIOUS_APP }) dock = listOf(previousAppPin()) + dock
            }
            return dock.any { it.value == PREVIOUS_APP }
        }
        set(v) {
            sp.edit().remove(K_PREVIOUS_APP).apply()
            val rest = dock.filterNot { it.value == PREVIOUS_APP }
            dock = if (v) listOf(previousAppPin()) + rest else rest
        }

    private fun previousAppPin() = Pin(PinType.APP, "Previous app", PREVIOUS_APP)

    /** Quick Controls row in the panel (it grew out of the Back/Home/Recents row, same setting). */
    var showSystemActions: Boolean
        // Supernote's panel has no Back/Home row, so it's opt-in.
        get() = sp.getBoolean(K_SYSTEM_ACTIONS, false)
        set(v) = sp.edit().putBoolean(K_SYSTEM_ACTIONS, v).apply()

    var notesPackage: String
        get() = sp.getString(K_NOTES_PKG, null)?.takeIf { it.isNotBlank() } ?: BooxNotes.DEFAULT_PACKAGE
        set(v) = sp.edit().putString(K_NOTES_PKG, v.trim()).apply()

    /** SAF tree whose newest files appear under "Recent" (e.g. the BOOX note export folder). */
    var recentFolderUri: String?
        get() = sp.getString(K_RECENT_URI, null)
        set(v) = sp.edit().putString(K_RECENT_URI, v).apply()

    var recentCount: Int
        get() = sp.getInt(K_RECENT_COUNT, 5)
        set(v) = sp.edit().putInt(K_RECENT_COUNT, v).apply()

    var pins: List<Pin>
        get() = readPins(K_PINS)
        set(v) = writePins(K_PINS, v)

    var dock: List<Pin>
        get() {
            if (!sp.contains(K_DOCK)) return defaultDock()
            return readPins(K_DOCK)
        }
        set(v) = writePins(K_DOCK, v)

    fun addPin(pin: Pin) {
        pins = pins + pin
    }

    fun removePin(id: String) {
        pins = pins.filterNot { it.id == id }
    }

    private fun defaultDock(): List<Pin> {
        val pm = appContext.packageManager
        return BooxNotes.DEFAULT_DOCK_PACKAGES.mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val label = runCatching {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            }.getOrDefault(pkg)
            Pin(PinType.APP, label, launch.component?.flattenToString() ?: pkg)
        }
    }

    private fun readPins(key: String): List<Pin> {
        val raw = sp.getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { Pin.fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun writePins(key: String, pins: List<Pin>) {
        val arr = JSONArray()
        pins.forEach { arr.put(it.toJson()) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E =
        name?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: fallback

    companion object {
        const val FILE = "superboox"
        const val K_EDGE = "edge"
        const val K_TRIGGER = "trigger"
        const val K_HANDLE_WIDTH = "handle_width_dp"
        const val K_HANDLE_HEIGHT = "handle_height_pct"
        const val K_HANDLE_TOP = "handle_top_pct"
        const val K_SHOW_HANDLE = "show_handle"
        const val K_HANDLE_ENABLED = "handle_enabled"
        const val K_PANEL_WIDTH = "panel_width_pct"
        const val K_SYSTEM_ACTIONS = "system_actions"
        const val K_APP_OWN_ICONS = "app_own_icons"
        const val K_PREVIOUS_APP = "previous_app"

        /** Dock item value standing for "the app used before this one". */
        const val PREVIOUS_APP = "@previous"
        const val K_QUICK_CONTROLS = "quick_controls"
        const val K_QC_STATES = "quick_control_states"
        const val K_MENU_SIZE = "menu_size"
        const val K_RECENTS = "recents"
        const val K_NOTES_PKG = "notes_package"
        const val K_RECENT_URI = "recent_uri"
        const val K_RECENT_COUNT = "recent_count"
        const val K_PINS = "pins"
        const val K_DOCK = "dock"
        const val K_REMEMBER_PAGES = "remember_pages"
        const val K_FL_PRESET = "front_light_preset"
        const val K_FL_LEVELS = "front_light_levels"
        const val K_FL_MINE = "front_light_mine"
        const val K_FL_MINE_ACTIVE = "front_light_mine_active"
        const val K_LAST_BACKUP = "last_backup"
        const val K_FL_LAST_ON = "front_light_last_on"

        /** Keys whose change requires the edge handle window to be rebuilt. */
        val HANDLE_KEYS = setOf(K_EDGE, K_TRIGGER, K_HANDLE_WIDTH, K_HANDLE_HEIGHT, K_HANDLE_TOP, K_SHOW_HANDLE, K_HANDLE_ENABLED)
    }
}
