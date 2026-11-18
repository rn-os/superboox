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
        private const val SHARE_TO_DIGEST = "com.superboox.quickaccess.ShareToDigest"
        private const val SHARE_TEXT_TO_CLIPBOARD = "com.superboox.quickaccess.ShareTextToClipboard"

        /**
         * Which SuperBoox entries other apps offer for text, from Settings → Digests: Save to Digest
         * in the selection menu, and one or both entries in the share sheet. Only switched when it
         * differs, so app start stays cheap.
         */
        fun sync(context: Context) {
            val prefs = Prefs(context)
            val share = prefs.digestShareText
            set(context, ComponentName(context, SaveToDigestActivity::class.java), prefs.digestSave)
            set(context, ComponentName(context.packageName, SHARE_TO_DIGEST), prefs.digestSave && share != Prefs.SHARE_TEXT_CLIPBOARD)
            set(context, ComponentName(context.packageName, SHARE_TEXT_TO_CLIPBOARD), !prefs.digestSave || share != Prefs.SHARE_TEXT_DIGEST)
        }

        private fun set(context: Context, cn: ComponentName, on: Boolean) {
            runCatching {
                val pm = context.packageManager
                val enabled = pm.getComponentEnabledSetting(cn) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                if (enabled == on) return
                pm.setComponentEnabledSetting(
                    cn,
                    if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }.onFailure { DebugLog.w("digests", "couldn't switch ${cn.shortClassName}: ${it.message}") }
        }
    }
}
