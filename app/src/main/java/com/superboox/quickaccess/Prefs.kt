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

    // ---- Clipboard hub ----

    /** The Clipboard button in the panel's top bar. */
    var clipShowButton: Boolean
        get() = sp.getBoolean(K_CLIP_SHOW, true)
        set(v) = sp.edit().putBoolean(K_CLIP_SHOW, v).apply()

    /** How many unpinned clips to keep. */
    var clipLimit: Int
        get() = sp.getInt(K_CLIP_LIMIT, 100)
        set(v) = sp.edit().putInt(K_CLIP_LIMIT, v).apply()

    /** The first-run tour was finished or skipped. */
    var tourDone: Boolean
        get() = sp.getBoolean(K_TOUR_DONE, false)
        set(v) = sp.edit().putBoolean(K_TOUR_DONE, v).apply()

    var clipKeepText: Boolean
        get() = sp.getBoolean(K_CLIP_TEXT, true)
        set(v) = sp.edit().putBoolean(K_CLIP_TEXT, v).apply()

    var clipKeepLinks: Boolean
        get() = sp.getBoolean(K_CLIP_LINKS, true)
        set(v) = sp.edit().putBoolean(K_CLIP_LINKS, v).apply()

    /** Short codes on their own (sign-in codes): off by default. */
    var clipKeepCodes: Boolean
        get() = sp.getBoolean(K_CLIP_CODES, false)
        set(v) = sp.edit().putBoolean(K_CLIP_CODES, v).apply()

    /** Handwriting copied with BOOX Notes' lasso. */
    var clipKeepHandwriting: Boolean
        get() = sp.getBoolean(K_CLIP_HANDWRITING, true)
        set(v) = sp.edit().putBoolean(K_CLIP_HANDWRITING, v).apply()

    /** While the clipboard is open, these parts of the panel still show (all off: it covers everything). */
    var clipShowControls: Boolean
        get() = sp.getBoolean(K_CLIP_SHOW_QC, false)
        set(v) = sp.edit().putBoolean(K_CLIP_SHOW_QC, v).apply()

    var clipShowLast: Boolean
        get() = sp.getBoolean(K_CLIP_SHOW_LAST, false)
        set(v) = sp.edit().putBoolean(K_CLIP_SHOW_LAST, v).apply()

    var clipShowApps: Boolean
        // Build 51's "covers the apps too" carries over.
        get() = sp.getBoolean(K_CLIP_SHOW_APPS, !sp.getBoolean(K_CLIP_COVERS_APPS, true))
        set(v) = sp.edit().putBoolean(K_CLIP_SHOW_APPS, v).apply()

    var clipKeepImages: Boolean
        get() = sp.getBoolean(K_CLIP_IMAGES, true)
        set(v) = sp.edit().putBoolean(K_CLIP_IMAGES, v).apply()

    var clipKeepAfterRestart: Boolean
        get() = sp.getBoolean(K_CLIP_KEEP_RESTART, true)
        set(v) = sp.edit().putBoolean(K_CLIP_KEEP_RESTART, v).apply()

    /** Apps whose copies are never kept (package names). */
    var clipIgnored: Set<String>
        get() = sp.getStringSet(K_CLIP_IGNORED, null) ?: ClipHistory.DEFAULT_IGNORED
        set(v) = sp.edit().putStringSet(K_CLIP_IGNORED, v).apply()

    /** [CLIP_PANEL]: save the clipboard when the panel opens; [CLIP_EVERY]: every copy (Advanced access). */
    var clipCatchMode: String
        get() = sp.getString(K_CLIP_MODE, CLIP_PANEL) ?: CLIP_PANEL
        set(v) = sp.edit().putString(K_CLIP_MODE, v).apply()

    /** Advanced access: SuperBoox presets set the light directly instead of borrowing Custom. */
    var independentPresets: Boolean
        get() = sp.getBoolean(K_ADV_INDEPENDENT, false)
        set(v) = sp.edit().putBoolean(K_ADV_INDEPENDENT, v).apply()

    /** The Advanced access light test: the user saw the light follow a direct change. */
    var lightTestPassed: Boolean
        get() = sp.getString(K_ADV_TEST, null) == "passed"
        set(v) = sp.edit().putString(K_ADV_TEST, if (v) "passed" else "failed").apply()

    val lightTestRun: Boolean get() = sp.contains(K_ADV_TEST)

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

    /** "This app" strip above the apps: opens BOOX's Optimize and EinkWise menus for the app in front. */
    var showThisApp: Boolean
        get() = sp.getBoolean(K_THIS_APP, true)
        set(v) = sp.edit().putBoolean(K_THIS_APP, v).apply()

    /** Quick Controls row in the panel (it grew out of the Back/Home/Recents row, same setting). */
    var showSystemActions: Boolean
        // Supernote's panel has no Back/Home row, so it's opt-in.
        get() = sp.getBoolean(K_SYSTEM_ACTIONS, false)
        set(v) = sp.edit().putBoolean(K_SYSTEM_ACTIONS, v).apply()

    /** Position of the Notes tab in the BOOX launcher's icon-only tab bar (older firmware), -1 = not learned yet. */
    var launcherNotesTab: Int
        get() = sp.getInt(K_NOTES_TAB, -1)
        set(v) = sp.edit().putInt(K_NOTES_TAB, v).apply()

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
        const val K_THIS_APP = "this_app"

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
        const val K_FL_BOOX_CUSTOM = "front_light_boox_custom"
        const val K_FL_MINE = "front_light_mine"
        const val K_FL_MINE_ACTIVE = "front_light_mine_active"
        const val K_LAST_BACKUP = "last_backup"
        const val K_ADV_INDEPENDENT = "adv_independent_presets"
        const val K_ADV_TEST = "adv_light_test"
        const val K_ADV_BOOT = "adv_boot"
        const val K_ADV_METHOD = "adv_light_method"
        const val K_FL_DIRECT_BEFORE = "front_light_direct_before"
        const val K_FL_LAST_ON = "front_light_last_on"
        /** Front-light settings seen with each BOOX preset, to recognise changes made outside SuperBoox. */
        const val K_FL_SIGNATURES = "front_light_signatures"
        /** Writing mode: on, what it switched (to put back), and what it's set to switch. */
        const val K_WM_ON = "writing_mode_on"
        const val K_WM_RESTORE = "writing_mode_restore"
        const val K_WM_OFF = "writing_mode_turns_off"
        const val K_WM_DND = "writing_mode_dnd"
        const val K_WM_NO_LIGHT = "writing_mode_no_light"
        const val K_CLIP_SHOW = "clip_show"
        const val K_CLIP_COVERS_APPS = "clip_covers_apps"
        const val K_CLIP_LIMIT = "clip_limit"
        const val K_CLIP_IMAGES = "clip_images"
        const val K_CLIP_KEEP_RESTART = "clip_keep_restart"
        const val K_CLIP_IGNORED = "clip_ignored"
        const val K_CLIP_MODE = "clip_mode"
        const val K_TOUR_DONE = "tour_done"
        /** When SuperBoox last stopped on an error (written as it stops), for Settings' status. */
        const val K_LAST_CRASH = "last_crash"
        const val K_NOTES_TAB = "boox_launcher_notes_tab"
        const val K_CLIP_TEXT = "clip_text"
        const val K_CLIP_LINKS = "clip_links"
        const val K_CLIP_CODES = "clip_codes"
        const val K_CLIP_HANDWRITING = "clip_handwriting"
        const val K_CLIP_SHOW_QC = "clip_show_qc"
        const val K_CLIP_SHOW_LAST = "clip_show_last"
        const val K_CLIP_SHOW_APPS = "clip_show_apps"
        const val CLIP_PANEL = "panel"
        const val CLIP_EVERY = "every"

        /** Keys whose change requires the edge handle window to be rebuilt. */
        val HANDLE_KEYS = setOf(K_EDGE, K_TRIGGER, K_HANDLE_WIDTH, K_HANDLE_HEIGHT, K_HANDLE_TOP, K_SHOW_HANDLE, K_HANDLE_ENABLED)
    }
}
