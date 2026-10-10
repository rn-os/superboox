package com.superboox.quickaccess

import android.content.Context
import android.content.Intent

/**
 * BOOX Notes integration.
 *
 * BOOX Notes keeps notes in a private database and has no documented deep link
 * for "open note X". We therefore offer three tiers:
 *  1. Launch the Notes app (always works).
 *  2. Open a note by name: relaunch Notes at its library and let the
 *     accessibility service find and tap the note (see [NoteOpener]).
 *  3. Exported notes (PDF) or any other file via SAF, and raw intent pins for
 *     firmware-specific deep links discovered with `adb shell dumpsys activity`.
 */
object BooxNotes {
    const val DEFAULT_PACKAGE = "com.onyx.android.note"

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

    fun isInstalled(context: Context, pkg: String): Boolean =
        context.packageManager.getLaunchIntentForPackage(pkg) != null

    /**
     * Intent that opens Notes. With [fresh] the existing task is cleared so the
     * app starts at its library instead of whatever note was open last.
     */
    fun launchIntent(context: Context, pkg: String, fresh: Boolean): Intent? {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return intent
    }
}
