package com.superboox.quickaccess

import android.app.Application

class SuperBooxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Recorded before the process dies so the next report shows the crash.
            DebugLog.e("crash", "Uncaught exception on ${thread.name}", error)
            runCatching { Prefs(this).sp.edit().putLong(Prefs.K_LAST_CRASH, System.currentTimeMillis()).commit() }
            previous?.uncaughtException(thread, error)
        }
    }
}
