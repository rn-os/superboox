package com.superboox.quickaccess

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Adb test hooks for the tablet test script (tools/tablet-test.sh). Only compiled in for unpublished
 * branch/PR test builds (BuildConfig.TEST_HOOKS), and even then each command is refused unless
 * `files/testhooks.on` exists, which only `adb shell run-as` can create. Never in a published release.
 *
 *   adb shell am broadcast -a com.superboox.quickaccess.TEST -p com.superboox.quickaccess --es cmd <cmd> --es id <n> [--es k v ...]
 *
 * Every command answers in logcat: `SBTEST <id> ok|fail <cmd> {json}`. Everything it makes is marked
 * (clips with [ClipHistory.TEST_SOURCE], recents titled "SBTEST ...") so it can be removed again, and
 * `snapshot` / `restore` put every SuperBoox setting back as it was. It never touches the system
 * clipboard, a note, or BOOX's own files, except `copy_clip` / `paste_clip`, which are asked for explicitly.
 */
object TestHooks {
    const val ACTION = "com.superboox.quickaccess.TEST"
    private const val TAG = "SBTEST"
    private const val GATE = "testhooks.on"
    private const val UI_DUMP = "ui-dump.json"
    private const val SNAPSHOT = "testhooks-snapshot.json"
    private const val RECENT_PREFIX = "SBTEST "
    private val PREF_FILES = listOf(Prefs.FILE, "clipboard", "recent_apps_state")

    private var receiver: BroadcastReceiver? = null

    fun register(service: QuickAccessService) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handle(service, intent)
        }
        receiver = r
        val filter = IntentFilter(ACTION)
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) service.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
            else service.registerReceiver(r, filter)
        }.onFailure { receiver = null }
        DebugLog.i("testhooks", "test hooks registered (needs files/$GATE to act)")
    }

    fun unregister(service: QuickAccessService) {
        receiver?.let { r -> runCatching { service.unregisterReceiver(r) } }
        receiver = null
    }

    private fun handle(service: QuickAccessService, intent: Intent) {
        val cmd = intent.getStringExtra("cmd") ?: "?"
        val id = intent.getStringExtra("id") ?: "0"
        fun answer(ok: Boolean, data: JSONObject = JSONObject()) {
            val line = "$id ${if (ok) "ok" else "fail"} $cmd $data"
            Log.i(TAG, line)
            DebugLog.i("testhooks", line)
        }
        if (!File(service.filesDir, GATE).exists()) return answer(false, JSONObject().put("error", "files/$GATE missing (create it with run-as)"))
        runCatching { answer(true, run(service, cmd, intent)) }
            .onFailure { answer(false, JSONObject().put("error", "${it.javaClass.simpleName}: ${it.message}")) }
    }

    private fun run(s: QuickAccessService, cmd: String, i: Intent): JSONObject {
        val prefs = Prefs(s)
        fun arg(k: String) = i.getStringExtra(k)
        return when (cmd) {
            "ping" -> JSONObject().put("version", BuildConfig.VERSION_NAME)
            "snapshot" -> JSONObject().put("saved", snapshot(s))
            "restore" -> JSONObject().put("restored", restore(s))
            // A text clip, as if copied in an app (kept like any other copy, named after the source "test").
            "add_text" -> {
                val c = ClipHistory.insertTestClip(s, ClipHistory.Kind.TEXT, arg("text") ?: "SBTEST text", null, arg("name"), arg("pin") == "1")
                JSONObject().put("clip", c.id)
            }
            // A picture clip with a drawn test picture (a screenshot / image copy).
            "add_image" -> {
                val file = "sbtest_${System.currentTimeMillis()}.png"
                drawTestPicture(File(ClipHistory.dir(s), file), arg("text") ?: "SBTEST image")
                val c = ClipHistory.insertTestClip(s, ClipHistory.Kind.IMAGE, "", file, arg("name"), arg("pin") == "1")
                JSONObject().put("clip", c.id)
            }
            // A handwriting copy: a drawn strokes picture and a link BOOX no longer has, so the
            // preview, name, pin and actions can be checked without anyone writing with the pen.
            "add_handwriting" -> {
                val file = "sbtest_${System.currentTimeMillis()}.png"
                drawTestPicture(File(ClipHistory.dir(s), file), arg("text") ?: "SBTEST handwriting")
                val c = ClipHistory.insertTestClip(
                    s, ClipHistory.Kind.HANDWRITING,
                    "content://com.onyx.android.note.onyx.fileprovider/external/.noteCache/CopyPoints/sbtest_${System.currentTimeMillis()}",
                    file, arg("name"), arg("pin") == "1", "NOTE_SHAPES", "text/uri-list",
                )
                JSONObject().put("clip", c.id)
            }
            "remove_test_clips" -> JSONObject().put("removed", ClipHistory.removeTestClips(s))
            // First half of a paste: puts the clip (by id, or the nth newest, 0 = newest) on the system clipboard.
            "copy_clip" -> JSONObject().put("ok", ClipHistory.putBack(s, pickClip(s, i)))
            // Whole paste: puts it back and pastes into the text box that has focus in the app in front.
            "paste_clip" -> {
                val ok = ClipHistory.putBack(s, pickClip(s, i))
                if (ok) s.pasteIntoApp()
                JSONObject().put("ok", ok)
            }
            // Notes in Last Opened / Recent Files, titled "SBTEST ...", so none of the user's own are touched.
            "seed_recents" -> {
                val names = (arg("names") ?: "Alpha,Beta,Gamma").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val now = System.currentTimeMillis()
                // Oldest first, so the first name given ends up newest.
                names.reversed().forEachIndexed { n, name ->
                    Recents.record(prefs, RecentItem(RecentItem.Kind.NOTE, RECENT_PREFIX + name, prefs.notesPackage, now - (names.size - n) * 1000L))
                }
                JSONObject().put("seeded", names.size)
            }
            "clear_test_recents" -> {
                val mine = Recents.list(prefs).filter { it.title.startsWith(RECENT_PREFIX) }
                mine.forEach { Recents.remove(prefs, it.key) }
                JSONObject().put("removed", mine.size)
            }
            // Pretend this app was the last one used, so Previous app shows it (pick one that isn't in front).
            "set_previous" -> {
                s.testRememberApp(arg("pkg") ?: error("pkg required"))
                JSONObject().put("previous", s.previousApp ?: JSONObject.NULL)
            }
            // The apps in the grid: comma-separated package names, replacing the current list.
            "set_dock" -> {
                val pkgs = (arg("pkgs") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val pins = pkgs.map { Pin(PinType.APP, Launcher.appLabel(s, it), it) }
                prefs.dock = pins
                JSONObject().put("apps", pkgs.size).put("labels", JSONArray(pins.map { it.label }))
            }
            "open_panel" -> JSONObject().also { s.showPanel() }
            "close_panel" -> JSONObject().also { s.hidePanel() }
            "set_previous_in_dock" -> {
                prefs.previousAppInDock = arg("on") == "1"
                JSONObject().put("on", prefs.previousAppInDock)
            }
            "set_dock_layout" -> {
                prefs.dockLayout = arg("layout")?.toIntOrNull() ?: Prefs.DOCK_AUTO
                JSONObject().put("layout", prefs.dockLayout)
            }
            // What the open panel shows, written to files/ui-dump.json (text, content description, screen bounds of every
            // visible view), so the script never needs uiautomator (its dump unbinds the accessibility service).
            "dump_ui" -> dumpUi(s)
            "state" -> state(s, prefs)
            else -> error("unknown command $cmd")
        }
    }

    private fun dumpUi(s: QuickAccessService): JSONObject {
        val root = s.testPanelRoot() ?: error("panel not showing")
        val nodes = JSONArray()
        fun walk(v: android.view.View) {
            if (v.visibility != android.view.View.VISIBLE || v.width <= 0 || v.height <= 0) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val text = (v as? android.widget.TextView)?.text?.toString().orEmpty()
            val desc = v.contentDescription?.toString().orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty()) {
                nodes.put(JSONObject().put("text", text).put("desc", desc).put("clickable", v.isClickable)
                    .put("b", JSONArray(listOf(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height))))
            }
            (v as? android.view.ViewGroup)?.let { g -> for (i in 0 until g.childCount) walk(g.getChildAt(i)) }
        }
        walk(root)
        File(s.filesDir, UI_DUMP).writeText(nodes.toString())
        return JSONObject().put("nodes", nodes.length())
    }

    private fun pickClip(s: Context, i: Intent): ClipHistory.Clip {
        val all = ClipHistory.list(s)
        i.getStringExtra("clip")?.let { id -> return all.firstOrNull { it.id == id } ?: error("no clip $id") }
        return all.getOrNull(i.getStringExtra("n")?.toIntOrNull() ?: 0) ?: error("no such clip")
    }

    private fun drawTestPicture(to: File, label: String) {
        val bmp = Bitmap.createBitmap(400, 240, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; strokeWidth = 4f; style = Paint.Style.STROKE; textSize = 28f }
        c.drawRect(8f, 8f, 392f, 232f, p)
        c.drawLine(30f, 200f, 370f, 40f, p)
        p.style = Paint.Style.FILL
        c.drawText(label.take(24), 24f, 120f, p)
        to.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
    }

    private fun state(s: QuickAccessService, prefs: Prefs): JSONObject {
        val clips = ClipHistory.list(s)
        return JSONObject()
            .put("version", BuildConfig.VERSION_NAME)
            .put("clips", clips.size)
            .put("testClips", clips.count { it.source == ClipHistory.TEST_SOURCE })
            .put("recents", Recents.list(prefs).size)
            .put("testRecents", Recents.list(prefs).count { it.title.startsWith(RECENT_PREFIX) })
            .put("dock", JSONArray(prefs.dock.map { it.value }))
            .put("dockLayout", prefs.dockLayout)
            .put("previousInDock", prefs.previousAppInDock)
            .put("previousApp", s.previousApp ?: JSONObject.NULL)
            .put("foreground", s.foregroundPackage ?: JSONObject.NULL)
            .put("panelShowing", s.isPanelShowing)
    }

    // ---- Settings snapshot, in this process (force-stopping would switch the accessibility service off) ----

    private fun snapshot(s: Context): Int {
        val root = JSONObject()
        var n = 0
        for (name in PREF_FILES) {
            val o = JSONObject()
            for ((k, v) in s.getSharedPreferences(name, Context.MODE_PRIVATE).all) {
                val e = when (v) {
                    is Boolean -> JSONObject().put("t", "b").put("v", v)
                    is Int -> JSONObject().put("t", "i").put("v", v)
                    is Long -> JSONObject().put("t", "l").put("v", v)
                    is Float -> JSONObject().put("t", "f").put("v", v.toDouble())
                    is String -> JSONObject().put("t", "s").put("v", v)
                    is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                    else -> continue
                }
                o.put(k, e)
                n++
            }
            root.put(name, o)
        }
        File(s.filesDir, SNAPSHOT).writeText(root.toString())
        return n
    }

    private fun restore(s: Context): Int {
        val root = JSONObject(File(s.filesDir, SNAPSHOT).readText())
        var n = 0
        for (name in PREF_FILES) {
            val o = root.optJSONObject(name) ?: continue
            val edit = s.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
            for (k in o.keys()) {
                val e = o.getJSONObject(k)
                when (e.getString("t")) {
                    "b" -> edit.putBoolean(k, e.getBoolean("v"))
                    "i" -> edit.putInt(k, e.getInt("v"))
                    "l" -> edit.putLong(k, e.getLong("v"))
                    "f" -> edit.putFloat(k, e.getDouble("v").toFloat())
                    "s" -> edit.putString(k, e.getString("v"))
                    "set" -> e.getJSONArray("v").let { a -> edit.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
                    else -> continue
                }
                n++
            }
            edit.commit()
        }
        // Test pictures left over from clips that the restored list no longer has.
        ClipHistory.dir(s).listFiles { f -> f.name.startsWith("sbtest_") }.orEmpty().forEach { f ->
            if (ClipHistory.list(s).none { it.image == f.name }) f.delete()
        }
        return n
    }
}
