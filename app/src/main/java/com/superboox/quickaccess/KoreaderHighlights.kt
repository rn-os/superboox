package com.superboox.quickaccess

import android.os.Environment
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Highlights from KOReader, read (never changed) from its per-book files.
 *
 * KOReader keeps each book's settings in a `.sdr` folder, as `metadata.<ext>.lua`: next to the
 * book by default, or under koreader/docsettings (mirroring the book's path) or
 * koreader/hashdocsettings. Newer versions list highlights under `["annotations"]` (with the
 * note, page and chapter on each); older ones under `["highlight"]`, keyed by page. Page
 * bookmarks have no `pos0` and are left out.
 */
object KoreaderHighlights {

    val PACKAGES = listOf("org.koreader.launcher", "org.koreader.launcher.fdroid")

    class Found(
        val sidecar: File,
        val title: String,
        val author: String?,
        val bookPath: String?,
        val text: String,
        val note: String,
        val page: Int?,
        val chapter: String?,
        val time: Long,
        /** Stable across rescans: the book file plus where the highlight starts. */
        val key: String,
    )

    fun installed(context: android.content.Context): String? =
        PACKAGES.firstOrNull { p -> runCatching { context.packageManager.getPackageInfo(p, 0) }.isSuccess }

    // ---- Finding the per-book files ----------------------------------------------------------

    /**
     * Every `metadata.*.lua` under shared storage. Breadth-first, skipping Android/ and hidden
     * folders (other than `.sdr` ones), and giving up after [budgetMs] so it never hangs.
     */
    fun findSidecars(budgetMs: Long = 8_000L, maxDirs: Int = 30_000): List<File> {
        val storage = Environment.getExternalStorageDirectory()
        val started = SystemClock.uptimeMillis()
        val out = ArrayList<File>()
        val queue = ArrayDeque<File>().apply { add(storage) }
        var dirs = 0
        while (queue.isNotEmpty() && SystemClock.uptimeMillis() - started < budgetMs && dirs < maxDirs) {
            val dir = queue.removeFirst()
            dirs++
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isDirectory) continue
                when {
                    f.name.endsWith(".sdr") -> f.listFiles().orEmpty()
                        .filter { it.isFile && it.name.startsWith("metadata.") && it.name.endsWith(".lua") && !it.name.endsWith(".old") }
                        .forEach { out += it }
                    // koreader/ itself is searched: docsettings and hashdocsettings live there.
                    f.name.startsWith(".") || (dir == storage && f.name == "Android") -> Unit
                    else -> queue.addLast(f)
                }
            }
        }
        return out
    }

    // ---- Reading one book --------------------------------------------------------------------

    fun read(sidecar: File): List<Found> {
        val root = Lua.parse(sidecar.readBytes()) as? Map<*, *> ?: return emptyList()
        val props = root["doc_props"] as? Map<*, *>
        val bookPath = (root["doc_path"] as? String) ?: guessBookPath(sidecar)
        val title = (props?.get("title") as? String)?.takeIf { it.isNotBlank() }
            ?: bookPath?.let { File(it).nameWithoutExtension }
            ?: sidecar.parentFile?.name?.removeSuffix(".sdr").orEmpty()
        val author = (props?.get("authors") as? String)?.replace('\n', ',')?.takeIf { it.isNotBlank() }
        val keyBase = bookPath ?: sidecar.path
        fun found(e: Map<*, *>, pageKey: Int?): Found? {
            val pos0 = e["pos0"] ?: return null // a page bookmark, not a highlight
            val text = (e["text"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val page = (e["pageno"] as? Number)?.toInt() ?: (e["page"] as? Number)?.toInt() ?: pageKey
            return Found(
                sidecar = sidecar,
                title = title,
                author = author,
                bookPath = bookPath,
                text = text,
                note = (e["note"] as? String)?.trim().orEmpty(),
                page = page,
                chapter = (e["chapter"] as? String)?.trim()?.takeIf { it.isNotEmpty() },
                time = parseTime(e["datetime"] as? String),
                key = keyBase + "|" + pos0.toString(),
            )
        }
        val out = ArrayList<Found>()
        val annotations = root["annotations"] as? Map<*, *>
        if (annotations != null) {
            annotations.values.forEach { v -> (v as? Map<*, *>)?.let { found(it, null) }?.let { out += it } }
        } else {
            // Older KOReader: { [page] = { [1] = { text, chapter, datetime, pos0, … } } }
            (root["highlight"] as? Map<*, *>)?.forEach { (page, list) ->
                (list as? Map<*, *>)?.values?.forEach { v -> (v as? Map<*, *>)?.let { found(it, (page as? Number)?.toInt()) }?.let { out += it } }
            }
        }
        return out
    }

    /** Where the book is, when the per-book file doesn't say (older KOReader). */
    private fun guessBookPath(sidecar: File): String? {
        val sdr = sidecar.parentFile ?: return null
        val ext = sidecar.name.removePrefix("metadata.").removeSuffix(".lua")
        val name = sdr.name.removeSuffix(".sdr") + "." + ext
        val marker = "/koreader/docsettings/"
        val i = sdr.path.indexOf(marker)
        return when {
            sdr.path.contains("/koreader/hashdocsettings/") -> null
            i >= 0 -> "/" + sdr.parent!!.substring(i + marker.length).trimStart('/') + "/" + name
            else -> File(sdr.parentFile, name).path
        }
    }

    private fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0L
        return runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s.trim())?.time }.getOrNull() ?: 0L
    }

    // ---- A small reader for KOReader's Lua tables --------------------------------------------

    /**
     * Reads `return { … }` as written by KOReader's settings dump: tables with `["key"] =` or
     * `[number] =` entries (and plain list items), strings, numbers, booleans and nil. Tables
     * become maps whose keys are Strings or Longs. Works on bytes so UTF-8 survives `\ddd`.
     */
    object Lua {
        fun parse(bytes: ByteArray): Any? = Parser(bytes).run {
            skip()
            if (word("return")) skip()
            value()
        }

        private class Parser(val b: ByteArray) {
            var i = 0

            fun skip() {
                while (i < b.size) {
                    val c = b[i].toInt().toChar()
                    when {
                        c.isWhitespace() || c == ';' -> i++
                        c == '-' && i + 1 < b.size && b[i + 1].toInt().toChar() == '-' -> {
                            i += 2
                            if (i + 1 < b.size && b[i].toInt().toChar() == '[' && b[i + 1].toInt().toChar() == '[') {
                                val end = find("]]".toByteArray(), i)
                                i = if (end < 0) b.size else end + 2
                            } else {
                                while (i < b.size && b[i].toInt() != '\n'.code) i++
                            }
                        }
                        else -> return
                    }
                }
            }

            private fun find(what: ByteArray, from: Int): Int {
                var j = from
                while (j + what.size <= b.size) {
                    if ((what.indices).all { b[j + it] == what[it] }) return j
                    j++
                }
                return -1
            }

            fun word(w: String): Boolean {
                val bytes = w.toByteArray()
                if (i + bytes.size > b.size) return false
                for (k in bytes.indices) if (b[i + k] != bytes[k]) return false
                val next = if (i + bytes.size < b.size) b[i + bytes.size].toInt().toChar() else ' '
                if (next.isLetterOrDigit() || next == '_') return false
                i += bytes.size
                return true
            }

            fun value(): Any? {
                skip()
                if (i >= b.size) return null
                val c = b[i].toInt().toChar()
                return when {
                    c == '{' -> table()
                    c == '"' || c == '\'' -> string(c)
                    c == '[' && i + 1 < b.size && (b[i + 1].toInt().toChar() == '[' || b[i + 1].toInt().toChar() == '=') -> longString()
                    word("true") -> true
                    word("false") -> false
                    word("nil") -> null
                    else -> number()
                }
            }

            private fun table(): Map<Any, Any?> {
                i++ // {
                val out = LinkedHashMap<Any, Any?>()
                var n = 1L
                while (true) {
                    skip()
                    if (i >= b.size) return out
                    val c = b[i].toInt().toChar()
                    if (c == '}') { i++; return out }
                    if (c == ',') { i++; continue }
                    if (c == '[' && i + 1 < b.size && b[i + 1].toInt().toChar() != '[' && b[i + 1].toInt().toChar() != '=') {
                        i++
                        val key = value()
                        skip()
                        if (i < b.size && b[i].toInt().toChar() == ']') i++
                        skip()
                        if (i < b.size && b[i].toInt().toChar() == '=') i++
                        val v = value()
                        val k: Any? = when (key) {
                            is Double -> if (key == Math.floor(key)) key.toLong() else key
                            else -> key
                        }
                        if (k != null) out[k] = v
                    } else if (c.isLetter() || c == '_') {
                        // name = value
                        val start = i
                        while (i < b.size && (b[i].toInt().toChar().isLetterOrDigit() || b[i].toInt().toChar() == '_')) i++
                        val name = String(b, start, i - start)
                        skip()
                        if (i < b.size && b[i].toInt().toChar() == '=') {
                            i++
                            out[name] = value()
                        } else {
                            out[n++] = when (name) { "true" -> true; "false" -> false; else -> null }
                        }
                    } else {
                        out[n++] = value()
                    }
                }
            }

            private fun string(q: Char): String {
                i++
                val buf = java.io.ByteArrayOutputStream()
                while (i < b.size) {
                    val c = b[i].toInt().toChar()
                    if (c == q) { i++; break }
                    if (c != '\\') { buf.write(b[i].toInt()); i++; continue }
                    i++
                    if (i >= b.size) break
                    when (val e = b[i].toInt().toChar()) {
                        'n' -> { buf.write('\n'.code); i++ }
                        't' -> { buf.write('\t'.code); i++ }
                        'r' -> { buf.write('\r'.code); i++ }
                        'a' -> { buf.write(7); i++ }
                        'b' -> { buf.write(8); i++ }
                        'f' -> { buf.write(12); i++ }
                        'v' -> { buf.write(11); i++ }
                        '\n' -> { buf.write('\n'.code); i++ }
                        '\r' -> { buf.write('\n'.code); i++; if (i < b.size && b[i].toInt() == '\n'.code) i++ }
                        'x' -> {
                            i++
                            val hex = String(b, i, minOf(2, b.size - i))
                            buf.write(hex.toIntOrNull(16) ?: 0)
                            i += 2
                        }
                        'z' -> { i++; while (i < b.size && b[i].toInt().toChar().isWhitespace()) i++ }
                        'u' -> {
                            // \u{XXX}
                            val close = find("}".toByteArray(), i)
                            val cp = if (close > i + 1) String(b, i + 2, close - i - 2).toIntOrNull(16) else null
                            if (cp != null) buf.write(String(Character.toChars(cp)).toByteArray())
                            i = if (close >= 0) close + 1 else i + 1
                        }
                        else -> if (e.isDigit()) {
                            var v = 0
                            var k = 0
                            while (k < 3 && i < b.size && b[i].toInt().toChar().isDigit()) {
                                v = v * 10 + (b[i] - '0'.code.toByte())
                                i++
                                k++
                            }
                            buf.write(v and 0xFF)
                        } else {
                            buf.write(b[i].toInt())
                            i++
                        }
                    }
                }
                return String(buf.toByteArray(), Charsets.UTF_8)
            }

            private fun longString(): String {
                // [[ … ]] or [==[ … ]==]
                var level = 0
                i++
                while (i < b.size && b[i].toInt().toChar() == '=') { level++; i++ }
                i++ // second [
                if (i < b.size && b[i].toInt() == '\n'.code) i++
                val close = ("]" + "=".repeat(level) + "]").toByteArray()
                val end = find(close, i)
                val s = String(b, i, (if (end < 0) b.size else end) - i, Charsets.UTF_8)
                i = if (end < 0) b.size else end + close.size
                return s
            }

            private fun number(): Any? {
                val start = i
                while (i < b.size) {
                    val c = b[i].toInt().toChar()
                    if (c.isLetterOrDigit() || c == '.' || c == '-' || c == '+') i++ else break
                }
                if (i == start) { i++; return null } // unknown character: step over it
                val s = String(b, start, i - start)
                return s.toDoubleOrNull()
                    ?: s.removePrefix("0x").takeIf { s.startsWith("0x") }?.toLongOrNull(16)?.toDouble()
            }
        }
    }
}
