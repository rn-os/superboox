package com.superboox.quickaccess

import org.json.JSONArray

/**
 * First-use cues: a feature the user has to switch on is introduced once, in the place it
 * works (see [WritingPill] for the first). One store for all of them, so a new cue gets the
 * same "seen" memory and the same "show tips again" reset in Settings.
 */
object FirstUse {
    const val WRITING_PILL = "writing_pill"

    private fun seenSet(prefs: Prefs): Set<String> {
        val raw = prefs.sp.getString(Prefs.K_CUES_SEEN, null) ?: return emptySet()
        return runCatching { JSONArray(raw).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    }

    fun seen(prefs: Prefs, id: String): Boolean = id in seenSet(prefs)

    fun markSeen(prefs: Prefs, id: String) {
        if (seen(prefs, id)) return
        prefs.sp.edit().putString(Prefs.K_CUES_SEEN, JSONArray((seenSet(prefs) + id).toList()).toString()).apply()
    }

    /** Counts a showing that went unanswered; returns how many there have been. */
    fun bump(prefs: Prefs, id: String): Int {
        val key = "cue_shown_$id"
        val n = prefs.sp.getInt(key, 0) + 1
        prefs.sp.edit().putInt(key, n).apply()
        return n
    }

    /** Settings → "Show first-time tips again". */
    fun resetAll(prefs: Prefs) {
        val edit = prefs.sp.edit().remove(Prefs.K_CUES_SEEN)
        prefs.sp.all.keys.filter { it.startsWith("cue_shown_") }.forEach { edit.remove(it) }
        edit.apply()
    }
}
