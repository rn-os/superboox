package com.superboox.quickaccess

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast

/**
 * "Add to Clipboard" in the share sheet. BOOX's share sheet has no Copy, so a screenshot (or a
 * photo, or text) shared here is kept in the Clipboard and put on the clipboard. No window of its
 * own: it reads what was shared while the sharing app's permission lasts, then finishes.
 */
class ShareToClipboardActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = applicationContext
        val prefs = Prefs(this)
        val uris = sharedUris(intent)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val source = referrer?.host?.takeIf { ClipHistory.isAppSource(this, it) } ?: ClipHistory.appInFront(this)
        Thread {
            val kept = runCatching { ClipHistory.keepShared(app, prefs, uris, text, source) }
                .onFailure { DebugLog.w("clipboard", "share not kept: ${it.javaClass.simpleName} ${it.message}") }.getOrDefault(false)
            runOnUiThread {
                Toast.makeText(app, if (kept) "Copied to the clipboard" else "Couldn't copy that", Toast.LENGTH_SHORT).show()
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
            }
        }.start()
    }

    @Suppress("DEPRECATION")
    private fun sharedUris(intent: Intent): List<Uri> {
        val extra: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(
                if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_STREAM),
            )
            Intent.ACTION_SEND_MULTIPLE ->
                (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
            else -> emptyList()
        }
        if (extra.isNotEmpty()) return extra
        val clip = intent.clipData ?: return emptyList()
        return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
    }
}
