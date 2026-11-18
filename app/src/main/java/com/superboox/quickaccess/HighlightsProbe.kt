package com.superboox.quickaccess

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import java.io.File

/**
 * "Highlights" section of the debug report: where reading apps keep their highlights on this
 * tablet, for the planned Digests feature. **Counts and structure only**: never highlighted
 * text, notes or book names.
 *
 *  - **KOReader** keeps each book's highlights in a `.sdr` folder (next to the book, or under
 *    koreader/docsettings or koreader/hashdocsettings), in `metadata.<ext>.lua`. Newer versions
 *    list them under `["annotations"]`, older ones under `["highlight"]`.
 *  - **NeoReader**'s library database is already readable (see [ReaderLibrary]); this checks
 *    which other tables it answers for and their column names.
 *  - **Instapaper** keeps its data private; only whether it's installed is shown.
 */
object HighlightsProbe {

    private val KOREADER = listOf("org.koreader.launcher", "org.koreader.launcher.fdroid")
    private const val INSTAPAPER = "com.instapaper.android"
    private const val NEOREADER_DB = "content://com.onyx.content.database.ContentProvider/"

    /** Tables NeoReader's database might keep highlights in (only Metadata is confirmed). */
    private val NEOREADER_TABLES = listOf(
        "Metadata", "Annotation", "AnnotationModel", "Bookmark", "BookmarkModel",
        "Highlight", "DocumentInfo", "ReaderNote", "Library",
    )

    private const val SCAN_MS = 6_000L
    private const val MAX_DIRS = 20_000

    fun lines(context: Context): List<String> {
        val out = ArrayList<String>()
        val pm = context.packageManager
        fun version(pkg: String) = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull()

        out += "all files access: ${ClipHistory.hasFileAccess()}"
        val ko = KOREADER.firstNotNullOfOrNull { p -> version(p)?.let { p to it } }
        out += "KOReader: " + (ko?.let { "${it.first} v${it.second}" } ?: "not installed")
        out += "Instapaper: " + (version(INSTAPAPER)?.let { "installed v$it (its data is private to it)" } ?: "not installed")
        out += "NeoReader: " + (version(ReaderLibrary.NEOREADER)?.let { "v$it" } ?: "not installed")

        if (ko != null) runCatching { koreader(out) }.onFailure { out += "KOReader probe failed: ${it.javaClass.simpleName} ${it.message}" }
        runCatching { neoReader(context, out) }.onFailure { out += "NeoReader probe failed: ${it.javaClass.simpleName} ${it.message}" }
        return out
    }

    // ---- KOReader ------------------------------------------------------------------------

    private fun koreader(out: MutableList<String>) {
        if (!ClipHistory.hasFileAccess()) {
            out += "KOReader highlights: need All files access to look"
            return
        }
        val storage = Environment.getExternalStorageDirectory()
        val home = File(storage, "koreader")
        out += "KOReader folder: ${if (home.isDirectory) "found" else "not found"} at /sdcard/koreader"
        // Where KOReader keeps per-book files: "doc" = next to the book, "dir" = docsettings, "hash" = hashdocsettings.
        val settings = File(home, "settings.reader.lua")
        val mode = settings.takeIf { it.isFile }?.let { f ->
            Regex("""\["document_metadata_folder"\]\s*=\s*"(\w+)"""").find(f.readText())?.groupValues?.get(1)
        }
        out += "KOReader keeps book data: ${mode ?: "default (next to the book)"}" + if (settings.isFile) "" else " (settings file not found)"

        val started = SystemClock.uptimeMillis()
        val sidecars = ArrayList<File>()
        var dirs = 0
        var stopped = false
        // Breadth-first over shared storage; .sdr folders are what we want, other hidden folders and Android/ are skipped.
        val queue = ArrayDeque<File>().apply { add(storage) }
        while (queue.isNotEmpty()) {
            if (SystemClock.uptimeMillis() - started > SCAN_MS || dirs > MAX_DIRS) { stopped = true; break }
            val dir = queue.removeFirst()
            dirs++
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isDirectory) continue
                when {
                    f.name.endsWith(".sdr") -> f.listFiles().orEmpty()
                        .filter { it.isFile && it.name.startsWith("metadata.") && it.name.endsWith(".lua") }
                        .forEach { sidecars += it }
                    f.name.startsWith(".") || (dir == storage && f.name == "Android") -> Unit
                    else -> queue.addLast(f)
                }
            }
        }
        out += "KOReader book files: ${sidecars.size} found, ${dirs} folders searched in ${SystemClock.uptimeMillis() - started} ms" +
            if (stopped) " (stopped early)" else ""

        var withHighlights = 0
        var highlights = 0
        var notes = 0
        val formats = HashMap<String, Int>()
        val kinds = HashMap<String, Int>()
        val places = HashMap<String, Int>()
        for (f in sidecars) {
            val text = runCatching { f.readText() }.getOrNull() ?: continue
            val format = when {
                text.contains("[\"annotations\"]") -> "annotations (new)"
                text.contains("[\"highlight\"]") -> "highlight (old)"
                else -> "none"
            }
            formats.merge(format, 1, Int::plus)
            kinds.merge(f.name.removePrefix("metadata.").removeSuffix(".lua"), 1, Int::plus)
            val place = when {
                f.path.contains("/koreader/hashdocsettings/") -> "hashdocsettings"
                f.path.contains("/koreader/docsettings/") -> "docsettings"
                else -> "next to book"
            }
            places.merge(place, 1, Int::plus)
            // A highlight has a start position; page bookmarks don't.
            val n = Regex("""\["pos0"\]""").findAll(text).count()
            if (n > 0) withHighlights++
            highlights += n
            notes += Regex("""\["note"\]\s*=\s*"""").findAll(text).count()
        }
        if (sidecars.isNotEmpty()) {
            out += "  formats: ${formats.entries.joinToString { "${it.key} ${it.value}" }}"
            out += "  book types: ${kinds.entries.joinToString { "${it.key} ${it.value}" }}"
            out += "  kept: ${places.entries.joinToString { "${it.key} ${it.value}" }}"
            out += "  books with highlights: $withHighlights, highlights: $highlights, with a note: $notes " +
                "(old-format books may count a highlight twice)"
        }
    }

    // ---- NeoReader -----------------------------------------------------------------------

    private fun neoReader(context: Context, out: MutableList<String>) {
        val pm = context.packageManager
        for (pkg in listOf(ReaderLibrary.NEOREADER, "com.onyx")) {
            val providers = runCatching { pm.getPackageInfo(pkg, PackageManager.GET_PROVIDERS).providers }.getOrNull().orEmpty()
            providers.forEach { p -> out += "provider $pkg: ${p.authority} exported=${p.exported} read=${p.readPermission}" }
        }
        for (table in NEOREADER_TABLES) {
            val result = runCatching {
                context.contentResolver.query(Uri.parse(NEOREADER_DB + table), null, null, null, null)?.use { c ->
                    "${c.count} rows; columns: ${c.columnNames.joinToString(", ")}"
                } ?: "no answer"
            }.getOrElse { "${it.javaClass.simpleName}: ${it.message?.take(120)}" }
            out += "NeoReader table $table: $result"
        }
    }
}
