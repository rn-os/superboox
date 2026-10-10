package com.superboox.quickaccess

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.zip.ZipFile

/**
 * Investigates, from inside the app (no adb), whether BOOX Notes exposes
 * note ids that would let SuperBoox open a note directly through the exported
 * OpenNoteByDocIdActivity, skipping the trip through the BOOX home screen.
 *
 * Runs only when the user copies the debug report, off the main thread, and
 * is time-boxed so the report always completes. Records structure only
 * (column names, row counts, field names), never the contents of notes.
 */
object NoteIdProbe {

    private const val TOTAL_BUDGET_MS = 12_000L
    private const val QUERY_TIMEOUT_MS = 1_500L

    /** Table names commonly used by BOOX's note database (DBFlow-style providers). */
    private val CANDIDATE_TABLES = listOf(
        "", "NoteModel", "Note", "note", "notes", "NoteInfo", "Document", "document", "DocumentModel",
        "FolderModel", "folder", "NoteSyncModel", "note_sync", "OnyxNoteSync", "sync_note",
    )

    /** Lower-case fragments of field names that could carry a note id. */
    private val KEYWORDS = listOf("docid", "doc_id", "documentid", "document_id", "uniqueid", "unique_id", "noteid", "note_id", "opennote")

    fun run(context: Context, notesPackage: String): List<String> {
        val deadline = SystemClock.uptimeMillis() + TOTAL_BUDGET_MS
        val out = ArrayList<String>()
        runCatching { providers(context, notesPackage, deadline, out) }
            .onFailure { out += "providers: ${it.javaClass.simpleName} ${it.message}" }
        // dexKeys() (id-like names across the whole Notes app) already did its job in
        // build 14's report; the targeted activityStrings() below replaces it.
        runCatching { out += activityStrings(context, notesPackage) }
            .onFailure { out += "activity strings: ${it.javaClass.simpleName} ${it.message}" }
        return out
    }

    /**
     * Text constants used by the code of BOOX's note-opening screens: the
     * extra names they read and the actions they expect. Read from the APKs
     * on the device; nothing is executed.
     */
    private fun activityStrings(context: Context, notesPackage: String): List<String> {
        val targets = listOf(
            notesPackage to listOf(
                "Lcom/onyx/android/note/note/ui/OpenNote",
                "Lcom/onyx/android/note/note/ui/ScribbleActivity;",
                "Lcom/onyx/android/note/note/ui/CreateQuickNoteActivity",
            ),
            BooxNotes.BOOX_HOME_PACKAGE to listOf("Lcom/onyx/common/common/base/MiddlewareActivity"),
        )
        val out = ArrayList<String>()
        for ((pkg, prefixes) in targets) {
            val apk = runCatching { context.packageManager.getApplicationInfo(pkg, 0).sourceDir }.getOrNull()
            if (apk == null) {
                out += "code strings ($pkg): apk not found"
                continue
            }
            val found = DexProbe.classStrings(apk, prefixes, SystemClock.uptimeMillis() + 12_000)
            if (found.isEmpty()) out += "code strings ($pkg): no matching classes"
            found.filterValues { it.isNotEmpty() }.forEach { (cls, strings) ->
                out += "code strings $cls: ${strings.joinToString(" | ") { it.replace("\n", " ").take(80) }}"
            }
        }
        return out
    }

    private fun providers(context: Context, notesPackage: String, deadline: Long, out: MutableList<String>) {
        val pm = context.packageManager
        val canceller = Handler(Looper.getMainLooper())
        var answered = 0
        for (pkg in BooxNotes.packages(notesPackage).sorted()) {
            val providers = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS).providers }.getOrNull().orEmpty()
            for (p in providers.filter { it.exported && it.readPermission == null }) {
                for (auth in p.authority.split(';')) {
                    for (table in CANDIDATE_TABLES) {
                        if (SystemClock.uptimeMillis() > deadline) {
                            out += "provider queries stopped: time limit"
                            return
                        }
                        val uri = Uri.parse(if (table.isEmpty()) "content://$auth" else "content://$auth/$table")
                        // Cancel queries that hang (e.g. a provider process that won't start).
                        val cancel = CancellationSignal()
                        val timeout = Runnable { cancel.cancel() }
                        canceller.postDelayed(timeout, QUERY_TIMEOUT_MS)
                        val result = runCatching {
                            context.contentResolver.query(uri, null, null, null, null, cancel)?.use { c ->
                                "rows=${c.count} columns=${c.columnNames.take(40).joinToString(",")}"
                            } ?: "null cursor"
                        }.getOrElse { e -> "${e.javaClass.simpleName}: ${e.message?.take(100)}" }
                        canceller.removeCallbacks(timeout)
                        val ok = result.startsWith("rows=")
                        if (ok) answered++
                        // Keep the report short: every table that answered, plus the root of each provider.
                        if (ok || table.isEmpty()) out += "query $uri -> $result"
                    }
                }
            }
        }
        out += "provider tables that answered: $answered"
    }

    /**
     * Field names in the Notes app that look like note-id intent extras.
     * Streams the dex files and only looks around keyword hits, so it stays
     * fast and light even for a large app.
     */
    private fun dexKeys(context: Context, notesPackage: String, deadline: Long): String {
        val apk = runCatching { context.packageManager.getApplicationInfo(notesPackage, 0).sourceDir }.getOrNull()
            ?: return "notes apk: not found"
        val found = sortedSetOf<String>()
        var timedOut = false
        ZipFile(apk).use { zip ->
            val dexes = zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.toList()
            for (entry in dexes) {
                if (SystemClock.uptimeMillis() > deadline) { timedOut = true; break }
                zip.getInputStream(entry).use { input ->
                    val buf = ByteArray(1 shl 20)
                    var carry = ByteArray(0)
                    while (true) {
                        if (SystemClock.uptimeMillis() > deadline) { timedOut = true; break }
                        val n = input.read(buf)
                        if (n <= 0) break
                        // Keep a short tail from the previous chunk so names split across chunks aren't missed.
                        val chunk = carry + buf.copyOf(n)
                        scan(chunk, found)
                        carry = chunk.copyOfRange(maxOf(0, chunk.size - 64), chunk.size)
                        if (found.size >= 80) break
                    }
                }
                if (found.size >= 80) break
            }
            return "notes apk: ${dexes.size} dex files${if (timedOut) " (stopped: time limit)" else ""}; " +
                "id-like names (${found.size}): ${found.joinToString(", ")}"
        }
    }

    private fun scan(bytes: ByteArray, found: MutableSet<String>) {
        var i = 0
        while (i < bytes.size) {
            val c = lower(bytes[i])
            // Every keyword starts with d, u, n or o: skip everything else cheaply.
            if (c == 'd'.code || c == 'u'.code || c == 'n'.code || c == 'o'.code) {
                for (k in KEYWORDS) {
                    if (matchesAt(bytes, i, k)) {
                        var start = i
                        while (start > 0 && isNameByte(bytes[start - 1])) start--
                        var end = i + k.length
                        while (end < bytes.size && isNameByte(bytes[end])) end++
                        val len = end - start
                        if (len in 4..48) found += String(bytes, start, len, Charsets.US_ASCII)
                        i = end
                        break
                    }
                }
            }
            i++
        }
    }

    private fun matchesAt(bytes: ByteArray, at: Int, k: String): Boolean {
        if (at + k.length > bytes.size) return false
        for (j in k.indices) if (lower(bytes[at + j]) != k[j].code) return false
        return true
    }

    private fun lower(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v in 'A'.code..'Z'.code) v + 32 else v
    }

    private fun isNameByte(b: Byte): Boolean {
        val v = b.toInt() and 0xFF
        return v in 'a'.code..'z'.code || v in 'A'.code..'Z'.code || v in '0'.code..'9'.code || v == '_'.code || v == '.'.code
    }
}
