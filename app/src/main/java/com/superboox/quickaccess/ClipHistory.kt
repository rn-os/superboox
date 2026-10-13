package com.superboox.quickaccess

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
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
                    val preview = notesCopyDir(uri)?.let { runCatching { handwritingPreview(context, it) }.onFailure { e -> DebugLog.w("clipboard", "handwriting preview failed: ${e.message}") }.getOrNull() }
                    main.post { add(context, prefs, Kind.HANDWRITING, uri.toString(), preview, source, label, mimes.joinToString(",")) }
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

    /** Handwriting with kept files but no picture yet, and not already tried. */
    fun needsDrawing(context: Context, clip: Clip): Boolean {
        val folder = clip.image?.takeIf { clip.kind == Kind.HANDWRITING && !it.endsWith(".png") } ?: return false
        return File(dir(context), folder).isDirectory && !File(dir(context), "$folder.png").exists() &&
            !File(File(dir(context), folder), "drawn-failed-2").exists()
    }

    /** Draws [clip]'s handwriting to its picture (once; off the main thread). [ensurePreview] then uses it. */
    fun draw(context: Context, clip: Clip) {
        if (!needsDrawing(context, clip)) return
        val folder = clip.image ?: return
        val keep = File(dir(context), folder)
        if (drawStrokes(keep, "$folder.png", context) == null) runCatching { File(keep, "drawn-failed-2").createNewFile() }
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
        val same = if (kind == Kind.IMAGE) null else all.firstOrNull { it.kind != Kind.IMAGE && it.text == text }
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
     * Puts an older handwriting copy's files back into BOOX's copy folder, if BOOX has since
     * removed them (needs All files access). Returns what happened, for the log.
     */
    private fun restoreHandwriting(context: Context, clip: Clip): String {
        val folder = notesCopyDir(Uri.parse(clip.text)) ?: return "not a BOOX copy folder"
        val target = File(folder)
        if (target.isDirectory && target.list()?.isNotEmpty() == true) return "BOOX's folder still there"
        if (!hasFileAccess()) return "BOOX's folder is gone; restoring it needs All files access"
        val keep = clip.image?.removeSuffix(".png")?.let { File(dir(context), it) }?.takeIf { it.isDirectory }
            ?: return "no kept copy of the strokes"
        return runCatching {
            target.mkdirs()
            keep.listFiles().orEmpty().filter { it.isFile && !it.name.startsWith("drawn-failed") }.forEach { f ->
                f.copyTo(File(target, f.name), overwrite = true)
            }
            "restored BOOX's folder"
        }.getOrElse { e -> "restoring BOOX's folder failed: ${e.message}" }
    }

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
            // handwriting pasted as a text link). Put BOOX's copy folder back if it's gone, and
            // offer a link of our own with the same path, which BOOX may read the folder from.
            if (clip.kind != Kind.HANDWRITING || it !is SecurityException) throw it
            val restored = restoreHandwriting(context, clip)
            val mirror = Uri.parse(clip.text).path?.let { p -> Uri.parse("content://$AUTHORITY$p") }
                ?: throw it
            DebugLog.i("clipboard", "handwriting: can't re-share BOOX's link; putting back SuperBoox's link to the same folder ($restored)")
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
