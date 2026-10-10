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
        val dm = context.resources.displayMetrics

        line("SuperBoox debug report — ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        section("App")
        val info = runCatching { pm.getPackageInfo(context.packageName, 0) }.getOrNull()
        line("version: ${info?.versionName} (${info?.longVersionCode})")
        line("service running: ${QuickAccessService.instance != null}")
        line("service enabled in settings: ${serviceEnabled(context)}")
        line("panel showing: ${QuickAccessService.instance?.isPanelShowing}")
        line("foreground app (last seen): ${QuickAccessService.instance?.foregroundPackage}")

        section("Device")
        line("model: ${Build.MANUFACTURER} ${Build.MODEL} (device=${Build.DEVICE}, product=${Build.PRODUCT})")
        line("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        line("firmware: ${Build.DISPLAY}")
        line("fingerprint: ${Build.FINGERPRINT}")
        line("screen: ${dm.widthPixels}x${dm.heightPixels}px, density ${dm.density} (${dm.densityDpi}dpi)")

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
        val recents = Recents.list(prefs)
        line("remembered recent items: ${recents.size}${recents.take(5).joinToString(prefix = " (", postfix = ")") { "${it.kind}:${it.title}" }}")
        line("note activities:")
        BooxNotes.describeActivities(context, notesPkg).forEach { line("  $it") }

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

    private fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.contains(context.packageName + "/", ignoreCase = true)
    }

    private fun StringBuilder.section(title: String) = append("\n== ").append(title).append(" ==\n")

    private fun StringBuilder.line(s: String) = append(s).append('\n')

    /** Keeps the report well under clipboard / share-intent size limits. */
    private const val MAX_CHARS = 200_000
}
