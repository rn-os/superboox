package com.superboox.quickaccess

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Quick Controls: round toggle tiles in the panel, like the BOOX Control Center.
 *
 * Android doesn't let apps switch Wi-Fi, Bluetooth or Airplane mode, and BOOX's
 * own tiles (EinkWise, Full Refresh, NaviBall…) have no API. So a tile is
 * operated through the real Control Center: SuperBoox opens it behind the
 * screen hold ([OpeningCover]), taps (or holds) the matching tile, and closes
 * it again. The build 24 report showed all of its tiles, their on/off state and
 * long-press, are readable on firmware 4.3.
 *
 * Back, Home and the other actions use Android's global actions directly.
 */
object QuickControls {

    enum class Kind {
        /** Control Center tile that switches something on or off. */
        TOGGLE,

        /** Control Center tile that starts something (a tool, a screen). */
        TILE,

        /** Android global action. */
        ACTION,
    }

    class Control(
        val id: String,
        val label: String,
        val icon: Int,
        val kind: Kind,
        /** Normalized Control Center label prefixes that identify the tile. */
        val keys: List<String> = emptyList(),
        /** Labels that start with a key but are a different tile. */
        val not: List<String> = emptyList(),
        val globalAction: Int = 0,
        val group: String,
        /** Hold taps the tile instead of long-pressing it (its tap opens the menu wanted on hold). */
        val holdTaps: Boolean = false,
        /** Does something once (Full Refresh) rather than staying on: never shown filled. */
        val momentary: Boolean = false,
    ) {
        fun matches(normalized: String): Boolean =
            keys.any { normalized.startsWith(it) } && not.none { normalized.startsWith(it) }
    }

    const val FRONT_LIGHT = "frontlight"
    private const val SWITCHES = "Switches"
    private const val BOOX = "BOOX"
    private const val ACTIONS = "Actions"

    val ALL: List<Control> = listOf(
        Control("wifi", "Wi‑Fi", R.drawable.ic_qc_wifi, Kind.TOGGLE, listOf("internet", "wi fi", "wifi", "wlan"), group = SWITCHES),
        Control("bluetooth", "Bluetooth", R.drawable.ic_qc_bluetooth, Kind.TOGGLE, listOf("bluetooth"), group = SWITCHES),
        Control("airplane", "Airplane", R.drawable.ic_qc_airplane, Kind.TOGGLE, listOf("airplane", "aeroplane", "flight"), group = SWITCHES),
        Control("dnd", "Do Not Disturb", R.drawable.ic_qc_dnd, Kind.TOGGLE, listOf("do not disturb"), group = SWITCHES),
        // Tap switches rotation directly (BOOX's tile tap always opens its rotation menu);
        // hold opens that menu.
        Control("rotate", "Auto‑rotate", R.drawable.ic_qc_rotate, Kind.TOGGLE, listOf("auto rotate", "autorotate"), group = SWITCHES, holdTaps = true),
        Control("mute", "Mute", R.drawable.ic_qc_mute, Kind.TOGGLE, listOf("mute"), group = SWITCHES),
        Control("location", "Location", R.drawable.ic_qc_location, Kind.TOGGLE, listOf("location"), group = SWITCHES),
        Control("einkwise", "EinkWise", R.drawable.ic_settings, Kind.TOGGLE, listOf("einkwise", "eink wise"), group = BOOX),
        // Not a tile: BOOX's Front Light presets under the tiles (see pressPreset).
        Control(FRONT_LIGHT, "Front Light", R.drawable.ic_qc_light, Kind.TOGGLE, group = BOOX),
        Control("refresh", "Full Refresh", R.drawable.ic_qc_refresh, Kind.TOGGLE, listOf("full refresh"), group = BOOX, momentary = true),
        Control("handtouch", "Hand Touch", R.drawable.ic_qc_touch, Kind.TOGGLE, listOf("hand touch"), group = BOOX),
        Control("naviball", "NaviBall", R.drawable.ic_qc_naviball, Kind.TILE, listOf("naviball", "navi ball"), group = BOOX),
        Control("recorder", "Screen Recorder", R.drawable.ic_qc_record, Kind.TILE, listOf("screen recorder"), group = BOOX),
        Control("scrollshot", "Scrolling Shot", R.drawable.ic_screenshot, Kind.TILE, listOf("scrolling screenshot"), group = BOOX),
        Control("freemark", "FreeMark", R.drawable.ic_edit, Kind.TILE, listOf("freemark"), group = BOOX),
        Control("quicknotes", "Quick Notes", R.drawable.ic_note_add, Kind.TILE, listOf("quick notes"), group = BOOX),
        Control("booxdrop", "BOOXDrop", R.drawable.ic_qc_cast, Kind.TILE, listOf("booxdrop"), not = listOf("booxdrop screen"), group = BOOX),
        Control("mirroring", "Mirroring", R.drawable.ic_qc_cast, Kind.TILE, listOf("booxdrop screen"), group = BOOX),
        Control("enforcedoze", "EnforceDoze", R.drawable.ic_qc_power, Kind.TILE, listOf("enforcedoze"), group = BOOX),
        Control("back", "Back", R.drawable.ic_back, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_BACK, group = ACTIONS),
        Control("home", "Home", R.drawable.ic_home, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_HOME, group = ACTIONS),
        Control("recents", "Recents", R.drawable.ic_recents, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_RECENTS, group = ACTIONS),
        Control("alerts", "Alerts", R.drawable.ic_notifications, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, group = ACTIONS),
        Control("screenshot", "Screenshot", R.drawable.ic_screenshot, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT, group = ACTIONS),
        Control("lock", "Lock", R.drawable.ic_qc_lock, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN, group = ACTIONS),
        Control("split", "Split Screen", R.drawable.ic_qc_split, Kind.ACTION, globalAction = AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN, group = ACTIONS),
    )

    val GROUPS = listOf(SWITCHES, BOOX, ACTIONS)

    /** Starter set: the old Back/Home row's actions after the most useful switches. */
    val DEFAULT = listOf("wifi", "bluetooth", "airplane", "dnd", "refresh", "back", "home", "recents", "alerts", "screenshot")

    private val byId = ALL.associateBy { it.id }

    fun byId(id: String): Control? = byId[id]

    fun selected(prefs: Prefs): List<Control> {
        val raw = prefs.sp.getString(Prefs.K_QUICK_CONTROLS, null) ?: return DEFAULT.mapNotNull(::byId)
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { byId(arr.getString(it)) }
        }.getOrDefault(DEFAULT.mapNotNull(::byId))
    }

    fun save(prefs: Prefs, controls: List<Control>) {
        prefs.sp.edit().putString(Prefs.K_QUICK_CONTROLS, JSONArray(controls.map { it.id }).toString()).apply()
    }

    fun reset(prefs: Prefs) {
        prefs.sp.edit().remove(Prefs.K_QUICK_CONTROLS).apply()
    }

    // ---- On/off state -------------------------------------------------------------------

    /**
     * Best-known state of each toggle: what the Control Center showed last time
     * SuperBoox opened it, updated with what Android lets any app read.
     */
    fun states(context: Context, prefs: Prefs): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        runCatching {
            val o = JSONObject(prefs.sp.getString(Prefs.K_QC_STATES, "{}") ?: "{}")
            o.keys().forEach { out[it] = o.getBoolean(it) }
        }
        val cr = context.contentResolver
        fun global(name: String) = runCatching { Settings.Global.getInt(cr, name) }.getOrNull()
        global(Settings.Global.AIRPLANE_MODE_ON)?.let { out["airplane"] = it != 0 }
        global("wifi_on")?.let { out["wifi"] = it != 0 }
        global("bluetooth_on")?.let { out["bluetooth"] = it != 0 }
        runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION) }.getOrNull()?.let { out["rotate"] = it != 0 }
        runCatching {
            val nm = context.getSystemService(NotificationManager::class.java)
            out["dnd"] = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
        }
        runCatching { out["location"] = context.getSystemService(LocationManager::class.java).isLocationEnabled }
        return out
    }

    private fun saveStates(prefs: Prefs, seen: Map<String, Boolean>) {
        if (seen.isEmpty()) return
        val o = runCatching { JSONObject(prefs.sp.getString(Prefs.K_QC_STATES, "{}") ?: "{}") }.getOrDefault(JSONObject())
        seen.forEach { (k, v) -> o.put(k, v) }
        prefs.sp.edit().putString(Prefs.K_QC_STATES, o.toString()).apply()
    }

    // ---- Operating Control Center tiles ------------------------------------------------

    private const val POLL_MS = 150L
    private const val FIND_TIMEOUT_MS = 2_500L
    const val AFTER_TAP_MS = 600L
    private const val SWITCH_MS = 1_000L
    private val CONTROL_CENTER_IDS = listOf("control_center_ll", "tablet_control_title")
    private val NOTIFICATION_IDS = listOf("onyx_notification_manager_view", "notification_stack_scroller")

    /**
     * Opens the Control Center and calls [ready] once its tiles can be read
     * (false: they never appeared). After Alerts, BOOX can answer "open quick
     * settings" with the notification list; if the Control Center hasn't shown
     * up within [SWITCH_MS], close the shade and pull down from the top-right,
     * where BOOX keeps the Control Center.
     */
    fun open(service: AccessibilityService, ready: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            DebugLog.w("controls", "couldn't open the Control Center")
            return ready(false)
        }
        val start = System.currentTimeMillis()
        var deadline = start + FIND_TIMEOUT_MS
        var pulls = 0
        var lastPull = 0L
        fun check() {
            if (runCatching { controlCenterShowing(service) }.getOrDefault(false)) return ready(true)
            val now = System.currentTimeMillis()
            val wrong = runCatching { notificationsShowing(service) }.getOrDefault(false)
            if (pulls == 0 && (now - start >= SWITCH_MS || wrong)) {
                pulls = 1
                lastPull = now
                DebugLog.w("controls", "${if (wrong) "notifications opened" else "nothing opened"} instead of the Control Center; pulling down from the top-right")
                closeShade(service)
                handler.postDelayed({ pullDown(service, 0.8f, "controls") }, 500)
                deadline = now + 500 + FIND_TIMEOUT_MS
                handler.postDelayed(::check, 900)
                return
            }
            // Full-screen apps (NeoReader): the first swipe only brings the status bar back.
            if (pulls == 1 && now - lastPull >= 1_400) {
                pulls = 2
                DebugLog.w("controls", "still no Control Center; pulling down again")
                if (wrong) closeShade(service)
                handler.postDelayed({ pullDown(service, 0.8f, "controls") }, if (wrong) 500 else 0)
                deadline = now + 600 + FIND_TIMEOUT_MS
                handler.postDelayed(::check, 900)
                return
            }
            if (now < deadline) handler.postDelayed(::check, POLL_MS) else ready(false)
        }
        handler.postDelayed(::check, POLL_MS)
    }

    /**
     * True when the Control Center itself is on screen, not just the shade:
     * three or more tiles SuperBoox knows, or BOOX's Control Center layout.
     */
    fun controlCenterShowing(service: AccessibilityService): Boolean {
        val tiles = runCatching { readTiles(service) }.getOrDefault(emptyList())
        if (tiles.count { t -> ALL.any { it.kind != Kind.ACTION && it.matches(t.normalized) } } >= 3) return true
        return shadeHas(service, CONTROL_CENTER_IDS)
    }

    /**
     * Taps (or, with [hold], long-presses) the tile for [control] in the open
     * Control Center. Returns the tile's state before the press (null: unknown),
     * or [NOT_FOUND]. Also records every state it sees.
     */
    fun press(service: AccessibilityService, prefs: Prefs, control: Control, hold: Boolean): Press {
        val tiles = runCatching { readTiles(service) }.getOrDefault(emptyList())
        saveStates(prefs, statesOf(tiles))
        val tile = tiles.firstOrNull { control.matches(it.normalized) }
        if (tile == null) {
            DebugLog.w("controls", "\"${control.label}\" not found in the Control Center (${tiles.size} tiles: ${tiles.joinToString { it.label }})")
            return Press(found = false, pressed = false, before = null)
        }
        val long = hold && !control.holdTaps
        val action = if (long) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
        val pressed = pressable(tile.node, long)?.performAction(action) == true
        DebugLog.i("controls", "${if (hold) "hold" else "tap"} \"${tile.label}\" (was ${tile.on?.let { if (it) "on" else "off" } ?: "?"}) -> $pressed")
        return Press(found = true, pressed = pressed, before = tile.on)
    }

    class Press(val found: Boolean, val pressed: Boolean, val before: Boolean?)

    /**
     * After a tap on a toggle: reads its new state. Some switches (Wi-Fi) take a
     * few seconds, so an unchanged state is taken as "still switching" and the
     * flipped state is recorded. Returns the state to show.
     */
    fun settle(service: AccessibilityService, prefs: Prefs, control: Control, before: Boolean?): Boolean? {
        val tiles = runCatching { readTiles(service) }.getOrDefault(emptyList())
        val seen = statesOf(tiles).toMutableMap()
        val now = tiles.firstOrNull { control.matches(it.normalized) }?.on
        val result = when {
            before == null -> now
            now == null || now == before -> !before
            else -> now
        }
        if (result != null) seen[control.id] = result
        saveStates(prefs, seen)
        DebugLog.i("controls", "\"${control.label}\" is now ${result?.let { if (it) "on" else "off" } ?: "?"}${if (now == before && before != null) " (still switching)" else ""}")
        return result
    }

    fun close(service: AccessibilityService) = closeShade(service)

    /**
     * The notification shade. On BOOX, Android's "open notifications" brings back
     * whichever side of the shade was used last, usually the Control Center after
     * Quick Controls. So pull down from the top-left, where BOOX keeps
     * notifications; if they still aren't showing, fall back to Android's action.
     */
    fun openNotifications(service: AccessibilityService) {
        val handler = Handler(Looper.getMainLooper())
        if (runCatching { notificationsShowing(service) }.getOrDefault(false)) return DebugLog.i("alerts", "notifications already open")
        val ccOpen = runCatching { controlCenterShowing(service) }.getOrDefault(false)
        if (ccOpen) closeShade(service)
        handler.postDelayed({ pullDown(service, 0.2f, "alerts") }, if (ccOpen) 500 else 200)
        handler.postDelayed({
            if (runCatching { notificationsShowing(service) }.getOrDefault(false)) return@postDelayed DebugLog.i("alerts", "notifications opened")
            DebugLog.w("alerts", "pull-down didn't show notifications; using Android's action")
            logShade(service, "alerts")
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
        }, if (ccOpen) 1_500 else 1_200)
    }

    /** True when BOOX's notification list is on screen. */
    private fun notificationsShowing(service: AccessibilityService): Boolean = shadeHas(service, NOTIFICATION_IDS)

    private fun shadeHas(service: AccessibilityService, ids: List<String>): Boolean {
        for (w in service.windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (!pkg.contains("systemui")) continue
            if (ids.any { id -> root.findAccessibilityNodeInfosByViewId("$pkg:id/$id").any { it.isVisibleToUser } }) return true
        }
        return false
    }

    /** Swipes down from the top edge at [xFraction] of the width: BOOX opens notifications on the left, the Control Center on the right. */
    private fun pullDown(service: AccessibilityService, xFraction: Float, tag: String) {
        val dm = service.resources.displayMetrics
        val x = dm.widthPixels * xFraction
        val path = android.graphics.Path().apply {
            moveTo(x, 2f)
            lineTo(x, dm.heightPixels * 0.45f)
        }
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        DebugLog.i(tag, "pulling down from the top-${if (xFraction < 0.5f) "left" else "right"}")
        service.dispatchGesture(gesture, null, null)
    }

    private fun logShade(service: AccessibilityService, tag: String) {
        runCatching {
            service.windows.filter { it.root?.packageName?.toString()?.contains("systemui") == true }
                .forEach { DebugLog.i(tag, "shade:\n" + DebugReport.describeTree(it.root, 120)) }
        }
    }

    /**
     * Auto-rotate without BOOX's rotation menu: flips Android's own setting.
     * Needs "Modify system settings" (granted once in Android settings); returns
     * the new state, or null when that isn't granted yet.
     */
    fun toggleRotation(context: Context): Boolean? {
        if (!Settings.System.canWrite(context)) return null
        val cr = context.contentResolver
        val on = runCatching { Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION) }.getOrDefault(0) != 0
        val ok = runCatching { Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1) }.getOrDefault(false)
        DebugLog.i("controls", "auto-rotate set directly: ${if (on) "off" else "on"} -> $ok")
        return if (ok) !on else on
    }

    // ---- Front Light presets ---------------------------------------------------------------

    /** BOOX's Front Light presets, in its order. */
    val PRESETS = listOf("Bright", "Soft", "Custom", "Off")
    const val PRESET_OFF = "Off"

    /** The preset last seen or chosen (null: never seen). */
    fun savedPreset(prefs: Prefs): String? = prefs.sp.getString(Prefs.K_FL_PRESET, null)

    /** The preset to go back to when the light is switched on again. */
    fun lastOnPreset(prefs: Prefs): String = prefs.sp.getString(Prefs.K_FL_LAST_ON, null) ?: "Bright"

    private fun savePreset(prefs: Prefs, preset: String) {
        val edit = prefs.sp.edit().putString(Prefs.K_FL_PRESET, preset)
        if (preset != PRESET_OFF) edit.putString(Prefs.K_FL_LAST_ON, preset)
        edit.apply()
        saveStates(prefs, mapOf(FRONT_LIGHT to (preset != PRESET_OFF)))
    }

    /**
     * Taps BOOX's [preset] button in the open Control Center. Returns whether
     * the light was on before (from the selected preset), or [Press.found] false
     * when the presets can't be seen (they may be scrolled out of view).
     */
    fun pressPreset(service: AccessibilityService, prefs: Prefs, preset: String): Press {
        val buttons = runCatching { presetButtons(service) }.getOrDefault(emptyMap())
        val before = selectedPreset(buttons)
        before?.let { savePreset(prefs, it) }
        val button = buttons[preset]
        if (button == null) {
            DebugLog.w("controls", "Front Light presets not found (${buttons.keys.joinToString().ifEmpty { "none" }})")
            return Press(found = false, pressed = false, before = null)
        }
        // Tapping the preset already in use opens BOOX's own editor for it (Custom): don't.
        if (before == preset) {
            DebugLog.i("controls", "front light: \"$preset\" already selected")
            return Press(found = true, pressed = true, before = before != PRESET_OFF)
        }
        val pressed = button.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        DebugLog.i("controls", "front light: tap \"$preset\" (was ${before ?: "?"}) -> $pressed")
        return Press(found = true, pressed = pressed, before = before?.let { it != PRESET_OFF })
    }

    /** After [pressPreset]: records the preset now selected (taken as [preset] if it can't be read). */
    fun settlePreset(service: AccessibilityService, prefs: Prefs, preset: String) {
        val now = runCatching { selectedPreset(presetButtons(service)) }.getOrNull()
        savePreset(prefs, now ?: preset)
        DebugLog.i("controls", "front light is now ${now ?: "$preset (assumed)"}")
    }

    /** BOOX's Custom front light: brightness and warmth, each 0–[LEVEL_MAX]. */
    data class Levels(val brightness: Int, val warmth: Int) {
        fun clamp() = Levels(brightness.coerceIn(0, LEVEL_MAX), warmth.coerceIn(0, LEVEL_MAX))
        override fun toString() = "brightness $brightness, warmth $warmth"
    }

    const val LEVEL_MAX = 32

    /** Custom's levels as last read from (or set in) the Control Center. */
    fun savedLevels(prefs: Prefs): Levels? = prefs.sp.getString(Prefs.K_FL_LEVELS, null)?.split(",")
        ?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { Levels(it[0], it[1]) }

    /**
     * Your own BOOX Custom levels: Custom as it was when none of SuperBoox's
     * presets was borrowing it. Picking Custom in the panel puts these back.
     */
    fun booxCustom(prefs: Prefs): Levels? = prefs.sp.getString(Prefs.K_FL_BOOX_CUSTOM, null)?.split(",")
        ?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 }?.let { Levels(it[0], it[1]) }

    private fun saveLevels(prefs: Prefs, l: Levels) {
        val edit = prefs.sp.edit().putString(Prefs.K_FL_LEVELS, "${l.brightness},${l.warmth}")
        if (activeMine(prefs) == null) edit.putString(Prefs.K_FL_BOOX_CUSTOM, "${l.brightness},${l.warmth}")
        edit.apply()
    }

    /**
     * Reads Custom's sliders. Only meaningful while Custom is selected: BOOX
     * shows the selected preset's levels in them.
     */
    fun readLevels(service: AccessibilityService, prefs: Prefs): Levels? {
        val buttons = runCatching { presetButtons(service) }.getOrDefault(emptyMap())
        if (selectedPreset(buttons) != "Custom") return null
        val (b, w) = levelNodes(service) ?: return null
        val levels = Levels(b.rangeInfo?.current?.roundToInt() ?: return null, w.rangeInfo?.current?.roundToInt() ?: return null)
        saveLevels(prefs, levels)
        DebugLog.i("controls", "front light Custom: $levels")
        return levels
    }

    /** Sets Custom's sliders (Custom must already be selected). */
    fun setLevels(service: AccessibilityService, prefs: Prefs, target: Levels): Boolean {
        val nodes = levelNodes(service) ?: return false.also { DebugLog.w("controls", "front light sliders not found") }
        val ok = listOf(nodes.first to target.brightness, nodes.second to target.warmth).all { (bar, v) -> setSlider(bar, v) }
        DebugLog.i("controls", "front light Custom -> $target: $ok")
        if (ok) saveLevels(prefs, target)
        return ok
    }

    private fun setSlider(bar: AccessibilityNodeInfo, value: Int): Boolean {
        val args = android.os.Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, value.toFloat())
        }
        return bar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args)
    }

    /** Custom's brightness and warmth sliders (SeekBars inside BOOX's two level rows). */
    private fun levelNodes(service: AccessibilityService): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo>? {
        for (w in service.windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString() ?: continue
            if (!pkg.contains("systemui")) continue
            fun slider(panel: String) = root.findAccessibilityNodeInfosByViewId("$pkg:id/$panel").firstOrNull()
                ?.findAccessibilityNodeInfosByViewId("$pkg:id/slider")?.firstOrNull { it.rangeInfo != null }
            val b = slider("brightness_bright_panel") ?: continue
            val t = slider("warm_panel") ?: continue
            return b to t
        }
        return null
    }

    // ---- Your own Front Light presets (applied through Custom) ----------------------------

    data class LightPreset(val id: String, val name: String, val levels: Levels)

    fun myPresets(prefs: Prefs): List<LightPreset> = runCatching {
        val arr = JSONArray(prefs.sp.getString(Prefs.K_FL_MINE, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            LightPreset(o.getString("id"), o.getString("name"), Levels(o.getInt("b"), o.getInt("w")).clamp())
        }
    }.getOrDefault(emptyList())

    fun saveMyPresets(prefs: Prefs, list: List<LightPreset>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("b", it.levels.brightness).put("w", it.levels.warmth)) }
        prefs.sp.edit().putString(Prefs.K_FL_MINE, arr.toString()).apply()
    }

    fun newPresetId(): String = "fl" + System.currentTimeMillis().toString(36)

    /** Your preset in use (only while the light is on Custom). */
    fun activeMine(prefs: Prefs): String? = prefs.sp.getString(Prefs.K_FL_MINE_ACTIVE, null)

    fun setActiveMine(prefs: Prefs, id: String?) {
        prefs.sp.edit().apply { if (id == null) remove(Prefs.K_FL_MINE_ACTIVE) else putString(Prefs.K_FL_MINE_ACTIVE, id) }.apply()
    }

    /** Scrolls the Control Center down, to bring the Front Light presets into view. */
    fun scrollShade(service: AccessibilityService): Boolean {
        for (w in service.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString()?.contains("systemui") != true) continue
            val scroll = root.findAccessibilityNodeInfosByViewId("${root.packageName}:id/expanded_qs_scroll_view").firstOrNull() ?: continue
            return scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        return false
    }

    /** Preset name to its button, found from the "Soft" label up to the row that holds all four. */
    private fun presetButtons(service: AccessibilityService): Map<String, AccessibilityNodeInfo> {
        for (w in service.windows) {
            val root = w.root ?: continue
            if (root.packageName?.toString()?.contains("systemui") != true) continue
            val soft = labelled(root, setOf("soft"))["soft"]?.firstOrNull() ?: continue
            var area: AccessibilityNodeInfo? = soft.parent
            repeat(6) {
                val a = area ?: return@repeat
                val found = labelled(a, PRESETS.map { it.lowercase() }.toSet())
                if (found.size == PRESETS.size) {
                    val labels = PRESETS.associateWith { found.getValue(it.lowercase()).first() }
                    val buttons = labels.mapValues { (_, n) -> pressable(n, false) ?: n }
                    // One clickable around all four would be the row, not a button: tap the labels.
                    return if (buttons.values.toSet().size == PRESETS.size) buttons else labels
                }
                area = a.parent
            }
        }
        return emptyMap()
    }

    /** Nodes under [root] whose whole label is one of [names] (lowercase). */
    private fun labelled(root: AccessibilityNodeInfo, names: Set<String>): Map<String, List<AccessibilityNodeInfo>> {
        val out = HashMap<String, MutableList<AccessibilityNodeInfo>>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (depth > 40) return
            val label = (n.text ?: n.contentDescription)?.toString()?.trim()?.lowercase()
            if (label != null && label in names) out.getOrPut(label) { ArrayList() } += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    /** The preset whose button (or anything in it) is selected or checked. */
    private fun selectedPreset(buttons: Map<String, AccessibilityNodeInfo>): String? =
        buttons.entries.firstOrNull { (_, b) -> anySelected(b, 0) }?.key

    private fun anySelected(n: AccessibilityNodeInfo, depth: Int): Boolean {
        if (n.isSelected || (n.isCheckable && n.isChecked)) return true
        if (depth > 4) return false
        return (0 until n.childCount).any { i -> n.getChild(i)?.let { anySelected(it, depth + 1) } == true }
    }

    private class Tile(val node: AccessibilityNodeInfo, val label: String, val normalized: String, val on: Boolean?)

    private fun statesOf(tiles: List<Tile>): Map<String, Boolean> {
        val out = HashMap<String, Boolean>()
        for (t in tiles) {
            val on = t.on ?: continue
            ALL.firstOrNull { it.kind == Kind.TOGGLE && it.matches(t.normalized) }?.let { out[it.id] = on }
        }
        return out
    }

    private fun readTiles(service: AccessibilityService): List<Tile> {
        val out = ArrayList<Tile>()
        for (w in service.windows) {
            val root = w.root ?: continue
            val pkg = root.packageName?.toString()
            if (pkg == service.packageName) continue
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM && pkg?.contains("systemui") != true) continue
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 40) return
                val label = (n.contentDescription ?: n.text)?.toString()?.replace("\n", " ")?.trim().orEmpty()
                if (label.isNotEmpty() && (n.isClickable || n.isCheckable || n.isLongClickable)) {
                    out += Tile(n, label, normalize(label), stateOf(n))
                }
                for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
            }
            walk(root, 0)
        }
        return out
    }

    /** "On"/"Off" as the tile announces it, else its checked state, else unknown. */
    private fun stateOf(n: AccessibilityNodeInfo): Boolean? {
        val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) n.stateDescription?.toString()?.trim()?.lowercase() else null
        if (desc != null) {
            if (desc.startsWith("on")) return true
            if (desc.startsWith("off")) return false
        }
        return if (n.isCheckable) n.isChecked else null
    }

    private fun pressable(n: AccessibilityNodeInfo, hold: Boolean): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(4) {
            val c = cur ?: return null
            if (if (hold) c.isLongClickable else c.isClickable) return c
            cur = c.parent
        }
        return n
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    private fun closeShade(service: AccessibilityService) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
}
