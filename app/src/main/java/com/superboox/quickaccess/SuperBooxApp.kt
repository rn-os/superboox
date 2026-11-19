package com.superboox.quickaccess

import android.app.Application

class SuperBooxApp : Application() {
    private val appearanceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null || key == Ink.APPEARANCE_KEY) Ink.readAppearance(prefs)
    }

    override fun onCreate() {
        super.onCreate()
        Prefs(this).sp.apply {
            Ink.readAppearance(this)
            registerOnSharedPreferenceChangeListener(appearanceListener)
        }
        DebugLog.init(this)
        // Errors in Settings or the panel are recovered from instead of stopping the service.
        MainGuard.install()
        // "Save to Digest" in other apps' menus follows its setting (also after a restore).
        runCatching { SaveToDigestActivity.sync(this) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Recorded before the process dies so the next report shows the crash.
            DebugLog.e("crash", "Uncaught exception on ${thread.name}", error)
            runCatching { Prefs(this).sp.edit().putLong(Prefs.K_LAST_CRASH, System.currentTimeMillis()).commit() }
            previous?.uncaughtException(thread, error)
        }
    }
}
