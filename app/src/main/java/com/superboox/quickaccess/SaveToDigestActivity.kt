package com.superboox.quickaccess

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast

/**
 * "Save to Digest": in the menu that pops up when text is selected in most apps (Android's
 * PROCESS_TEXT), and in the share sheet. Saves the passage to Digests with the app it came from,
 * then finishes. No window of its own.
 */
class SaveToDigestActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = when (intent?.action) {
            Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)
            else -> null
        }?.toString().orEmpty()
        // The selection menu starts us for a result, so the caller is known; shares say who sent them.
        val source = callingPackage?.takeIf { it != packageName }
            ?: referrer?.host?.takeIf { it != packageName && ClipHistory.isAppSource(this, it) }
            ?: QuickAccessService.instance?.foregroundPackage
        val title = intent?.getStringExtra(Intent.EXTRA_SUBJECT)
        val ok = runCatching { Digests.saveText(this, text, source, title) }
            .onFailure { DebugLog.w("digests", "save failed: ${it.javaClass.simpleName} ${it.message}") }.getOrDefault(false)
        val from = source?.let { runCatching { Launcher.appLabel(this, it) }.getOrNull() }
        Toast.makeText(applicationContext, if (ok) "Saved to Digests" + (from?.let { " · $it" } ?: "") else "Nothing to save", Toast.LENGTH_SHORT).show()
        // Leave the selected text as it was.
        setResult(RESULT_CANCELED)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        /** [setEnabled] only when it differs, so app start stays cheap. */
        fun sync(context: Context, on: Boolean) {
            val now = context.packageManager.getComponentEnabledSetting(ComponentName(context, SaveToDigestActivity::class.java))
            val enabled = now != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (enabled != on) setEnabled(context, on)
        }

        /** Shows or hides "Save to Digest" in other apps' menus (Settings → Digests). */
        fun setEnabled(context: Context, on: Boolean) {
            runCatching {
                context.packageManager.setComponentEnabledSetting(
                    ComponentName(context, SaveToDigestActivity::class.java),
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { DebugLog.w("digests", "couldn't switch Save to Digest: ${it.message}") }
        }
    }
}
