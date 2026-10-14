package com.superboox.quickaccess

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Builds the plain-text report behind "Copy debug report". */
object DebugReport {

    fun build(context: Context): String = buildString {
        val pm = context.packageManager
        val prefs = Prefs(context)
        // The app's, not Settings' own (which is scaled by UiScale).
        val dm = context.applicationContext.resources.displayMetrics

        line("SuperBoox debug report — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        section("App")
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        line("version: ${info?.versionName} (${info?.longVersionCode})")
        line("service running: ${QuickAccessService.instance != null}")
        line("service enabled in settings: ${serviceEnabled(context)}")
        line("after a restart: ${if (Prefs(context).restoreAfterRestart) "on" else "off"} (can switch the service: ${BootRestore.canSwitch(context)}, notifications: ${BootRestore.canNotify(context)})")
        line("panel showing: ${QuickAccessService.instance?.isPanelShowing}")
        line("foreground app (last seen): ${QuickAccessService.instance?.foregroundPackage}")

        section("Device")
        line("model: ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        line("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("firmware: ${Build.DISPLAY}")
        line("fingerprint: ${Build.FINGERPRINT}")
        line(Devices.describe())
        line("screen: ${dm.widthPixels}x${dm.heightPixels}px, density ${dm.density} (${dm.densityDpi}dpi)")
        line("settings scale: ${runCatching { UiScale.describe(context) }.getOrElse { "unknown" }}")

        section("BOOX apps (launchable)")
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo }
            .filter { it.packageName.contains("onyx", ignoreCase = true) || it.packageName.contains("boox", ignoreCase = true) }
            .sortedBy { it.packageName }
            .forEach { ai ->
                val v = runCatching { pm.getPackageInfo(ai.packageName, 0).versionName }.getOrNull()
                line("${ai.packageName}/${ai.name}  v$v")
            }
        val notesPkg = prefs.notesPackage
        line("notes package setting: $notesPkg (launcher entry: ${pm.getLaunchIntentForPackage(notesPkg) != null})")
        line("notes in front: ${QuickAccessService.instance?.isNotesInFront} (${QuickAccessService.instance?.foregroundActivity})")
        line("ways to open Notes: ${BooxNotes.launchCandidates(context, notesPkg).joinToString { it.first }.ifEmpty { "none" }}")
        line("new note screen available: ${BooxNotes.newNoteIntent(context, notesPkg) != null}")
        line("note-id access (for instant opening):")
        BooxNotes.describeNoteIdAccess(context, notesPkg).forEach { line("  $it") }
        NoteIdProbe.run(context, notesPkg).forEach { line("  $it") }
        line("NeoReader last opened: " + (ReaderLibrary.lastOpened(context)?.let { "\"${it.title}\" (.${it.path.substringAfterLast('.', "?")})" } ?: "unknown"))
        val recents = Recents.list(prefs)
        line("remembered recent items: ${recents.size}${recents.take(5).joinToString(prefix = " (", postfix = ")") { "${it.kind}:${it.title}" }}")
        line("note activities:")
        BooxNotes.describeActivities(context, notesPkg).forEach { line("  $it") }

        section("Control Center (Quick Controls test)")
        controlCenter().forEach { line(it) }

        section("Advanced access")
        line("Shizuku installed: ${AdvancedAccess.installed(context)} running: ${AdvancedAccess.running()} allowed: ${AdvancedAccess.allowed()}")
        line("light test: ${if (!prefs.lightTestRun) "not run" else if (prefs.lightTestPassed) "passed" else "failed"}; independent presets: ${prefs.independentPresets}; restarted since use: ${AdvancedAccess.restartedSinceUse(prefs)}")
        runCatching { AdvancedAccess.lightProbe() }.getOrElse { listOf("probe failed: ${it.message}") }.forEach { line(it) }

        section("Clipboard")
        line("button: ${prefs.clipShowButton} stillShows: qc=${prefs.clipShowControls} last=${prefs.clipShowLast} apps=${prefs.clipShowApps} keep: text=${prefs.clipKeepText} links=${prefs.clipKeepLinks} codes=${prefs.clipKeepCodes} handwriting=${prefs.clipKeepHandwriting} mode: ${prefs.clipCatchMode} limit: ${prefs.clipLimit} images: ${prefs.clipKeepImages} keepAfterRestart: ${prefs.clipKeepAfterRestart}")
        ClipHistory.list(context).let { clips ->
            // Counts only: what was copied stays private.
            line("clips: ${clips.size} (${clips.count { it.pinned }} pinned, ${clips.count { it.kind == ClipHistory.Kind.IMAGE }} images, ${clips.count { it.kind == ClipHistory.Kind.LINK }} links, ${clips.count { it.kind == ClipHistory.Kind.HANDWRITING }} handwriting)")
        }
        line("background read problem: ${ClipHistory.backgroundProblem() ?: "none"}")
        line("all files access: ${ClipHistory.hasFileAccess()}; advanced access: ${AdvancedAccess.allowed()}")

        section("Wi-Fi")
        runCatching { WifiProbe.lines(context) }.getOrElse { listOf("probe failed: ${it.message}") }.forEach { line(it) }

        section("Front light: system settings")
        lightSettings(context).forEach { line(it) }

        section("Settings")
        line("edge=${prefs.edge} trigger=${prefs.trigger} handleEnabled=${prefs.handleEnabled} showMarker=${prefs.showHandle}")
        line("strip width=${prefs.handleWidthDp}dp height=${prefs.handleHeightPct}% top=${prefs.handleTopPct}% panelWidth=${prefs.panelWidthPct}% systemRow=${prefs.showSystemActions}")
        line("recent folder: ${prefs.recentFolderUri ?: "none"} (count ${prefs.recentCount})")
        line("pins (${prefs.pins.size}):")
        prefs.pins.forEach { line("  ${it.type} \"${it.label}\" -> ${it.value}") }
        line("dock (${prefs.dock.size}): ${prefs.dock.joinToString { it.value }}")

        val log = DebugLog.entries()
        section("Event log (${log.size} entries, newest last)")
        log.forEach { line(it) }
    }.let { if (it.length > MAX_CHARS) "…(truncated)\n" + it.takeLast(MAX_CHARS) else it }

    /** Runs [ControlCenterProbe] through the service and waits for it (called off the main thread). */
    private fun controlCenter(): List<String> {
        val service = QuickAccessService.instance ?: return listOf("service not running")
        val result = java.util.concurrent.atomic.AtomicReference<List<String>>(listOf("timed out"))
        val latch = java.util.concurrent.CountDownLatch(1)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            service.probeControlCenter {
                result.set(it)
                latch.countDown()
            }
        }
        latch.await(8, java.util.concurrent.TimeUnit.SECONDS)
        return result.get()
    }

    /**
     * Compact outline of a window's view tree (class, id, text, flags). Logged
     * when opening a note fails or the note picker opens, so a report shows how
     * BOOX Notes lays out its library on this firmware.
     */
    fun describeTree(root: AccessibilityNodeInfo, maxNodes: Int = 150): String = buildString {
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= maxNodes) return
            count++
            append("  ".repeat(depth.coerceAtMost(12)))
            append(n.className?.toString()?.substringAfterLast('.') ?: "?")
            n.viewIdResourceName?.let { append(" #").append(it.substringAfter(":id/")) }
            val text = (n.text ?: n.contentDescription)?.toString()?.replace('\n', ' ')?.take(40)
            if (!text.isNullOrBlank()) append(" \"").append(text).append('"')
            if (n.isClickable) append(" [click]")
            if (n.isScrollable) append(" [scroll]")
            if (!n.isVisibleToUser) append(" [hidden]")
            append('\n')
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (count >= maxNodes) append("  …(more nodes not shown)\n")
    }

    /**
     * Android settings that look like the front light (brightness, warmth),
     * to see whether BOOX's light can be set without its Control Center.
     */
    private fun lightSettings(context: Context): List<String> {
        val out = ArrayList<String>()
        val pattern = Regex("bright|light|warm|cold|temp|\\bfl_|_fl\\b|frontlight|ctm", RegexOption.IGNORE_CASE)
        for (table in listOf("system", "secure", "global")) {
            runCatching {
                context.contentResolver.query(android.net.Uri.parse("content://settings/$table"), arrayOf("name", "value"), null, null, null)?.use { c ->
                    var n = 0
                    while (c.moveToNext()) {
                        val name = c.getString(0) ?: continue
                        n++
                        if (pattern.containsMatchIn(name)) out += "$table/$name = ${c.getString(1)}"
                    }
                    out += "$table: $n readable"
                } ?: run { out += "$table: no access" }
            }.onFailure { out += "$table: ${it.javaClass.simpleName} ${it.message?.take(80)}" }
        }
        out += "custom levels: SuperBoox last read ${QuickControls.savedLevels(Prefs(context)) ?: "none"}, your BOOX Custom ${QuickControls.booxCustom(Prefs(context)) ?: "unknown"}"
        return out
    }

    private fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(context.packageName + "/", ignoreCase = true)
    }

    private fun StringBuilder.section(title: String) = append("\n== ").append(title).append(" ==\n")

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    /** Keeps the report well under clipboard / share-intent size limits. */
    private const val MAX_CHARS = 200_000
}
