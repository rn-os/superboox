package com.superboox.quickaccess

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.StrictMode
import android.webkit.MimeTypeMap
import java.io.File

/**
 * Documents in NeoReader, BOOX's reading app.
 *
 * NeoReader keeps its library in an exported, permission-free database
 * (`content://com.onyx.content.database.ContentProvider/Metadata`, found via
 * the debug report): one row per document with its title, file path and when
 * it was last opened. The newest `lastAccess` is the document open right now,
 * which is how a document gets pinned and remembered. Only titles, paths and
 * times are read.
 */
object ReaderLibrary {
    const val NEOREADER = "com.onyx.kreader"
    private val METADATA: Uri = Uri.parse("content://com.onyx.content.database.ContentProvider/Metadata")

    data class Doc(val title: String, val path: String)

    /** The document most recently opened in NeoReader, or null. Call off the main thread. */
    fun lastOpened(context: Context): Doc? = runCatching {
        val cols = arrayOf("title", "name", "nativeAbsolutePath", "lastAccess")
        context.contentResolver.query(METADATA, cols, null, null, null)?.use { c ->
            val iTitle = c.getColumnIndex("title")
            val iName = c.getColumnIndex("name")
            val iPath = c.getColumnIndex("nativeAbsolutePath")
            val iAccess = c.getColumnIndex("lastAccess")
            var best: Doc? = null
            var bestTime = 0L
            while (c.moveToNext()) {
                val path = if (iPath >= 0) c.getString(iPath) else null
                if (path.isNullOrBlank()) continue
                val time = if (iAccess >= 0) runCatching { c.getLong(iAccess) }.getOrDefault(0L) else 0L
                if (time <= bestTime) continue
                val title = (if (iTitle >= 0) c.getString(iTitle) else null)?.takeIf { it.isNotBlank() }
                    ?: (if (iName >= 0) c.getString(iName) else null)?.takeIf { it.isNotBlank() }
                    ?: File(path).nameWithoutExtension
                best = Doc(title, path)
                bestTime = time
            }
            best
        }
    }.onFailure { DebugLog.w("reader", "NeoReader library not readable: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    fun isInstalled(context: Context) = runCatching { context.packageManager.getPackageInfo(NEOREADER, 0) }.isSuccess

    /** Opens the document at [path], in NeoReader when it's installed. */
    fun open(context: Context, path: String): Boolean {
        val file = File(path)
        val ext = file.extension.lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.fromFile(file), mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (isInstalled(context)) intent.setPackage(NEOREADER)
        // NeoReader reads files by path (it has storage access); Android only objects to
        // file:// links as a precaution, so allow them for this one launch.
        val policy = StrictMode.getVmPolicy()
        StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
        return try {
            Launcher.start(context, intent)
        } finally {
            StrictMode.setVmPolicy(policy)
        }
    }
}
