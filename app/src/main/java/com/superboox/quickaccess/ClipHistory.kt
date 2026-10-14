package com.superboox.quickaccess

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.util.Patterns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Clipboard history for the panel's Clipboard hub. Kept in its own file (not
 * part of settings backups) with images copied into the app's own storage.
 *
 * Android lets only the app in front read the clipboard, so by default a copy
 * is saved when the panel opens (the panel then has focus). With Advanced
 * access, [checkInBackground] reads it as the shell through Shizuku, which
 * Android allows, so copies are caught as they happen.
 */
object ClipHistory {
    enum class Kind { TEXT, LINK, IMAGE, HANDWRITING }

    data class Clip(
        val id: String,
        val kind: Kind,
        val text: String,
        val image: String?,
        val source: String?,
        val time: Long,
        val pinned: Boolean,
        /** For handwriting: the original clip's label and types, to put it back as BOOX made it. */
        val label: String? = null,
        val mimes: String? = null,
    )

    private const val FILE = "clipboard"
    private const val K_ITEMS = "items"
    private const val K_CURRENT = "current"
    private const val K_STAMP = "stamp"
    private const val K_BOOT = "boot"
    const val AUTHORITY = "com.superboox.quickaccess.clips"
    /** Also [ClipProvider]'s: the name is what makes BOOX Notes read a handwriting link put back by [putBack]. */
    const val NOTES_LINK_AUTHORITY = "com.superboox.quickaccess.fileprovider"

    /** Password managers: their copies are never kept. */
    val DEFAULT_IGNORED = setOf(
        "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.onepassword.android", "com.lastpass.lpandroid",
        "com.dashlane", "keepass2android.keepass2android", "keepass2android.keepass2android_nonet",
        "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre", "com.google.android.apps.authenticator2",
        "com.azure.authenticator", "com.authy.authy", "proton.android.pass", "com.enpass.app",
    )

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun sp(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun dir(context: Context): File = File(context.filesDir, "clips").apply { mkdirs() }

    fun imageFile(context: Context, clip: Clip): File? = clip.image?.let { File(dir(context), it) }

    /** A clip's own files: its picture, and for handwriting the copy of BOOX's files kept beside it. */
    private fun discardFiles(context: Context, clip: Clip) {
        val name = clip.image ?: return
        File(dir(context), name).deleteRecursively()
        File(dir(context), name.removeSuffix(".png")).deleteRecursively()
    }

    // ---- Stored list -------------------------------------------------------------------------

    /** Newest first. */
    fun list(context: Context): List<Clip> {
        val raw = sp(context).getString(K_ITEMS, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Clip(
                    o.getString("id"),
                    runCatching { Kind.valueOf(o.getString("k")) }.getOrDefault(Kind.TEXT),
                    o.optString("t"),
                    o.optString("img").ifEmpty { null },
                    o.optString("src").ifEmpty { null },
                    o.optLong("time"),
                    o.optBoolean("pin"),
                    o.optString("label").ifEmpty { null },
                    o.optString("mimes").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, clips: List<Clip>) {
        val a = JSONArray()
        clips.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("k", c.kind.name).put("t", c.text).put("img", c.image ?: "")
                .put("src", c.source ?: "").put("time", c.time).put("pin", c.pinned)
                .put("label", c.label ?: "").put("mimes", c.mimes ?: ""))
        }
        sp(context).edit().putString(K_ITEMS, a.toString()).apply()
    }

    /** The clip on the clipboard right now, as far as SuperBoox knows. */
    fun currentId(context: Context): String? = sp(context).getString(K_CURRENT, null)

    private fun setCurrent(context: Context, id: String?) {
        sp(context).edit().putString(K_CURRENT, id).apply()
    }

    fun setPinned(context: Context, id: String, pinned: Boolean) {
        save(context, list(context).map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun delete(context: Context, id: String) {
        val all = list(context)
        all.firstOrNull { it.id == id }?.let { c -> discardFiles(context, c) }
        save(context, all.filterNot { it.id == id })
        if (currentId(context) == id) setCurrent(context, null)
    }

    /** Clears everything but pinned clips. */
    fun clear(context: Context) {
        val (keep, drop) = list(context).partition { it.pinned }
        drop.forEach { discardFiles(context, it) }
        save(context, keep)
        DebugLog.i("clipboard", "history cleared (${drop.size} clips, ${keep.size} pinned kept)")
    }

    /** "Keep history after a restart" off: unpinned clips go after a restart. */
    fun onStart(context: Context, prefs: Prefs) {
        val boot = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val last = sp(context).getLong(K_BOOT, 0L)
        sp(context).edit().putLong(K_BOOT, boot).apply()
        if (!prefs.clipKeepAfterRestart && last != 0L && abs(boot - last) > 60_000L) clear(context)
        // Copies BOOX already holds aren't new; the one on the clipboard is still read when the panel opens.
        if (hasFileAccess()) io.execute { File(NOTES_COPY_DIR).list()?.let { seenCopies += it } }
        if (prefs.clipKeepHandwriting) watchNotesCopies(context, prefs)
        watchScreenshots(context, prefs)
    }

    // ---- Capture -----------------------------------------------------------------------------

    private fun isLink(text: String): Boolean {
        val t = text.trim()
        if (t.contains(' ') || t.contains('\n')) return false
        return (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("www.")) && Patterns.WEB_URL.matcher(t).matches()
    }

    private fun sensitive(desc: ClipDescription?): Boolean =
        desc?.extras?.getBoolean("android.content.extra.IS_SENSITIVE") == true

    /**
     * Adds [clip] (read just now) to the history, as copied from [source].
     * The same text again just moves to the top. Runs the image copy off the main thread.
     */
    fun record(context: Context, prefs: Prefs, clip: ClipData?, source: String?) {
        if (clip == null || clip.itemCount == 0) return
        if (sensitive(clip.description)) return DebugLog.i("clipboard", "skipped a copy marked sensitive")
        if (source != null && source in prefs.clipIgnored) return DebugLog.i("clipboard", "skipped a copy from $source (ignored)")
        val item = clip.getItemAt(0)
        val uri = item.uri
        val mime = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() } ?: clip.description?.let { d ->
            (0 until d.mimeTypeCount).map { d.getMimeType(it) }.firstOrNull { it.startsWith("image/") }
        }
        if (uri != null && mime?.startsWith("image/") == true) {
            if (!prefs.clipKeepImages) return
            if (uri.authority == AUTHORITY) return // our own image, put back from the history
            io.execute {
                val name = "${System.currentTimeMillis()}.png"
                val ok = runCatching { copyImage(context, uri, File(dir(context), name)) }
                    .onFailure { DebugLog.w("clipboard", "couldn't keep the image: ${it.message}") }.getOrDefault(false)
                if (ok) main.post { add(context, prefs, Kind.IMAGE, "", name, source) }
            }
            return
        }
        if (uri != null && item.text == null && item.htmlText == null) return recordUri(context, prefs, clip, uri, source)
        val text = (item.text ?: item.htmlText?.let { android.text.Html.fromHtml(it, 0) })?.toString()
        if (text.isNullOrBlank()) return
        val kind = if (isLink(text)) Kind.LINK else Kind.TEXT
        when {
            kind == Kind.LINK && !prefs.clipKeepLinks -> return
            kind == Kind.TEXT && isCode(text) -> if (!prefs.clipKeepCodes) return DebugLog.i("clipboard", "skipped a code (Codes are off)")
            kind == Kind.TEXT && !prefs.clipKeepText -> return
        }
        add(context, prefs, kind, text.take(20_000), null, source)
    }

    /** A short code on its own, like a 6-digit sign-in code. */
    fun isCode(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 4..10 || t.any { it.isWhitespace() }) return false
        val digits = t.count { it.isDigit() }
        return digits >= 4 && digits * 2 >= t.length && t.all { it.isLetterOrDigit() || it == '-' }
    }

    /**
     * A clip that's only a file link (no text). BOOX Notes' lasso copy is one: a link to
     * its own "CopyPoints" file. Kept as handwriting, never shown as the raw address.
     */
    private fun recordUri(context: Context, prefs: Prefs, clip: ClipData, uri: Uri, source: String?) {
        val desc = clip.description
        val mimes = desc?.let { d -> (0 until d.mimeTypeCount).map { d.getMimeType(it) } }.orEmpty()
        val label = desc?.label?.toString()
        val fromNotes = uri.authority.orEmpty().contains("onyx") || uri.path.orEmpty().contains("CopyPoints")
        io.execute {
            // What the linked file is (for the debug report): its type, size, first bytes, and whether it's a picture.
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val head = runCatching { context.contentResolver.openInputStream(uri)?.use { s -> ByteArray(32).let { b -> b.copyOf(s.read(b).coerceAtLeast(0)) } } }
            val size = runCatching { context.contentResolver.openInputStream(uri)?.use { s -> var n = 0L; val b = ByteArray(8192); while (true) { val r = s.read(b); if (r < 0) break; n += r }; n } }.getOrNull()
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            runCatching { context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } }
            val picture = bounds.outWidth > 0 && bounds.outHeight > 0
            runCatching { describeFile(context, uri) }.onSuccess { DebugLog.i("clipboard", "file clip contents:\n$it") }
                .onFailure { DebugLog.w("clipboard", "file clip unreadable: ${it.javaClass.simpleName} ${it.message}") }
            DebugLog.i("clipboard", "file clip: ${uri.authority}${uri.path?.substringBeforeLast('/')?.let { "$it/…" }} label=$label mimes=$mimes type=$type " +
                "size=$size picture=$picture${if (picture) " ${bounds.outWidth}x${bounds.outHeight}" else ""} " +
                "head=${head.getOrNull()?.joinToString("") { "%02x".format(it) } ?: head.exceptionOrNull()?.javaClass?.simpleName}")
            when {
                picture && prefs.clipKeepImages -> {
                    val name = "${System.currentTimeMillis()}.png"
                    if (runCatching { copyImage(context, uri, File(dir(context), name)) }.getOrDefault(false)) {
                        main.post { add(context, prefs, Kind.IMAGE, "", name, source) }
                    }
                }
                fromNotes && prefs.clipKeepHandwriting -> {
                    // Already kept when BOOX made the copy ([checkNotesCopies]): just bring that clip to the top.
                    val name = uri.lastPathSegment.orEmpty()
                    val kept = list(context).firstOrNull { it.kind == Kind.HANDWRITING && it.text.endsWith("/$name") }?.text
                        ?: (NOTES_COPY_LINK + name).takeIf { name in keptCopies }
                    if (kept != null) {
                        main.post { add(context, prefs, Kind.HANDWRITING, kept, null, source, label, mimes.joinToString(",")) }
                    } else {
                        seenCopies += name
                        keptCopies += name
                        val preview = notesCopyDir(uri)?.let { runCatching { handwritingPreview(context, it) }.onFailure { e -> DebugLog.w("clipboard", "handwriting preview failed: ${e.message}") }.getOrNull() }
                        main.post { add(context, prefs, Kind.HANDWRITING, uri.toString(), preview, source, label, mimes.joinToString(",")) }
                    }
                }
                else -> DebugLog.i("clipboard", "file clip not kept")
            }
        }
    }

    /**
     * BOOX Notes' lasso copy is a folder on shared storage, linked as
     * content://…onyx.fileprovider/external/.noteCache/CopyPoints/<id> (build 54 report).
     */
    private fun notesCopyDir(uri: Uri): String? {
        val p = uri.path ?: return null
        if (!uri.authority.orEmpty().endsWith(".fileprovider") || !p.startsWith("/external/")) return null
        val path = "/storage/emulated/0/" + p.removePrefix("/external/")
        return path.takeIf { Regex("[A-Za-z0-9._/-]+").matches(it) }
    }

    /** Draws BOOX's strokes (shape.pb in the kept folder) into [png]; logs their structure if they can't be read. */
    private fun drawStrokes(keep: File, png: String, context: Context): String? {
        val pb = File(keep, "shape.pb").takeIf { it.exists() }?.readBytes() ?: return null
        val bmp = runCatching { BooxShapes.render(pb) }.onFailure { DebugLog.w("clipboard", "drawing handwriting failed: ${it.message}") }.getOrNull()
        if (bmp == null) {
            DebugLog.i("clipboard", "handwriting strokes not recognised; " + runCatching { BooxShapes.describe(pb) }.getOrElse { it.message })
            return null
        }
        File(dir(context), png).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        DebugLog.i("clipboard", "handwriting drawn from shape.pb (${bmp.width}x${bmp.height})")
        bmp.recycle()
        return png
    }

    /** Handwriting saved before it could be drawn: points it at its picture once [draw] has made one. Cheap. */
    fun ensurePreview(context: Context, clip: Clip): Clip {
        val folder = clip.image?.takeIf { clip.kind == Kind.HANDWRITING && !it.endsWith(".png") } ?: return clip
        if (!File(dir(context), "$folder.png").exists()) return clip
        val updated = clip.copy(image = "$folder.png")
        save(context, list(context).map { if (it.id == updated.id) updated else it })
        return updated
    }

    /** The folder holding a handwriting clip's copy of BOOX's files, whether or not its picture is drawn yet. */
    private fun keptDir(context: Context, clip: Clip): File? =
        clip.image?.takeIf { clip.kind == Kind.HANDWRITING }?.let { File(dir(context), it.removeSuffix(".png")) }?.takeIf { it.isDirectory }

    /** A picture from a build that misread the points (1.1.0-dev.77 on the Note Air 6C): a line a few pixels high. */
    private fun flatPicture(png: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(png.path, bounds)
        return bounds.outHeight in 1..16
    }

    /** Handwriting with kept files but no picture yet (or a misdrawn one), and not already tried. */
    fun needsDrawing(context: Context, clip: Clip): Boolean {
        val keep = keptDir(context, clip) ?: return false
        val png = File(dir(context), "${keep.name}.png")
        if (png.exists()) return !File(keep, "redrawn").exists() && flatPicture(png)
        return !File(keep, "drawn-failed-2").exists()
    }

    /** Draws [clip]'s handwriting to its picture (once; off the main thread). [ensurePreview] then uses it. */
    fun draw(context: Context, clip: Clip) {
        if (!needsDrawing(context, clip)) return
        val keep = keptDir(context, clip) ?: return
        val again = File(dir(context), "${keep.name}.png").exists()
        if (drawStrokes(keep, "${keep.name}.png", context) == null) runCatching { File(keep, "drawn-failed-2").createNewFile() }
        if (again) runCatching { File(keep, "redrawn").createNewFile() }
    }

    /** Android's "All files access" is on for SuperBoox: BOOX's copy folder can be read directly. */
    fun hasFileAccess(): Boolean = Build.VERSION.SDK_INT >= 30 && android.os.Environment.isExternalStorageManager()

    /**
     * Reads BOOX's copy folder (directly with All files access, else through
     * Advanced access): logs what it holds, keeps a copy of its files so the
     * strokes can be drawn later, and returns a picture from it for the preview
     * if there is one (else the kept folder's name).
     */
    private fun handwritingPreview(context: Context, folder: String): String? {
        val files: List<Pair<String, ByteArray>> = when {
            hasFileAccess() -> {
                val root = File(folder)
                DebugLog.i("clipboard", "handwriting folder (file access): " + (root.walkTopDown().take(60).joinToString("\n  ", prefix = "\n  ") {
                    "${if (it.isDirectory) "d" else "-"} ${it.length()} ${it.path.removePrefix(folder)}"
                }))
                root.walkTopDown().filter { it.isFile }.take(20).mapNotNull { f ->
                    runCatching { f.path.removePrefix(folder) to f.readBytes() }.getOrNull()
                }.toList()
            }
            AdvancedAccess.allowed() -> {
                DebugLog.i("clipboard", "handwriting folder (Advanced access):\n" + AdvancedAccess.shellNow("ls -laR '$folder' 2>&1 | head -60").second)
                AdvancedAccess.shellNow("find '$folder' -type f 2>/dev/null | head -20").second.lines().map { it.trim() }
                    .filter { it.startsWith(folder) && Regex("[A-Za-z0-9._/ -]+").matches(it) }
                    .mapNotNull { f -> AdvancedAccess.shellBytes("cat '$f'", max = 4 * 1024 * 1024)?.let { f.removePrefix(folder) to it } }
            }
            else -> {
                DebugLog.i("clipboard", "handwriting preview needs All files access or Advanced access")
                return null
            }
        }
        val stamp = System.currentTimeMillis()
        val keep = File(dir(context), "h$stamp").apply { mkdirs() }
        var picture: String? = null
        for ((name, bytes) in files) {
            File(keep, name.trim('/').replace('/', '_').ifEmpty { "file" }).writeBytes(bytes)
            val hex = bytes.take(64).joinToString(" ") { "%02x".format(it) }
            val text = String(bytes.take(240).map { c -> if (c in 32..126) c.toInt().toChar() else '.' }.toCharArray())
            DebugLog.i("clipboard", "handwriting file $name (${bytes.size} bytes)\n  hex: $hex\n  text: $text")
            if (picture == null) {
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                if (bmp != null) {
                    val png = "h$stamp.png"
                    File(dir(context), png).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    DebugLog.i("clipboard", "handwriting preview from $name (${bmp.width}x${bmp.height})")
                    bmp.recycle()
                    picture = png
                }
            }
        }
        // The strokes are drawn later, off the main thread (the panel asks for it), since finding them can take a while.
        if (picture == null) DebugLog.i("clipboard", "handwriting: no picture in BOOX's copy (${files.size} files kept for drawing the strokes)")
        // No picture yet: the clip still points at its kept files (the folder), so they go when it's deleted.
        return picture ?: keep.name
    }

    /**
     * What a linked file holds, for working out BOOX's handwriting format from a
     * debug report: its first bytes (hex and readable text), and inside it if it's
     * gzip or zip. Handwriting strokes only, nothing personal beyond the shape data.
     */
    private fun describeFile(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { s -> s.readNBytesCompat(4096) } ?: return "no stream"
        fun hex(b: ByteArray, n: Int) = b.take(n).joinToString(" ") { "%02x".format(it) }
        fun ascii(b: ByteArray, n: Int) = String(b.take(n).map { c -> if (c in 32..126) c.toInt().toChar() else '.' }.toCharArray())
        val out = StringBuilder()
        out.append("  hex: ").append(hex(bytes, 96)).append('\n')
        out.append("  text: ").append(ascii(bytes, 400)).append('\n')
        when {
            bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte() -> {
                val inner = context.contentResolver.openInputStream(uri)?.use { java.util.zip.GZIPInputStream(it).readNBytesCompat(2048) } ?: ByteArray(0)
                out.append("  gzip inside, hex: ").append(hex(inner, 96)).append('\n')
                out.append("  gzip inside, text: ").append(ascii(inner, 600)).append('\n')
            }
            bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val zip = java.util.zip.ZipInputStream(s)
                    var n = 0
                    while (n < 12) {
                        val e = zip.nextEntry ?: break
                        val head = zip.readNBytesCompat(256)
                        out.append("  zip entry ${e.name} (${e.size}): ").append(ascii(head, 160)).append('\n')
                        n++
                    }
                }
            }
        }
        return out.toString().trimEnd()
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val buf = java.io.ByteArrayOutputStream()
        val b = ByteArray(1024)
        while (buf.size() < max) {
            val r = read(b, 0, minOf(b.size, max - buf.size()))
            if (r < 0) break
            buf.write(b, 0, r)
        }
        return buf.toByteArray()
    }

    private fun add(context: Context, prefs: Prefs, kind: Kind, text: String, image: String?, source: String?, label: String? = null, mimes: String? = null) {
        val all = list(context)
        // Pictures only match by their original file name (a screenshot both caught and shared); others by text.
        val same = if (kind == Kind.IMAGE) all.firstOrNull { text.isNotEmpty() && it.kind == Kind.IMAGE && it.text == text }
            else all.firstOrNull { it.kind != Kind.IMAGE && it.text == text }
        if (same != null && kind == Kind.IMAGE && image != null && image != same.image) File(dir(context), image).delete()
        val now = System.currentTimeMillis()
        val updated = if (same != null) {
            if (all.firstOrNull() == same) {
                setCurrent(context, same.id)
                return
            }
            listOf(same.copy(time = now)) + all.filterNot { it.id == same.id }
        } else {
            val c = Clip("c$now", kind, text, image, source, now, pinned = false, label = label, mimes = mimes)
            DebugLog.i("clipboard", "saved a ${kind.name.lowercase()} clip from ${source ?: "?"}")
            listOf(c) + all
        }
        // Keep the newest unpinned clips up to the limit; pinned ones always stay.
        var unpinned = 0
        val kept = updated.filter { c -> c.pinned || ++unpinned <= prefs.clipLimit }
        updated.filterNot { it in kept }.forEach { discardFiles(context, it) }
        save(context, kept)
        setCurrent(context, (same ?: kept.first()).id)
    }

    private fun copyImage(context: Context, uri: Uri, to: File): Boolean {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1800) sample *= 2
        val bmp = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false
        to.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return true
    }

    // ---- Screenshots, and things shared to "Copy to clipboard" ---------------------------------

    /** The label of an image clip that's a screenshot, so the panel shows it as one. */
    const val SCREENSHOT = "SCREENSHOT"

    fun isScreenshot(clip: Clip): Boolean = clip.kind == Kind.IMAGE && clip.label == SCREENSHOT

    /** Where BOOX and Android save screenshots; whichever exist are watched. */
    private val SHOT_DIRS = listOf("Pictures/Screenshots", "DCIM/Screenshots", "Screenshots").map { File("/storage/emulated/0/$it") }
    private val PICTURE_TYPES = setOf("png", "jpg", "jpeg", "webp")

    /** Screens that aren't the app a screenshot or share came from. */
    private val NOT_SOURCES = setOf("android", "com.android.systemui", "com.android.intentresolver", "com.onyx.floatingbutton")

    /** The app in front (behind the panel or share sheet), as the source of a screenshot or share. */
    fun appInFront(context: Context): String? =
        QuickAccessService.instance?.foregroundPackage?.takeIf { isAppSource(context, it) }

    fun isAppSource(context: Context, pkg: String): Boolean = pkg !in NOT_SOURCES && pkg != context.packageName

    /** Its file name, from the app that shared it ("Screenshot_….png"), if it says. */
    private fun displayName(context: Context, uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private fun looksLikeScreenshot(name: String?): Boolean = name?.contains("screenshot", ignoreCase = true) == true

    /** Adds the newest clip just saved to the clipboard too. Main thread. */
    private fun putNewestBack(context: Context) {
        val c = list(context).firstOrNull { it.id == currentId(context) } ?: return
        putBack(context, c)
    }

    /**
     * Something shared to "Copy to clipboard" ([ShareToClipboardActivity]): kept in the history and put on
     * the clipboard. It was shared on purpose, so the What to keep ticks don't apply. Reads the pictures
     * here, so call it off the main thread while the sharing app's permission lasts. True if anything was kept.
     */
    fun keepShared(context: Context, prefs: Prefs, uris: List<Uri>, text: String?, source: String?): Boolean {
        // A clip shared back from the Clipboard itself: just put it back on the clipboard.
        uris.singleOrNull()?.takeIf { it.authority == AUTHORITY }?.let { own ->
            val clip = list(context).firstOrNull { it.image == own.lastPathSegment } ?: return false
            main.post { putBack(context, clip) }
            return true
        }
        val pictures = uris.mapNotNull { uri ->
            val name = displayName(context, uri)
            val png = "${System.currentTimeMillis()}-${uris.indexOf(uri)}.png"
            val ok = runCatching { copyImage(context, uri, File(dir(context), png)) }
                .onFailure { DebugLog.w("clipboard", "couldn't keep a shared picture: ${it.message}") }.getOrDefault(false)
            if (ok) Triple(png, name.orEmpty(), looksLikeScreenshot(name)) else null
        }
        val words = text?.trim()?.takeIf { it.isNotEmpty() && pictures.isEmpty() }
        if (pictures.isEmpty() && words == null) return false
        main.post {
            // Each new clip goes on top, so add them in reverse: the first one shared ends up on top and on the clipboard.
            pictures.asReversed().forEach { (png, name, shot) -> add(context, prefs, Kind.IMAGE, name, png, source, if (shot) SCREENSHOT else null) }
            if (words != null) add(context, prefs, if (isLink(words)) Kind.LINK else Kind.TEXT, words.take(20_000), null, source)
            putNewestBack(context)
        }
        DebugLog.i("clipboard", "kept a share from ${source ?: "?"}: ${pictures.size} pictures${if (words != null) ", text" else ""}")
        return true
    }

    /** Where Mark up puts a gallery copy of a picture clip that has none; watched with the screenshot folders. */
    private val MARKUP_DIR = File("/storage/emulated/0/Pictures/SuperBoox")
    private val watchedDirs = SHOT_DIRS + MARKUP_DIR

    // Pictures in the watched folders as last kept (path to last modified), and since when new ones count. Only touched on [io].
    private val shotTimes = HashMap<String, Long>()
    private var shotsSince = 0L
    private var shotWatcher: android.os.FileObserver? = null
    private val pendingShots = Any()

    /**
     * "Screenshots" kept: watches the screenshot folders (needs All files access) and keeps each new
     * screenshot as it's saved. Starts or stops to match the setting; safe to call again any time.
     * Watches the folders above too, since the first screenshot may create the folder.
     */
    fun watchScreenshots(context: Context, prefs: Prefs) {
        val on = prefs.clipKeepScreenshots && hasFileAccess()
        synchronized(pendingShots) {
            if (!on) {
                shotWatcher?.stopWatching()
                shotWatcher = null
                return
            }
            if (shotWatcher != null) return
        }
        val app = context.applicationContext
        io.execute {
            // Screenshots already there when it starts aren't new.
            if (shotsSince == 0L) shotsSince = System.currentTimeMillis()
            startShotWatcher(app, prefs)
        }
    }

    private fun startShotWatcher(context: Context, prefs: Prefs) {
        val dirs = (watchedDirs + watchedDirs.mapNotNull { it.parentFile }).distinct().filter { it.isDirectory }
        if (dirs.isEmpty()) return
        val watcher = object : android.os.FileObserver(dirs, CREATE or CLOSE_WRITE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) = checkScreenshots(context, prefs)
        }
        synchronized(pendingShots) {
            if (!prefs.clipKeepScreenshots) return
            shotWatcher?.stopWatching()
            shotWatcher = watcher.also { it.startWatching() }
        }
    }

    /** The picture clip a gallery file belongs to: its original (by name), or the copy Mark up made of it. */
    private fun clipForFile(context: Context, name: String): Clip? =
        list(context).firstOrNull { it.kind == Kind.IMAGE && (it.text == name || markUpName(it) == name) }

    private fun markUpName(clip: Clip): String? = clip.image?.let { "SuperBoox_${it.removeSuffix(".png")}.png" }

    /**
     * Keeps screenshots saved since watching started that aren't kept yet, and takes in a picture marked
     * up in place (its file changed): the clip then shows it and it goes on the clipboard. Debounced:
     * the file is still being written.
     */
    private fun checkScreenshots(context: Context, prefs: Prefs) {
        if (!prefs.clipKeepScreenshots || !hasFileAccess()) return
        main.removeCallbacksAndMessages(pendingShots)
        main.postAtTime({
            val source = appInFront(context)
            io.execute {
                // Hidden names are files still being written: BOOX saves ".pending-<n>-Screenshot_….png" and renames it when done.
                val files = watchedDirs.flatMap { d -> d.listFiles { f -> f.isFile && !f.name.startsWith(".") && f.extension.lowercase() in PICTURE_TYPES }.orEmpty().toList() }
                    .filter { it.length() > 0 }.sortedBy { it.lastModified() }
                for (f in files) {
                    val known = shotTimes[f.path]
                    if (known != null && f.lastModified() <= known) continue
                    if (known == null && f.lastModified() < shotsSince - 2_000L) continue
                    // Already a clip (a picture saved over by an editor, or its Mark up copy): take in the new picture.
                    val clip = clipForFile(context, f.name)
                    if (clip != null) {
                        val to = imageFile(context, clip)
                        if (to != null && runCatching { copyImage(context, Uri.fromFile(f), to) }.getOrDefault(false)) {
                            DebugLog.i("clipboard", "took in a picture marked up in place (${f.parentFile?.name}/…)")
                            main.post { list(context).firstOrNull { it.id == clip.id }?.let { putBack(context, it) } }
                        }
                        shotTimes[f.path] = f.lastModified()
                        continue
                    }
                    if (source != null && source in prefs.clipIgnored) continue
                    val png = "${System.currentTimeMillis()}.png"
                    val ok = runCatching { copyImage(context, Uri.fromFile(f), File(dir(context), png)) }
                        .onFailure { DebugLog.w("clipboard", "couldn't keep a screenshot: ${it.message}") }.getOrDefault(false)
                    // Not readable yet: tried again on the next change in the folder.
                    if (!ok) continue
                    shotTimes[f.path] = f.lastModified()
                    // A new file beside a mark-up copy is the editor's saved copy: a picture, not a screenshot.
                    val shot = f.parentFile != MARKUP_DIR
                    DebugLog.i("clipboard", "kept a ${if (shot) "screenshot" else "marked-up picture"} as it was saved (${f.parentFile?.name}/…)")
                    main.post {
                        add(context, prefs, Kind.IMAGE, f.name, png, source, if (shot) SCREENSHOT else null)
                        if (prefs.clipShotToClipboard || !shot) putNewestBack(context)
                        // Not for a picture the editor itself just saved, or each mark-up would open another.
                        if (shot && prefs.clipShotMarkUp && source?.contains("gallery") != true) list(context).firstOrNull { it.image == png }?.let { markUp(context, it) }
                    }
                }
                // A screenshot folder may have just been made.
                startShotWatcher(context, prefs)
            }
        }, pendingShots, SystemClock.uptimeMillis() + 800L)
    }

    /**
     * Opens a picture clip in the tablet's picture editor (Android's Edit with…), to mark it up. BOOX's
     * Gallery editor opens pictures by their file path, so it gets a gallery item: the screenshot itself
     * when it's still there, else a copy in Pictures/SuperBoox. A mark-up saved over that file, or as a
     * new file beside it, comes back to the Clipboard (while Screenshots are watched). [done]: whether an
     * editor opened.
     */
    fun markUp(context: Context, clip: Clip, done: (Boolean) -> Unit = {}) {
        if (clip.kind != Kind.IMAGE) return done(false)
        val app = context.applicationContext
        io.execute {
            val uri = runCatching { galleryUri(app, clip) }
                .onFailure { DebugLog.w("clipboard", "mark up: no gallery copy: ${it.javaClass.simpleName} ${it.message}") }.getOrNull()
            main.post {
                val edit = uri?.let {
                    Intent(Intent.ACTION_EDIT).setDataAndType(it, "image/*")
                        // A new task each time: an editor still open from last time would show its old picture.
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                val ok = edit != null && Launcher.tryStart(app, edit)
                DebugLog.i("clipboard", if (ok) "opened a picture to mark up" else "no picture editor opened")
                if (ok && edit != null) reopenIfClosedAtOnce(app, edit)
                done(ok)
            }
        }
    }

    /**
     * BOOX's Gallery editor sometimes closes itself about a second after a cold start (its drawing view isn't
     * ready when the picture is; Note Air 6C, firmware 4.3); a second start works. So: if the editor came to
     * the front and left again within a few seconds, it's opened once more. Main thread.
     */
    private fun reopenIfClosedAtOnce(context: Context, edit: Intent) {
        val editor = runCatching { context.packageManager.resolveActivity(edit, 0)?.activityInfo?.packageName }.getOrNull() ?: return
        val started = SystemClock.uptimeMillis()
        var seen = false
        val check = object : Runnable {
            override fun run() {
                val front = QuickAccessService.instance?.foregroundPackage ?: return
                if (front == editor) seen = true
                else if (seen) {
                    DebugLog.i("clipboard", "the editor closed right after opening; opening it again")
                    Launcher.tryStart(context, edit)
                    return
                }
                if (SystemClock.uptimeMillis() - started < 4_000L) main.postDelayed(this, 250L)
            }
        }
        main.postDelayed(check, 250L)
    }

    /** The clip's picture as a gallery (MediaStore) item. Off the main thread. */
    private fun galleryUri(context: Context, clip: Clip): Uri? {
        val images = android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val name = android.provider.MediaStore.Images.Media.DISPLAY_NAME
        fun find(file: String): Uri? = context.contentResolver.query(images, arrayOf(android.provider.BaseColumns._ID), "$name=?", arrayOf(file),
            "${android.provider.MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(images, c.getLong(0)) else null }
        // The screenshot (or shared picture) itself, while it's still in the gallery. Others' pictures need All files access to find.
        if (clip.text.isNotEmpty() && hasFileAccess()) find(clip.text)?.let { return it }
        // Else a copy of the kept picture in Pictures/SuperBoox, made once.
        val copyName = markUpName(clip) ?: return null
        find(copyName)?.let { return it }
        val src = imageFile(context, clip)?.takeIf { it.exists() } ?: return null
        val values = ContentValues().apply {
            put(name, copyName)
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SuperBoox")
        }
        val uri = context.contentResolver.insert(images, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> src.inputStream().use { it.copyTo(out) } }
        // In a watched folder: SuperBoox's own copy isn't a new picture, though a mark-up saved over it is.
        File(MARKUP_DIR, copyName).let { shotTimes[it.path] = it.lastModified() }
        return uri
    }

    // ---- BOOX Notes' lasso copies, as they're made ---------------------------------------------

    private const val NOTES_COPY_DIR = "/storage/emulated/0/.noteCache/CopyPoints"
    private const val NOTES_COPY_LINK = "content://com.onyx.android.note.onyx.fileprovider/external/.noteCache/CopyPoints/"

    // Folder names in BOOX's copy folder: looked at already, and kept as a clip. Only touched on [io].
    private val seenCopies = HashSet<String>()
    private val keptCopies = HashSet<String>()
    private var copyWatcher: android.os.FileObserver? = null
    private val pendingCopies = Any()

    /**
     * BOOX removes its previous lasso copy when a new one is made, so a copy that's only read when
     * the panel next opens loses every copy before the last. With All files access the copy
     * folder is watched instead, and each copy is kept as it's made. Watches the folder above
     * too, and is set up again after each look, because BOOX may recreate the copy folder itself.
     */
    private fun watchNotesCopies(context: Context, prefs: Prefs) {
        if (!hasFileAccess()) return
        val dirs = listOf(File(NOTES_COPY_DIR).parentFile, File(NOTES_COPY_DIR)).filterNotNull().filter { it.isDirectory }
        if (dirs.isEmpty()) return
        val app = context.applicationContext
        val watcher = object : android.os.FileObserver(dirs, CREATE or MOVED_TO) {
            override fun onEvent(event: Int, path: String?) = checkNotesCopies(app, prefs)
        }
        synchronized(pendingCopies) {
            copyWatcher?.stopWatching()
            copyWatcher = watcher.also { it.startWatching() }
        }
    }

    /** Keeps any lasso copy in BOOX's copy folder that hasn't been looked at yet. Debounced: BOOX is still writing it. */
    fun checkNotesCopies(context: Context, prefs: Prefs) {
        if (!prefs.clipKeepHandwriting || !hasFileAccess()) return
        main.removeCallbacksAndMessages(pendingCopies)
        main.postAtTime({
            io.execute {
                val source = prefs.notesPackage
                if (source in prefs.clipIgnored) return@execute
                val fresh = File(NOTES_COPY_DIR).listFiles { f -> f.isDirectory }.orEmpty()
                    .filter { it.name !in seenCopies && File(it, "shape.pb").length() > 0 }.sortedBy { it.lastModified() }
                for (folder in fresh) {
                    seenCopies += folder.name
                    if (list(context).any { it.kind == Kind.HANDWRITING && it.text.endsWith("/${folder.name}") }) continue
                    val preview = runCatching { handwritingPreview(context, folder.path) }
                        .onFailure { e -> DebugLog.w("clipboard", "handwriting preview failed: ${e.message}") }.getOrNull() ?: continue
                    keptCopies += folder.name
                    DebugLog.i("clipboard", "kept a lasso copy as BOOX made it")
                    main.post { add(context, prefs, Kind.HANDWRITING, NOTES_COPY_LINK + folder.name, preview, source, "NOTE_SHAPES", ClipDescription.MIMETYPE_TEXT_URILIST) }
                }
                watchNotesCopies(context, prefs)
            }
        }, pendingCopies, SystemClock.uptimeMillis() + 500L)
    }

    /**
     * Reads the clipboard as the app in front (the panel, once it has focus).
     * Skips the read when the clip hasn't changed since last time.
     */
    fun checkInForeground(context: Context, prefs: Prefs, source: String?) {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching {
            val desc = cm.primaryClipDescription ?: return
            val stamp = if (Build.VERSION.SDK_INT >= 26) desc.timestamp else 0L
            if (stamp != 0L && stamp == sp(context).getLong(K_STAMP, -1L)) return
            sp(context).edit().putLong(K_STAMP, stamp).apply()
            record(context, prefs, cm.primaryClip, source)
        }.onFailure { DebugLog.w("clipboard", "read failed: ${it.message}") }
    }

    // ---- In the background, through Shizuku -------------------------------------------------

    private var clipboardShell: Any? = null
    private var shellFailed: String? = null

    /** Why reading in the background isn't working, if it was tried and failed (for the debug report). */
    fun backgroundProblem(): String? = shellFailed

    /** Android's clipboard service, called as the shell (which may read it any time). */
    private fun shellClipboard(): Any? {
        clipboardShell?.let { return it }
        return runCatching {
            if (Build.VERSION.SDK_INT >= 28) org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            val binder = rikka.shizuku.SystemServiceHelper.getSystemService("clipboard") ?: error("no clipboard service")
            val wrapped = rikka.shizuku.ShizukuBinderWrapper(binder)
            Class.forName("android.content.IClipboard\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, wrapped)
        }.onFailure { shellFailed = "${it.javaClass.simpleName}: ${(it.cause ?: it).message}" }
            .getOrNull()?.also { clipboardShell = it }
    }

    /** Calls IClipboard.[name] as com.android.shell, filling the arguments by type (they differ between Android versions). */
    private fun callShell(name: String): Any? {
        val service = shellClipboard() ?: return null
        val m = service.javaClass.methods.firstOrNull { it.name == name } ?: error("no $name")
        var strings = 0
        var ints = 0
        val args = m.parameterTypes.map { t ->
            when (t) {
                String::class.java -> if (strings++ == 0) "com.android.shell" else null
                Int::class.javaPrimitiveType -> if (ints++ == 0) Process.myUid() / 100_000 else 0
                else -> null
            }
        }
        return m.invoke(service, *args.toTypedArray())
    }

    private val pending = Any()

    /**
     * Something that may have been a copy just happened (a tap, a toast, a new
     * app in front): reads the clipboard as the shell if it changed. Debounced.
     */
    fun checkInBackground(context: Context, prefs: Prefs, source: String?) {
        if (source == prefs.notesPackage) checkNotesCopies(context, prefs)
        if (prefs.clipCatchMode != Prefs.CLIP_EVERY || !AdvancedAccess.allowed()) return
        main.removeCallbacksAndMessages(pending)
        main.postAtTime({
            io.execute {
                val result = runCatching {
                    val desc = callShell("getPrimaryClipDescription") as? ClipDescription ?: return@runCatching null
                    val stamp = if (Build.VERSION.SDK_INT >= 26) desc.timestamp else 0L
                    if (stamp != 0L && stamp == sp(context).getLong(K_STAMP, -1L)) return@runCatching null
                    sp(context).edit().putLong(K_STAMP, stamp).apply()
                    callShell("getPrimaryClip") as? ClipData
                }
                result.onFailure {
                    shellFailed = "${it.javaClass.simpleName}: ${(it.cause ?: it).message}"
                    DebugLog.w("clipboard", "background read failed: $shellFailed")
                }
                result.getOrNull()?.let { clip -> main.post { record(context, prefs, clip, source) } }
            }
        }, pending, SystemClock.uptimeMillis() + 700L)
    }

    // ---- Back onto the clipboard ----------------------------------------------------------------

    /**
     * Puts a handwriting copy's files back into its folder in BOOX's copy folder, which BOOX
     * empties at every new copy (needs All files access). Returns what happened, for the log.
     */
    private fun restoreHandwriting(context: Context, clip: Clip): String {
        val folder = notesCopyDir(Uri.parse(clip.text)) ?: return "not a BOOX copy folder"
        val target = File(folder)
        val there = target.isDirectory && target.list()?.isNotEmpty() == true
        if (!hasFileAccess()) return if (there) "BOOX's folder still there" else "BOOX's folder is gone; restoring it needs All files access"
        val keep = keptDir(context, clip) ?: return if (there) "BOOX's folder still there" else "no kept copy of the strokes"
        return runCatching {
            target.mkdirs()
            strokeFiles(keep).forEach { f -> f.copyTo(File(target, f.name), overwrite = true) }
            if (there) "BOOX's folder refreshed" else "restored BOOX's folder"
        }.getOrElse { e -> "restoring BOOX's folder failed: ${e.message}" }
    }

    /** BOOX's own files in a kept copy, without SuperBoox's markers. */
    private fun strokeFiles(keep: File): List<File> =
        keep.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith("drawn-failed") && it.name != "redrawn" }

    fun putBack(context: Context, clip: Clip): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        val data = when (clip.kind) {
            Kind.IMAGE -> ClipData.newUri(context.contentResolver, "Image", contentUri(clip) ?: return false)
            // BOOX's own clip, as BOOX made it: pastes back into BOOX Notes while BOOX still has the copy.
            Kind.HANDWRITING -> ClipData(
                ClipDescription(clip.label ?: "Handwriting", clip.mimes?.split(',')?.filter { it.isNotBlank() }?.toTypedArray()?.takeIf { it.isNotEmpty() }
                    ?: arrayOf(ClipDescription.MIMETYPE_TEXT_URILIST)),
                ClipData.Item(Uri.parse(clip.text)),
            )
            else -> ClipData.newPlainText("SuperBoox", clip.text)
        }
        val set = runCatching { cm.setPrimaryClip(data) }.recoverCatching {
            // Android only lets BOOX re-share its own handwriting link (Tab Ultra C report: older
            // handwriting pasted as a text link). Put the copy's files back in BOOX's copy folder
            // and offer a link of our own with the same path. BOOX Notes turns a link into a
            // folder from its path alone when the link's authority contains "fileprovider"
            // (Note Air 6C, firmware 4.3), so it pastes this like a copy of its own.
            if (clip.kind != Kind.HANDWRITING || it !is SecurityException) throw it
            val restored = restoreHandwriting(context, clip)
            val mirror = Uri.parse(clip.text).path?.let { p -> Uri.parse("content://$NOTES_LINK_AUTHORITY$p") }
                ?: throw it
            DebugLog.i("clipboard", "handwriting: can't re-share BOOX's link; put back SuperBoox's link to the same folder ($restored)")
            cm.setPrimaryClip(ClipData(data.description, ClipData.Item(mirror)))
        }
        return set
            .onSuccess {
                setCurrent(context, clip.id)
                // Our own copy: no need to save it again when the clipboard is next read.
                save(context, listOf(clip.copy(time = System.currentTimeMillis())) + list(context).filterNot { it.id == clip.id })
                main.postDelayed({
                    runCatching {
                        val d = cm.primaryClipDescription
                        if (d != null && Build.VERSION.SDK_INT >= 26) sp(context).edit().putLong(K_STAMP, d.timestamp).apply()
                    }
                }, 200L)
            }
            .onFailure { DebugLog.w("clipboard", "couldn't set the clipboard: ${it.message}") }
            .isSuccess
    }

    fun contentUri(clip: Clip): Uri? = clip.image?.let { Uri.parse("content://$AUTHORITY/$it") }
}

/** Serves kept clipboard images to the apps they're pasted or shared into. */
class ClipProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File? {
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        return File(ClipHistory.dir(context ?: return null), name).takeIf { it.exists() }
    }

    // Only kept images are files here; a handwriting link (BOOX's folder path) has no type.
    override fun getType(uri: Uri): String? = file(uri)?.let { "image/png" }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
        file(uri)?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? {
        val f = file(uri) ?: return null
        return android.database.MatrixCursor(arrayOf(android.provider.OpenableColumns.DISPLAY_NAME, android.provider.OpenableColumns.SIZE)).apply {
            addRow(arrayOf<Any>(f.name, f.length()))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
}
