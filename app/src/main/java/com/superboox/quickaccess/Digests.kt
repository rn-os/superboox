package com.superboox.quickaccess

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StrictMode
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Digests: highlights from reading apps in one place (Supernote-style).
 *
 * Sources:
 *  - **KOReader**: read from its per-book files ([KoreaderHighlights]); never changed.
 *  - **Saved**: passages saved with "Save to Digest" from any app's text-selection menu, the
 *    share sheet or the clipboard.
 *
 * What the user adds (saved passages, notes, pins, hidden highlights) lives in SuperBoox's
 * settings under [Prefs.K_DIGESTS], so Backup & restore includes it. KOReader's highlights are
 * cached in a file and re-read in the background.
 */
object Digests {

    enum class Source(val label: String) { KOREADER("KOReader"), NEOREADER("NeoReader"), SAVED("Saved") }

    class Highlight(
        val id: String,
        val source: Source,
        /** Groups highlights into books: the book's file, or the article/app for saved passages. */
        val bookKey: String,
        val book: String,
        val author: String?,
        val bookPath: String?,
        val app: String?,
        val text: String,
        /** The reading app's own note (KOReader); the user's note is [note]. */
        val appNote: String,
        val note: String,
        val page: Int?,
        val chapter: String?,
        val time: Long,
        val pinned: Boolean,
    )

    class Book(val key: String, val title: String, val author: String?, val source: Source, val count: Int, val latest: Long)

    // ---- User state (in Prefs, so it's backed up) ---------------------------------------------

    private class State(
        val saved: MutableList<JSONObject>,
        val notes: MutableMap<String, String>,
        val pinned: MutableSet<String>,
        val hidden: MutableSet<String>,
    )

    private fun state(prefs: Prefs): State {
        val o = runCatching { JSONObject(prefs.sp.getString(Prefs.K_DIGESTS, null) ?: "{}") }.getOrDefault(JSONObject())
        fun arr(k: String) = o.optJSONArray(k) ?: JSONArray()
        val saved = arr("saved").let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) }.toMutableList() }
        val notes = HashMap<String, String>()
        o.optJSONObject("notes")?.let { n -> n.keys().forEach { k -> notes[k] = n.optString(k) } }
        val pinned = arr("pinned").let { a -> (0 until a.length()).map { a.optString(it) }.toMutableSet() }
        val hidden = arr("hidden").let { a -> (0 until a.length()).map { a.optString(it) }.toMutableSet() }
        return State(saved, notes, pinned, hidden)
    }

    private fun save(prefs: Prefs, s: State) {
        val o = JSONObject()
            .put("saved", JSONArray(s.saved))
            .put("notes", JSONObject(s.notes as Map<*, *>))
            .put("pinned", JSONArray(s.pinned.toList()))
            .put("hidden", JSONArray(s.hidden.toList()))
        prefs.sp.edit().putString(Prefs.K_DIGESTS, o.toString()).apply()
        version++
    }

    /** Bumped on every change, so screens know to redraw. */
    @Volatile var version = 0
        private set

    /** Saves a passage from another app. Returns false if it was empty. */
    fun saveText(context: Context, text: String, app: String?, title: String? = null): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        val prefs = Prefs(context)
        val s = state(prefs)
        val appLabel = app?.let { runCatching { Launcher.appLabel(context, it) }.getOrNull() }
        val now = System.currentTimeMillis()
        s.saved += JSONObject()
            .put("id", "s" + hash(t + now))
            .put("text", t.take(MAX_TEXT))
            .put("app", app ?: "")
            .put("appLabel", appLabel ?: "")
            .put("title", title?.takeIf { it.isNotBlank() } ?: appLabel ?: "Saved passages")
            .put("time", now)
        save(prefs, s)
        DebugLog.i("digests", "saved a passage (${t.length} chars) from ${app ?: "?"}")
        return true
    }

    fun setNote(context: Context, id: String, note: String) {
        val prefs = Prefs(context)
        val s = state(prefs)
        if (note.isBlank()) s.notes.remove(id) else s.notes[id] = note.trim()
        save(prefs, s)
    }

    fun setPinned(context: Context, id: String, pinned: Boolean) {
        val prefs = Prefs(context)
        val s = state(prefs)
        if (pinned) s.pinned += id else s.pinned -= id
        save(prefs, s)
    }

    /** Hides a highlight from Digests. Saved passages are deleted; app highlights are only hidden. */
    fun hide(context: Context, h: Highlight) {
        val prefs = Prefs(context)
        val s = state(prefs)
        if (h.source == Source.SAVED) s.saved.removeAll { it.optString("id") == h.id } else s.hidden += h.id
        s.notes.remove(h.id)
        s.pinned -= h.id
        save(prefs, s)
    }

    fun unhideAll(context: Context) {
        val prefs = Prefs(context)
        val s = state(prefs)
        s.hidden.clear()
        save(prefs, s)
    }

    fun hiddenCount(prefs: Prefs) = state(prefs).hidden.size

    // ---- KOReader cache ------------------------------------------------------------------------

    @Volatile private var koCache: List<KoreaderHighlights.Found>? = null
    @Volatile var lastScan = 0L
        private set

    private fun cacheFile(context: Context) = File(context.filesDir, "digests-koreader.json")

    private fun koreader(context: Context): List<KoreaderHighlights.Found> {
        koCache?.let { return it }
        val loaded = runCatching {
            val a = JSONArray(cacheFile(context).readText())
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                KoreaderHighlights.Found(
                    sidecar = File(o.optString("sidecar")),
                    title = o.optString("title"),
                    author = o.optString("author").ifEmpty { null },
                    bookPath = o.optString("path").ifEmpty { null },
                    text = o.optString("text"),
                    note = o.optString("note"),
                    page = if (o.has("page")) o.optInt("page") else null,
                    chapter = o.optString("chapter").ifEmpty { null },
                    time = o.optLong("time"),
                    key = o.optString("key"),
                )
            }
        }.getOrDefault(emptyList())
        koCache = loaded
        return loaded
    }

    /**
     * Re-reads KOReader's per-book files (needs All files access). Slow (it searches shared
     * storage), so call it off the main thread. Returns how many highlights were found, or null
     * when it couldn't look.
     */
    fun refreshKoreader(context: Context): Int? {
        val prefs = Prefs(context)
        if (!prefs.digestKoreader || !ClipHistory.hasFileAccess() || KoreaderHighlights.installed(context) == null) return null
        val started = System.currentTimeMillis()
        val sidecars = KoreaderHighlights.findSidecars()
        val found = sidecars.flatMap { f ->
            runCatching { KoreaderHighlights.read(f) }
                .onFailure { DebugLog.w("digests", "couldn't read a KOReader book file: ${it.javaClass.simpleName} ${it.message}") }
                .getOrDefault(emptyList())
        }
        val a = JSONArray()
        found.forEach { f ->
            a.put(JSONObject().put("sidecar", f.sidecar.path).put("title", f.title).put("author", f.author ?: "")
                .put("path", f.bookPath ?: "").put("text", f.text).put("note", f.note).apply { f.page?.let { put("page", it) } }
                .put("chapter", f.chapter ?: "").put("time", f.time).put("key", f.key))
        }
        runCatching { cacheFile(context).writeText(a.toString()) }
        val changed = koCache?.map { it.key + it.note } != found.map { it.key + it.note }
        koCache = found
        lastScan = System.currentTimeMillis()
        if (changed) version++
        DebugLog.i("digests", "KOReader: ${found.size} highlights in ${sidecars.size} book files (${System.currentTimeMillis() - started} ms)")
        return found.size
    }

    // ---- Everything, merged ----------------------------------------------------------------------

    fun all(context: Context): List<Highlight> {
        val prefs = Prefs(context)
        val s = state(prefs)
        val out = ArrayList<Highlight>()
        if (prefs.digestKoreader) {
            val koApp = KoreaderHighlights.installed(context)
            koreader(context).forEach { f ->
                val id = "k" + hash(f.key)
                if (id in s.hidden) return@forEach
                out += Highlight(
                    id = id, source = Source.KOREADER, bookKey = "ko:" + (f.bookPath ?: f.sidecar.path), book = f.title,
                    author = f.author, bookPath = f.bookPath, app = koApp, text = f.text,
                    appNote = f.note, note = s.notes[id].orEmpty(), page = f.page, chapter = f.chapter,
                    time = f.time.takeIf { it > 0 } ?: f.sidecar.lastModified(), pinned = id in s.pinned,
                )
            }
        }
        s.saved.forEach { o ->
            val id = o.optString("id")
            val title = o.optString("title").ifEmpty { "Saved passages" }
            out += Highlight(
                id = id, source = Source.SAVED, bookKey = "saved:$title", book = title, author = null, bookPath = null,
                app = o.optString("app").ifEmpty { null }, text = o.optString("text"), appNote = "", note = s.notes[id].orEmpty(),
                page = null, chapter = o.optString("appLabel").ifEmpty { null }, time = o.optLong("time"), pinned = id in s.pinned,
            )
        }
        return out
    }

    fun books(list: List<Highlight>): List<Book> =
        list.groupBy { it.bookKey }.map { (key, hs) ->
            val first = hs.first()
            Book(key, first.book, first.author, first.source, hs.size, hs.maxOf { it.time })
        }.sortedByDescending { it.latest }

    // ---- Opening the source --------------------------------------------------------------------

    /**
     * Opens the book in the app it came from. Other apps can't ask KOReader for a page, so it
     * opens where the book was left; the page shows on the highlight.
     */
    fun open(context: Context, h: Highlight): Boolean {
        val path = h.bookPath
        if (h.source == Source.KOREADER && path != null && File(path).exists()) {
            val file = File(path)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "*/*"
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), mime).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            h.app?.let { intent.setPackage(it) }
            val policy = StrictMode.getVmPolicy()
            StrictMode.setVmPolicy(StrictMode.VmPolicy.LAX)
            return try { Launcher.start(context, intent) } finally { StrictMode.setVmPolicy(policy) }
        }
        return h.app?.let { Launcher.launchApp(context, it) } ?: false
    }

    // ---- Markdown export -------------------------------------------------------------------------

    /** Where exports go when no folder was picked: Documents/SuperBoox/Digests (needs All files access). */
    fun defaultFolder(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "SuperBoox/Digests")

    fun markdown(book: Book, hs: List<Highlight>): String = buildString {
        val day = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
        append("# ").append(book.title).append("\n\n")
        listOfNotNull(book.author, book.source.label).joinToString(" · ").let { append("*").append(it).append("*\n\n") }
        for (h in sortForBook(hs)) {
            h.text.lines().forEach { append("> ").append(it).append('\n') }
            val where = listOfNotNull(h.page?.let { "p. $it" }, h.chapter, h.time.takeIf { it > 0 }?.let { day.format(Date(it)) })
            append("\n— ").append(where.joinToString(" · ")).append("\n")
            if (h.appNote.isNotBlank()) append("\n").append(h.appNote).append("\n")
            if (h.note.isNotBlank()) append("\n**Note:** ").append(h.note).append("\n")
            append("\n---\n\n")
        }
    }

    fun sortForBook(hs: List<Highlight>): List<Highlight> =
        hs.sortedWith(compareBy<Highlight>({ it.page ?: Int.MAX_VALUE }, { it.time }))

    /**
     * Writes one Markdown file per book (or just [only]). To the folder picked in Settings
     * (any document provider, Google Drive included), else to [defaultFolder]. Call off the main
     * thread. Returns how many files were written, or throws with a readable message.
     */
    fun export(context: Context, only: String? = null): Int {
        val prefs = Prefs(context)
        val list = all(context)
        val books = books(list).filter { only == null || it.key == only }
        if (books.isEmpty()) throw IllegalStateException("No highlights to export yet")
        val tree = prefs.digestExportUri?.let(Uri::parse)
        var n = 0
        for (b in books) {
            val name = fileName(b.title) + ".md"
            val body = markdown(b, list.filter { it.bookKey == b.key })
            if (tree != null) writeToTree(context, tree, name, body) else {
                if (!ClipHistory.hasFileAccess()) throw IllegalStateException("Choose a folder to save to first (Settings → Digests)")
                val dir = defaultFolder().apply { mkdirs() }
                File(dir, name).writeText(body)
            }
            n++
        }
        prefs.sp.edit().putLong(Prefs.K_DIGEST_EXPORTED, System.currentTimeMillis()).apply()
        DebugLog.i("digests", "exported $n file(s) to ${if (tree != null) "the chosen folder" else "Documents/SuperBoox/Digests"}")
        return n
    }

    private fun writeToTree(context: Context, tree: Uri, name: String, body: String) {
        val cr = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var target: Uri? = null
        cr.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    target = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    break
                }
            }
        }
        val uri = target ?: DocumentsContract.createDocument(cr, parent, "text/markdown", name)
            ?: throw IllegalStateException("Couldn't create $name in that folder")
        val out = runCatching { cr.openOutputStream(uri, "wt") }.getOrNull() ?: cr.openOutputStream(uri, "w")
            ?: throw IllegalStateException("Couldn't write $name")
        out.use { it.write(body.toByteArray()) }
    }

    private fun fileName(title: String): String =
        title.replace(Regex("""[\\/:*?"<>|\n\r\t]"""), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Highlights" }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private const val MAX_TEXT = 4000
}
