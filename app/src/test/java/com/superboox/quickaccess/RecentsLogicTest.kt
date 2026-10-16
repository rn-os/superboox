package com.superboox.quickaccess

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recents decisions that used to need a tablet session to see: what counts as a note, what counts as a reader. */
class RecentsLogicTest {
    @Test fun toolPopUpsAreNotNotes() {
        // Seen on the Note Air 6C: BOOX Notes' tool pop-up title was saved as a note.
        for (t in listOf("Pen 0.30mm", "pencil 2", "Fountain Pen 0.5mm", "Highlighter 12", "  Marker 3.0  "))
            assertTrue("tool: $t", Recents.isToolName(t))
    }

    @Test fun realNotesAreKept() {
        for (t in listOf("Pen", "Pen pals", "Pencil sketches", "Work Content Ideas", "Meeting 2026-10-15", "Marker ideas"))
            assertFalse("note: $t", Recents.isToolName(t))
    }

    @Test fun knownAndNamedReadersCount() {
        val notes = BooxNotes.DEFAULT_PACKAGE
        for (p in listOf("com.onyx.kreader", "org.koreader.launcher", "com.example.pdfviewer", "org.some.epubthing"))
            assertTrue("reader: $p", Recents.isReader(p, notes))
    }

    @Test fun notesLauncherAndHelpersAreNotReaders() {
        val notes = BooxNotes.DEFAULT_PACKAGE
        for (p in listOf(notes, BooxNotes.BOOX_HOME_PACKAGE, "com.example.reader.sync", "com.android.vending", "com.example.launcher", "com.android.settings"))
            assertFalse("not a reader: $p", Recents.isReader(p, notes))
    }

    @Test fun itemKeyIgnoresCaseAndPadding() {
        val a = RecentItem(RecentItem.Kind.NOTE, " Ideas ", "p", 1)
        val b = RecentItem(RecentItem.Kind.NOTE, "ideas", "p", 2)
        assertEquals(a.key, b.key)
        assertTrue(a.key != RecentItem.Kind.DOCUMENT.let { RecentItem(it, "ideas", "p", 1).key })
    }
}
