package com.superboox.quickaccess

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import java.util.zip.ZipFile

/**
 * Investigates, from inside the app (no adb), whether BOOX Notes exposes
 * note ids that would let SuperBoox open a note directly through the exported
 * OpenNoteByDocIdActivity, skipping the trip through the BOOX home screen.
 *
 * Runs only when the user copies the debug report, off the main thread.
 * Records structure only (column names, row counts, field names), never the
 * contents of anyone's notes.
 */
object NoteIdProbe {

    /** Table names commonly used by BOOX's note database (DBFlow-style providers). */
    private val CANDIDATE_TABLES = listOf(
        "", "NoteModel", "Note", "note", "notes", "NoteInfo", "Document", "document", "DocumentModel",
        "FolderModel", "folder", "NoteSyncModel", "note_sync", "OnyxNoteSync", "sync_note",
    )

    fun run(context: Context, notesPackage: String): List<String> =
        runCatching { providers(context, notesPackage) + dexKeys(context, notesPackage) }
            .getOrElse { listOf("probe failed: ${it.javaClass.simpleName} ${it.message}") }

    private fun providers(context: Context, notesPackage: String): List<String> {
        val out = ArrayList<String>()
        val pm = context.packageManager
        for (pkg in BooxNotes.packages(notesPackage).sorted()) {
            val providers = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS).providers }.getOrNull().orEmpty()
            for (p in providers.filter { it.exported && it.readPermission == null }) {
                for (auth in p.authority.split(';')) {
                    for (table in CANDIDATE_TABLES) {
                        val uri = Uri.parse(if (table.isEmpty()) "content://$auth" else "content://$auth/$table")
                        val result = runCatching {
                            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                                "rows=${c.count} columns=${c.columnNames.take(40).joinToString(",")}"
                            } ?: "null cursor"
                        }.getOrElse { e -> "${e.javaClass.simpleName}: ${e.message?.take(120)}" }
                        // Only report tables that answered, plus one line per provider for the root.
                        if (table.isEmpty() || !result.startsWith("IllegalArgument") && !result.startsWith("null cursor")) {
                            out += "query $uri -> $result"
                        }
                    }
                }
            }
        }
        return out.ifEmpty { listOf("no readable providers") }
    }

    /** Field names in the Notes app that look like note-id intent extras. */
    private fun dexKeys(context: Context, notesPackage: String): List<String> {
        val apk = runCatching { context.packageManager.getApplicationInfo(notesPackage, 0).sourceDir }.getOrNull()
            ?: return listOf("notes apk: not found")
        val found = sortedSetOf<String>()
        val pattern = Regex("""[A-Za-z0-9_.]{4,60}""")
        val interesting = listOf("docid", "doc_id", "documentid", "document_id", "uniqueid", "unique_id", "noteid", "note_id", "opennote")
        runCatching {
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { entry ->
                    val text = zip.getInputStream(entry).use { String(it.readBytes(), Charsets.ISO_8859_1) }
                    for (m in pattern.findAll(text)) {
                        val v = m.value
                        val l = v.lowercase()
                        if (interesting.any { it in l } && !v.contains('/') && v.length <= 48) found += v
                        if (found.size >= 80) break
                    }
                }
            }
        }.onFailure { return listOf("notes apk ($apk): ${it.javaClass.simpleName} ${it.message}") }
        return listOf("notes apk id-like strings (${found.size}): ${found.joinToString(", ")}")
    }
}
