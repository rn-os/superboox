package com.superboox.quickaccess

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible trampoline that opens the panel and finishes immediately.
 *
 * Exposed as its own launcher entry ("Quick Access") so it can be bound to
 * anything that can launch an app: BOOX system gestures, the Navigation Ball,
 * a home-screen icon, or `adb shell am start -a com.superboox.quickaccess.OPEN_PANEL`.
 */
class OpenPanelActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = QuickAccessService.instance
        if (service != null) {
            service.showPanel()
        } else {
            Toast.makeText(this, "Turn on the SuperBoox accessibility service first", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
