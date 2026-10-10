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

    /** Saves the id of every pinned note that doesn't have one yet. */
    fun capture(context: Context, prefs: Prefs) {
        io.execute {
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
     * The path to open for [pin], following a rename made in Notes since it
     * was pinned (the pin is updated too). Calls back on the io thread.
     */
    fun resolve(context: Context, prefs: Prefs, pin: Pin, done: (path: String, noteId: String?) -> Unit) {
        io.execute {
            val path = runCatching { currentPath(context, prefs, pin) }.getOrDefault(pin.value)
            done(path, pin.noteId.ifEmpty { null })
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

    private fun update(prefs: Prefs, pinId: String, change: (Pin) -> Pin) {
        prefs.pins = prefs.pins.map { if (it.id == pinId) change(it) else it }
    }
}
