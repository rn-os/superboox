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
    enum class Kind { TEXT, LINK, IMAGE }

    data class Clip(
        val id: String,
        val kind: Kind,
        val text: String,
        val image: String?,
        val source: String?,
        val time: Long,
        val pinned: Boolean,
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
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun save(context: Context, clips: List<Clip>) {
        val a = JSONArray()
        clips.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("k", c.kind.name).put("t", c.text).put("img", c.image ?: "")
                .put("src", c.source ?: "").put("time", c.time).put("pin", c.pinned))
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
        all.firstOrNull { it.id == id }?.let { c -> imageFile(context, c)?.delete() }
        save(context, all.filterNot { it.id == id })
        if (currentId(context) == id) setCurrent(context, null)
    }

    /** Clears everything but pinned clips. */
    fun clear(context: Context) {
        val (keep, drop) = list(context).partition { it.pinned }
        drop.forEach { imageFile(context, it)?.delete() }
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
        val text = (item.text ?: item.htmlText?.let { android.text.Html.fromHtml(it, 0) } ?: uri?.toString())?.toString()
        if (text.isNullOrBlank()) return
        add(context, prefs, if (isLink(text)) Kind.LINK else Kind.TEXT, text.take(20_000), null, source)
    }

    private fun add(context: Context, prefs: Prefs, kind: Kind, text: String, image: String?, source: String?) {
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
            val c = Clip("c$now", kind, text, image, source, now, pinned = false)
            DebugLog.i("clipboard", "saved a ${kind.name.lowercase()} clip from ${source ?: "?"}")
            listOf(c) + all
        }
        // Keep the newest unpinned clips up to the limit; pinned ones always stay.
        var unpinned = 0
        val kept = updated.filter { c -> c.pinned || ++unpinned <= prefs.clipLimit }
        updated.filterNot { it in kept }.forEach { imageFile(context, it)?.delete() }
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

    fun putBack(context: Context, clip: Clip): Boolean {
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return false
        val data = when (clip.kind) {
            Kind.IMAGE -> ClipData.newUri(context.contentResolver, "Image", contentUri(clip) ?: return false)
            else -> ClipData.newPlainText("SuperBoox", clip.text)
        }
        return runCatching { cm.setPrimaryClip(data) }
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

    override fun getType(uri: Uri): String = "image/png"

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
