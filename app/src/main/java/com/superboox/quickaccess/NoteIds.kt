package com.superboox.quickaccess

import android.content.Context
import java.util.concurrent.Executors

/**
 * Keeps each pinned BOOX note tied to its id in the Notes database, so a pin
 * keeps working after the note is renamed and never confuses two notes with
 * the same name. Ids are looked up when a note is pinned (and, for pins made
 * by older builds, the next time the pins change or one is opened).
 */
object NoteIds {
    private val io = Executors.newSingleThreadExecutor()

    /** Saves the id of every pinned note that doesn't have one yet, and turns pinned Notes folders into folder pins. */
    fun capture(context: Context, prefs: Prefs) {
        io.execute {
            upgradeFolders(context, prefs)
            val found = prefs.pins
                .filter { it.type == PinType.BOOX_NOTE && it.noteId.isEmpty() }
                .mapNotNull { pin -> NotesDb.findNoteId(context, BooxNotes.splitPath(pin.value))?.let { pin.id to it } }
                .toMap()
            if (found.isEmpty()) return@execute
            // Re-read so pins changed meanwhile aren't lost.
            prefs.pins = prefs.pins.map { p -> found[p.id]?.takeIf { p.noteId.isEmpty() }?.let { p.copy(noteId = it) } ?: p }
            DebugLog.i("pin", "saved note ids for ${found.size} pin(s)")
        }
    }

    /**
     * A folder pinned as a plain note (by earlier builds, or when the library card didn't read as
     * a folder) becomes a Notes folder pin, so it expands in the panel.
     */
    private fun upgradeFolders(context: Context, prefs: Prefs) {
        val folderIds = prefs.pins.filter { it.type == PinType.BOOX_NOTE }.mapNotNull { pin ->
            val id = if (pin.noteId.isNotEmpty() && NotesDb.isFolderId(context, pin.noteId)) pin.noteId
            else if (pin.noteId.isEmpty()) NotesDb.findFolderId(context, BooxNotes.splitPath(pin.value)) else null
            id?.let { pin.id to it }
        }.toMap()
        if (folderIds.isEmpty()) return
        prefs.pins = prefs.pins.map { p -> folderIds[p.id]?.let { p.copy(type = PinType.NOTE_FOLDER, noteId = it) } ?: p }
        DebugLog.i("pin", "turned ${folderIds.size} pinned folder(s) into Notes folder pins")
    }

    /**
     * The path to open for [pin], following a rename made in Notes since it
     * was pinned (the pin is updated too). Calls back on the io thread.
     */
    fun resolve(context: Context, prefs: Prefs, pin: Pin, done: (path: String, noteId: String?) -> Unit) {
        io.execute {
            val path = runCatching { currentPath(context, prefs, pin) }.getOrDefault(pin.value)
            done(withFolders(context, pin, path), pin.noteId.ifEmpty { null })
        }
    }

    private fun currentPath(context: Context, prefs: Prefs, pin: Pin): String {
        val steps = BooxNotes.splitPath(pin.value)
        val oldName = steps.lastOrNull() ?: return pin.value
        if (pin.noteId.isEmpty()) {
            // Pinned by an older build: remember the id now.
            NotesDb.findNoteId(context, steps)?.let { id -> update(prefs, pin.id) { it.copy(noteId = id) } }
            return pin.value
        }
        val title = NotesDb.titleForId(context, pin.noteId) ?: return pin.value
        if (NotesScreen.matches(title, oldName)) return pin.value
        val path = (steps.dropLast(1) + title).joinToString(" / ")
        DebugLog.i("pin", "note \"$oldName\" was renamed to \"$title\"; updating the pin")
        update(prefs, pin.id) { it.copy(value = path, label = if (it.label == oldName) title else it.label) }
        return path
    }

    /**
     * A note pinned by name alone that sits in a folder: walk the library through its folders
     * (from BOOX's database), for firmware that can't open a note by id (Note Air 2, 2022).
     */
    private fun withFolders(context: Context, pin: Pin, path: String): String {
        val steps = BooxNotes.splitPath(path)
        if (steps.size != 1) return path
        val id = pin.noteId.ifEmpty { NotesDb.findNoteId(context, steps) } ?: return path
        val folders = runCatching { NotesDb.folderPath(context, id) }.getOrDefault(emptyList())
        if (folders.isEmpty()) return path
        DebugLog.i("pin", "\"${steps[0]}\" is in ${folders.joinToString(" / ")}")
        return (folders + steps).joinToString(" ${BooxNotes.PATH_SEPARATOR} ")
    }

    private fun update(prefs: Prefs, pinId: String, change: (Pin) -> Pin) {
        prefs.pins = prefs.pins.map { if (it.id == pinId) change(it) else it }
    }
}
