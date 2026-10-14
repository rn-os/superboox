package com.superboox.quickaccess

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * BOOX Notes integration.
 *
 * On current BOOX firmware (seen on a Note Air 6C, firmware 4.3) "Notes" is
 * two pieces:
 *  - the **library** is part of the BOOX launcher app: `com.onyx` /
 *    `com.onyx.common.note.ui.NoteActivity`;
 *  - an **open note** is `com.onyx.android.note` / `…note.ui.ScribbleActivity`,
 *    which has no launcher icon of its own.
 * Older firmware shipped Notes as one launchable `com.onyx.android.note` app;
 * both layouts are handled.
 *
 * There's no documented "open note X" intent, so pinned notes are opened by
 * name through the accessibility service (see [NoteOpener]). Exported PDFs and
 * custom intents remain available as pins too.
 */
object BooxNotes {
    const val DEFAULT_PACKAGE = "com.onyx.android.note"

    /** The BOOX launcher, which hosts the Notes library on newer firmware. */
    const val BOOX_HOME_PACKAGE = "com.onyx"

    /** Notes library screens, newest firmware first. Started directly when the system allows it. */
    val LIBRARY_COMPONENTS = listOf(
        ComponentName(BOOX_HOME_PACKAGE, "com.onyx.common.note.ui.NoteActivity"),
    )

    /** Seeded into the bottom dock on first run; packages that aren't installed are skipped. */
    val DEFAULT_DOCK_PACKAGES = listOf(
        DEFAULT_PACKAGE,
        "com.onyx.kreader", // NeoReader
        "com.onyx.calendar",
        "com.android.settings",
    )

    /** Separator between folder levels in a note path, e.g. "Work / Meetings / Standup". */
    const val PATH_SEPARATOR = "/"

    fun splitPath(path: String): List<String> =
        path.split(PATH_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    /** Packages whose windows can be part of BOOX Notes (library or editor). */
    fun packages(notesPackage: String): Set<String> = setOf(notesPackage, BOOX_HOME_PACKAGE)

    /**
     * True if this foreground window is BOOX Notes: the editor package, or the
     * Notes library hosted inside the BOOX launcher (not the rest of the launcher).
     */
    fun isNotesScreen(pkg: String?, activity: String?, notesPackage: String): Boolean {
        if (pkg == null) return false
        if (pkg == notesPackage) return true
        val cls = activity.orEmpty()
        return pkg == BOOX_HOME_PACKAGE && (cls.contains(".note.", ignoreCase = true) || cls.endsWith("NoteActivity"))
    }

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null ||
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /**
     * Ways to bring up Notes, best first: the Notes app's own launcher entry
     * (older firmware), the library activity inside the BOOX launcher, BOOX's
     * tab router asked for Notes, then the BOOX launcher itself (the opener then
     * taps its "Notes" tab or widget).
     */
    fun launchCandidates(context: Context, notesPackage: String): List<Pair<String, Intent>> {
        val pm = context.packageManager
        val out = ArrayList<Pair<String, Intent>>()
        pm.getLaunchIntentForPackage(notesPackage)?.let { out += "notes app" to it }
        for (cn in LIBRARY_COMPONENTS) {
            val info = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull() ?: continue
            if (info.exported) out += "notes library ${cn.shortClassName}" to Intent(Intent.ACTION_MAIN).setComponent(cn)
        }
        notesLibraryIntent(context)?.let { out += "BOOX tab router" to it }
        pm.getLaunchIntentForPackage(BOOX_HOME_PACKAGE)?.let { out += "BOOX launcher" to it }
        out.forEach { it.second.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        return out
    }

    /** BOOX's main screen tab router (exported on the Note Air 6C, firmware 4.3). */
    private val TAB_ROUTER = ComponentName(BOOX_HOME_PACKAGE, "com.onyx.tablet.main.ui.TabletMainActivity")
    private const val TAB_ROUTER_ACTION = "com.onyx.intent.action.MAIN_ACTIVITY"

    /**
     * Opens the Notes library straight away: BOOX's tab router passes OPEN_NOTE on to its
     * (unexported) NoteActivity, the same screen the home Notes widget opens. Null where the
     * firmware doesn't export the router.
     */
    fun notesLibraryIntent(context: Context): Intent? {
        val info = runCatching { context.packageManager.getActivityInfo(TAB_ROUTER, 0) }.getOrNull() ?: return null
        if (!info.exported || !info.enabled) return null
        return Intent(TAB_ROUTER_ACTION).setComponent(TAB_ROUTER)
            .putExtra("json", """{"action":"OPEN_NOTE"}""")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** BOOX's "new quick note" screen; other apps may open it on firmware 4.3. */
    private const val QUICK_NOTE_ACTIVITY = "com.onyx.android.note.note.ui.CreateQuickNoteActivity"

    /** Opens a new blank note, if this firmware lets other apps do that. */
    fun newNoteIntent(context: Context, notesPackage: String): Intent? {
        val cn = ComponentName(notesPackage, QUICK_NOTE_ACTIVITY)
        val info = runCatching { context.packageManager.getActivityInfo(cn, 0) }.getOrNull() ?: return null
        if (!info.exported) return null
        return Intent(Intent.ACTION_MAIN).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /**
     * What this device exposes that could open a note by its internal id
     * (OpenNoteByDocIdActivity) or reveal those ids (content providers). Shown
     * in the debug report so this can be investigated without adb.
     */
    fun describeNoteIdAccess(context: Context, notesPackage: String): List<String> {
        val pm = context.packageManager
        val out = ArrayList<String>()
        val byId = ComponentName(notesPackage, "com.onyx.android.note.note.ui.OpenNoteByDocIdActivity")
        val info = runCatching { pm.getActivityInfo(byId, PackageManager.GET_META_DATA) }.getOrNull()
        out += if (info == null) "OpenNoteByDocIdActivity: not found" else
            "OpenNoteByDocIdActivity: exported=${info.exported} permission=${info.permission} launchMode=${info.launchMode} meta=${info.metaData?.keySet()}"
        for (cn in listOf(
            ComponentName(BOOX_HOME_PACKAGE, "com.onyx.common.common.base.MiddlewareActivity"),
            ComponentName(notesPackage, "com.onyx.android.note.note.ui.ScribbleActivity"),
        )) {
            val a = runCatching { pm.getActivityInfo(cn, 0) }.getOrNull()
            out += if (a == null) "${cn.className.substringAfterLast('.')}: not found" else
                "${cn.className.substringAfterLast('.')}: exported=${a.exported} permission=${a.permission} launchMode=${a.launchMode}"
        }
        for (pkg in packages(notesPackage).sorted()) {
            val providers = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS).providers }.getOrNull().orEmpty()
            providers.forEach { p ->
                out += "provider $pkg: ${p.authority} exported=${p.exported} read=${p.readPermission} write=${p.writePermission} grantUri=${p.grantUriPermissions}"
            }
        }
        return out
    }

    /** Note-related activities on this device, for the debug report. */
    fun describeActivities(context: Context, notesPackage: String): List<String> {
        val pm = context.packageManager
        return packages(notesPackage).sorted().flatMap { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES) }.getOrNull()
                ?: return@flatMap listOf("$pkg: not visible / not installed")
            info.activities.orEmpty()
                .filter { pkg == notesPackage || it.name.contains("note", ignoreCase = true) || it.name.endsWith("StartupActivity") }
                .map { "${it.name}${if (it.exported) " (exported)" else ""}" }
                .ifEmpty { listOf("$pkg: no matching activities") }
        }
    }
}
