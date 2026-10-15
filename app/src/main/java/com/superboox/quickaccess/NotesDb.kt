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

    /**
     * Indexes of the [names] that exactly one live note or folder has, so finding that name
     * on screen means finding it. Empty if the database isn't readable.
     */
    fun uniqueNames(context: Context, names: List<String>): Set<Int> {
        val live = runCatching { query(context) }.getOrNull()?.filter { it.status == 0 } ?: return emptySet()
        return names.indices.filter { i -> live.count { NotesScreen.matches(it.title, names[i]) } == 1 }.toSet()
    }

    /** The current name of the note with this id, or null if it's gone (deleted, or the database isn't readable). */
    fun titleForId(context: Context, id: String): String? {
        val rows = runCatching { query(context) }.getOrNull() ?: return null
        return rows.firstOrNull { it.id == id && it.status == 0 }?.title
    }

    /**
     * The folders a note sits in, outermost first ("Work", "Projects"), or empty at the top
     * level. Lets the library walk find a note pinned by name alone inside a folder.
     */
    fun folderPath(context: Context, id: String): List<String> {
        val rows = runCatching { query(context) }.getOrNull() ?: return emptyList()
        val byId = rows.associateBy { it.id }
        val out = ArrayList<String>()
        var parent = byId[id]?.parent
        while (!parent.isNullOrEmpty() && out.size < 12) {
            val folder = byId[parent] ?: break
            out.add(0, folder.title)
            parent = folder.parent
        }
        return out
    }

    @Volatile private var titleCache: Pair<Long, Set<String>>? = null

    /**
     * The first of [shown] that is the name of a live note in BOOX's database, or null. For
     * reading an open note's name where the editor has no note_name view: matching a real note
     * keeps tool labels ("Pen 0.30mm") from being taken as names. Cached briefly.
     */
    fun matchTitle(context: Context, shown: List<String>): String? {
        if (shown.isEmpty()) return null
        val now = android.os.SystemClock.uptimeMillis()
        val titles = titleCache?.takeIf { now - it.first < 30_000 }?.second
            ?: (runCatching { query(context) }.getOrNull() ?: return null)
                .filter { it.status == 0 }
                .map { it.title }
                .toSet()
                .also { titleCache = now to it }
        for (s in shown) titles.firstOrNull { NotesScreen.matches(it, s) }?.let { return it }
        return null
    }

    /**
     * The live note edited most recently, as a path ("Folder / Note"), or null. Folders have no
     * pages, so they're skipped. Call off the main thread.
     */
    fun lastEdited(context: Context): String? {
        val rows = runCatching { query(context) }.getOrNull() ?: return null
        val best = rows.filter { it.status == 0 && it.pages > 0 && it.updated > 0 }.maxByOrNull { it.updated } ?: return null
        val byId = rows.associateBy { it.id }
        val path = ArrayList<String>().apply { add(best.title) }
        var parent = best.parent
        while (!parent.isNullOrEmpty() && path.size < 12) {
            val folder = byId[parent] ?: break
            path.add(0, folder.title)
            parent = folder.parent
        }
        return path.joinToString(" ${BooxNotes.PATH_SEPARATOR} ")
    }

    /**
     * [title]'s place in the library as "Folder / Note", from BOOX's database: the note with
     * that name edited most recently (the one just opened). Just [title] at the top level or if
     * unreadable. No status filter: live notes are status 1 on the Note Air 6C (firmware 4.3).
     */
    fun pathOf(context: Context, title: String): String {
        if (BooxNotes.splitPath(title).size > 1) return title
        val rows = runCatching { query(context) }.getOrNull() ?: return title
        val best = rows.filter { it.pages > 0 && NotesScreen.matches(it.title, title) }.maxByOrNull { it.updated } ?: return title
        val byId = rows.associateBy { it.id }
        val path = ArrayList<String>().apply { add(title) }
        var parent = best.parent
        while (!parent.isNullOrEmpty() && path.size < 12) {
            val folder = byId[parent] ?: break
            path.add(0, folder.title)
            parent = folder.parent
        }
        return path.joinToString(" ${BooxNotes.PATH_SEPARATOR} ")
    }

    /** One entry inside a Notes folder: a note or a subfolder (folders have no pages). */
    class Entry(val id: String, val title: String, val isFolder: Boolean, val updated: Long = 0L)

    /**
     * What sits in the folder with this [folderId], most recently changed first (as BOOX Notes lists them); null
     * if the database isn't readable or the folder is gone. Call off the main thread. Which
     * `status` means "alive" differs between firmware (0 elsewhere, 1 on the Note Air 6C), so
     * the status of the most recently edited note counts as alive.
     */
    fun folderChildren(context: Context, folderId: String): List<Entry>? {
        val rows = runCatching { query(context) }.getOrNull() ?: return null
        if (rows.none { it.id == folderId }) return null
        val alive = rows.filter { it.pages > 0 && it.updated > 0 }.maxByOrNull { it.updated }?.status
        return rows.filter { it.parent == folderId && (alive == null || it.status == alive) }
            .map { Entry(it.id, it.title, isFolderRow(rows, it), it.updated) }
            // Like BOOX Notes' own list: most recently changed first.
            .sortedWith(compareByDescending<Entry> { it.updated }.thenBy { it.title.lowercase() })
    }

    /**
     * The id of the folder at [path] (just its name, or "Parent / Folder"), or null if that name
     * isn't a folder. A folder is a row with no pages or with entries inside it, so this doesn't
     * depend on how a firmware fills `pageCount`. Several with the name: the one holding most
     * entries. Off the main thread.
     */
    fun findFolderId(context: Context, path: List<String>): String? {
        val name = path.lastOrNull() ?: return null
        val rows = runCatching { query(context) }.getOrNull() ?: return null
        val byId = rows.associateBy { it.id }
        var folders = rows.filter { NotesScreen.matches(it.title, name) && isFolderRow(rows, it) }
        if (folders.size > 1 && path.size > 1) {
            val parentName = path[path.size - 2]
            val inParent = folders.filter { f -> f.parent?.let { byId[it] }?.let { NotesScreen.matches(it.title, parentName) } == true }
            if (inParent.isNotEmpty()) folders = inParent
        }
        val best = folders.maxByOrNull { f -> rows.count { it.parent == f.id } }
        DebugLog.i("note", "folder lookup \"$name\": ${rows.count { NotesScreen.matches(it.title, name) }} rows named so, ${folders.size} folder(s)" +
            (best?.let { " -> ${it.id.take(8)}… (pages ${it.pages}, status ${it.status})" } ?: ""))
        return best?.id
    }

    fun findFolderId(context: Context, name: String): String? = findFolderId(context, listOf(name))

    /** True if [id] is a folder in the database (no pages, or something inside it). Off the main thread. */
    fun isFolderId(context: Context, id: String): Boolean {
        val rows = runCatching { query(context) }.getOrNull() ?: return false
        return rows.firstOrNull { it.id == id }?.let { isFolderRow(rows, it) } == true
    }

    private fun isFolderRow(rows: List<Row>, r: Row): Boolean = r.pages == 0 || rows.any { it.parent == r.id }

    /** The path to the folder from the top, outermost first, ending with its own name. Empty if gone. */
    fun folderPathWithSelf(context: Context, folderId: String): List<String> {
        val rows = runCatching { query(context) }.getOrNull() ?: return emptyList()
        val self = rows.firstOrNull { it.id == folderId } ?: return emptyList()
        return folderPath(context, folderId) + self.title
    }

    /** Forget cached titles (a note was just created or renamed). */
    fun forgetTitles() {
        titleCache = null
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
