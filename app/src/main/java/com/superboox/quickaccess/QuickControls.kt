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
    ) {
        fun matches(normalized: String): Boolean =
            keys.any { normalized.startsWith(it) } && not.none { normalized.startsWith(it) }
    }

    private const val SWITCHES = "Switches"
    private const val BOOX = "BOOX"
    private const val ACTIONS = "Actions"

    val ALL: List<Control> = listOf(
        Control("wifi", "Wi‑Fi", R.drawable.ic_qc_wifi, Kind.TOGGLE, listOf("internet", "wi fi", "wifi", "wlan"), group = SWITCHES),
        Control("bluetooth", "Bluetooth", R.drawable.ic_qc_bluetooth, Kind.TOGGLE, listOf("bluetooth"), group = SWITCHES),
        Control("airplane", "Airplane", R.drawable.ic_qc_airplane, Kind.TOGGLE, listOf("airplane", "aeroplane", "flight"), group = SWITCHES),
        Control("dnd", "Do Not Disturb", R.drawable.ic_qc_dnd, Kind.TOGGLE, listOf("do not disturb"), group = SWITCHES),
        Control("rotate", "Auto‑rotate", R.drawable.ic_qc_rotate, Kind.TOGGLE, listOf("auto rotate", "autorotate"), group = SWITCHES),
        Control("mute", "Mute", R.drawable.ic_qc_mute, Kind.TOGGLE, listOf("mute"), group = SWITCHES),
        Control("location", "Location", R.drawable.ic_qc_location, Kind.TOGGLE, listOf("location"), group = SWITCHES),
        Control("einkwise", "EinkWise", R.drawable.ic_settings, Kind.TOGGLE, listOf("einkwise", "eink wise"), group = BOOX),
        Control("refresh", "Full Refresh", R.drawable.ic_qc_refresh, Kind.TOGGLE, listOf("full refresh"), group = BOOX),
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

    // ---- Operating a Control Center tile -----------------------------------------------

    private const val POLL_MS = 150L
    private const val FIND_TIMEOUT_MS = 2_500L
    private const val AFTER_TAP_MS = 600L

    /**
     * Opens the Control Center, taps (or, with [hold], long-presses) the tile for
     * [control], records the states it shows, and closes it again.
     * [done] gets whether the tile was found and pressed.
     */
    fun operate(service: AccessibilityService, prefs: Prefs, control: Control, hold: Boolean, done: (Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) {
            DebugLog.w("controls", "couldn't open the Control Center")
            return done(false)
        }
        val deadline = System.currentTimeMillis() + FIND_TIMEOUT_MS
        fun attempt() {
            val tiles = runCatching { readTiles(service) }.getOrDefault(emptyList())
            val tile = tiles.firstOrNull { control.matches(it.normalized) }
            if (tile == null) {
                if (System.currentTimeMillis() < deadline) return run { handler.postDelayed(::attempt, POLL_MS) }
                DebugLog.w("controls", "\"${control.label}\" not found in the Control Center (${tiles.size} tiles: ${tiles.joinToString { it.label }})")
                closeShade(service)
                return done(false)
            }
            saveStates(prefs, statesOf(tiles))
            val action = if (hold) AccessibilityNodeInfo.ACTION_LONG_CLICK else AccessibilityNodeInfo.ACTION_CLICK
            val pressed = pressable(tile.node, hold)?.performAction(action) == true
            DebugLog.i("controls", "${if (hold) "hold" else "tap"} \"${tile.label}\" (was ${tile.on?.let { if (it) "on" else "off" } ?: "?"}) -> $pressed")
            handler.postDelayed({
                if (control.kind == Kind.TOGGLE && !hold) {
                    val after = runCatching { readTiles(service) }.getOrDefault(emptyList())
                    saveStates(prefs, statesOf(after))
                    after.firstOrNull { control.matches(it.normalized) }?.on?.let {
                        DebugLog.i("controls", "\"${control.label}\" is now ${if (it) "on" else "off"}")
                    }
                }
                // After a tap, put the Control Center away (whatever the tile started is its own
                // window). After a hold, leave it: the tile's settings may open inside it.
                if (!hold) closeShade(service)
                done(pressed)
            }, AFTER_TAP_MS)
        }
        handler.postDelayed(::attempt, POLL_MS)
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
