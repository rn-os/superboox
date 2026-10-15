package com.superboox.quickaccess

/**
 * First-use cues: a small card shown once, at the moment a feature is useful, for features that
 * have to be switched on. One "seen" set for all of them; Settings → Help → Show tips again clears it.
 */
object Cues {
    const val WRITING = "writing"

    fun seen(prefs: Prefs, id: String): Boolean = prefs.sp.getStringSet(Prefs.K_CUES_SEEN, emptySet())?.contains(id) == true

    fun markSeen(prefs: Prefs, id: String) {
        val now = prefs.sp.getStringSet(Prefs.K_CUES_SEEN, emptySet()).orEmpty()
        prefs.sp.edit().putStringSet(Prefs.K_CUES_SEEN, now + id).apply()
    }

    fun resetAll(prefs: Prefs) {
        prefs.sp.edit().remove(Prefs.K_CUES_SEEN).apply()
    }
}
