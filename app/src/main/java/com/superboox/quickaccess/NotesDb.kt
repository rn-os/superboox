package com.superboox.quickaccess

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Direct note opening on BOOX firmware that exposes its note database.
 *
 * Found via the debug report on a Note Air 6C (firmware 4.3):
 *  - `content://com.onyx.android.sdk.note.ContentProvider/NoteModel` is
 *    exported without a permission and lists every note's `uniqueId`,
 *    `title`, `parentUniqueId`, `pageCount` and `status`;
 *  - `OpenNoteByDocIdActivity` is exported and opens a note by that id.
 *
 * The activity takes the id in the `note_document_id` extra. It only opens
 * existing notes (an unknown id shows "note not found"), and [NoteOpener]
 * confirms the right note appeared, falling back to the library if not. Only titles and ids are read, never note contents.
 */
object NotesDb {
    private val NOTES_URI: Uri = Uri.parse("content://com.onyx.android.sdk.note.ContentProvider/NoteModel")
    private const val OPEN_BY_ID = "com.onyx.android.note.note.ui.OpenNoteByDocIdActivity"

    /**
     * The extra OpenNoteByDocIdActivity reads the id from (its helper
     * OpenNoteIntentsKt.noteDocIdFromIntent, found via the build 18 report).
     */
    private const val ID_EXTRA = "note_document_id"

    private class Row(val id: String, val title: String, val parent: String?, val pages: Int, val status: Int, val updated: Long)

    /** The id of the note at [path] ("Folder / Note" or just "Note"), or null if not found. */
    fun findNoteId(context: Context, path: List<String>): String? {
        val name = path.lastOrNull() ?: return null
        val rows = runCatching { query(context) }
            .onFailure { DebugLog.w("note", "note database not readable: ${it.javaClass.simpleName} ${it.message}") }
            .getOrNull() ?: return null
        var candidates = rows.filter { NotesScreen.matches(it.title, name) }
        if (candidates.size > 1 && path.size > 1) {
            // Disambiguate by the folder the note sits in.
            val parentName = path[path.size - 2]
            val byId = rows.associateBy { it.id }
            val inFolder = candidates.filter { r -> r.parent?.let { byId[it] }?.let { NotesScreen.matches(it.title, parentName) } == true }
            if (inFolder.isNotEmpty()) candidates = inFolder
        }
        // Prefer live notes with pages (folders have none) and the most recently edited.
        val best = candidates.sortedWith(
            compareBy<Row>({ if (it.status == 0) 0 else 1 }, { if (it.pages > 0) 0 else 1 }, { -it.updated }),
        ).firstOrNull()
        DebugLog.i("note", "database: ${rows.size} entries, ${candidates.size} named \"$name\"${best?.let { " -> using ${it.id.take(8)}…" } ?: ""}")
        return best?.id
    }

    /** The current name of the note with this id, or null if it's gone (deleted, or the database isn't readable). */
    fun titleForId(context: Context, id: String): String? {
        val rows = runCatching { query(context) }.getOrNull() ?: return null
        return rows.firstOrNull { it.id == id && it.status == 0 }?.title
    }

    fun openIntent(context: Context, notesPackage: String, id: String): Intent? {
        val cn = ComponentName(notesPackage, OPEN_BY_ID)
        val info = runCatching { context.packageManager.getActivityInfo(cn, 0) }.getOrNull() ?: return null
        if (!info.exported) return null
        return Intent(Intent.ACTION_VIEW).setComponent(cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
            putExtra(ID_EXTRA, id)
        }
    }

    private fun query(context: Context): List<Row> {
        val cols = arrayOf("uniqueId", "title", "parentUniqueId", "pageCount", "status", "updatedAt")
        val out = ArrayList<Row>()
        context.contentResolver.query(NOTES_URI, cols, null, null, null)?.use { c ->
            val iId = c.getColumnIndex("uniqueId")
            val iTitle = c.getColumnIndex("title")
            val iParent = c.getColumnIndex("parentUniqueId")
            val iPages = c.getColumnIndex("pageCount")
            val iStatus = c.getColumnIndex("status")
            val iUpdated = c.getColumnIndex("updatedAt")
            while (c.moveToNext()) {
                val id = c.getString(iId) ?: continue
                val title = c.getString(iTitle) ?: continue
                out += Row(
                    id = id,
                    title = title,
                    parent = if (iParent >= 0) c.getString(iParent) else null,
                    pages = if (iPages >= 0) c.getInt(iPages) else 0,
                    status = if (iStatus >= 0) c.getInt(iStatus) else 0,
                    updated = if (iUpdated >= 0) runCatching { c.getLong(iUpdated) }.getOrDefault(0L) else 0L,
                )
            }
        }
        return out
    }
}
