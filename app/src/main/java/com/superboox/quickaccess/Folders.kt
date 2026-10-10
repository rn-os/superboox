package com.superboox.quickaccess

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/** Lists files under a persisted SAF tree, newest first. Call off the main thread. */
object Folders {

    data class Entry(val name: String, val uri: Uri, val mime: String, val modified: Long)

    fun newestFiles(context: Context, tree: Uri, limit: Int, maxDepth: Int = 2): List<Entry> {
        val out = ArrayList<Entry>()
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return emptyList()
        collect(context, tree, rootId, 0, maxDepth, out)
        return out.sortedByDescending { it.modified }.take(limit)
    }

    private fun collect(context: Context, tree: Uri, docId: String, depth: Int, maxDepth: Int, out: MutableList<Entry>) {
        if (out.size >= MAX_SCAN) return
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
        )
        val subdirs = ArrayList<String>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < MAX_SCAN) {
                    val id = c.getString(0)
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: "application/octet-stream"
                    if (name.startsWith(".")) continue
                    if (mime == Document.MIME_TYPE_DIR) {
                        subdirs += id
                    } else {
                        out += Entry(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), mime, c.getLong(3))
                    }
                }
            }
        }
        if (depth < maxDepth) subdirs.forEach { collect(context, tree, it, depth + 1, maxDepth, out) }
    }

    private const val MAX_SCAN = 400
}
