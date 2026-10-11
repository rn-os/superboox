package com.superboox.quickaccess

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws handwriting copied with BOOX Notes' lasso from its `shape.pb` file
 * (protocol buffers, build 56 report): one message per shape holding its id,
 * times, colour, its bounds as JSON ({"left","top","right","bottom"}) and its
 * points. The point layout isn't documented, so it's found by looking for an
 * array of floats whose pairs fall inside the shape's bounds; [describe] logs
 * the structure when that fails, to make it exact.
 */
object BooxShapes {
    private class Field(val num: Int, val type: Int, val varint: Long, val bytes: ByteArray?)

    private fun parse(b: ByteArray): List<Field>? {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long? {
            var r = 0L
            var shift = 0
            while (i < b.size && shift < 64) {
                val c = b[i++].toInt() and 0xff
                r = r or ((c and 0x7f).toLong() shl shift)
                if (c and 0x80 == 0) return r
                shift += 7
            }
            return null
        }
        while (i < b.size) {
            val key = varint() ?: return null
            val num = (key ushr 3).toInt()
            val type = (key and 7).toInt()
            if (num <= 0) return null
            when (type) {
                0 -> out += Field(num, 0, varint() ?: return null, null)
                1 -> { if (i + 8 > b.size) return null; out += Field(num, 1, 0, b.copyOfRange(i, i + 8)); i += 8 }
                2 -> {
                    val len = varint()?.toInt() ?: return null
                    if (len < 0 || i + len > b.size) return null
                    out += Field(num, 2, 0, b.copyOfRange(i, i + len)); i += len
                }
                5 -> { if (i + 4 > b.size) return null; out += Field(num, 5, 0, b.copyOfRange(i, i + 4)); i += 4 }
                else -> return null
            }
        }
        return out
    }

    private fun printable(b: ByteArray) = b.isNotEmpty() && b.all { it in 9..126 }

    private fun bounds(fields: List<Field>): RectF? = fields.firstNotNullOfOrNull { f ->
        val t = f.bytes?.takeIf(::printable)?.toString(Charsets.UTF_8) ?: return@firstNotNullOfOrNull null
        if (!t.startsWith("{") || !t.contains("\"left\"")) return@firstNotNullOfOrNull null
        runCatching {
            val o = JSONObject(t)
            RectF(o.getDouble("left").toFloat(), o.getDouble("top").toFloat(), o.getDouble("right").toFloat(), o.getDouble("bottom").toFloat())
        }.getOrNull()
    }

    private fun floats(b: ByteArray): FloatArray {
        val buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { buf.float }
    }

    private fun inflate(b: ByteArray): ByteArray? = runCatching {
        if (b.size < 2 || (b[0].toInt() and 0xff) != 0x78) return null
        java.util.zip.InflaterInputStream(b.inputStream()).readBytes()
    }.getOrNull()

    /** Points of one shape, in page coordinates, or null if none were recognised. */
    private fun points(fields: List<Field>, rect: RectF, depth: Int = 0): List<FloatArray>? {
        val slack = maxOf(rect.width(), rect.height()) * 0.1f + 4f
        val box = RectF(rect.left - slack, rect.top - slack, rect.right + slack, rect.bottom + slack)
        fun place(x: Float, y: Float, mode: Int): Pair<Float, Float> = when (mode) {
            0 -> x to y
            1 -> rect.left + x to rect.top + y
            else -> rect.left + x * rect.width() to rect.top + y * rect.height()
        }
        var best: List<FloatArray>? = null
        for (f in fields) {
            val raw = f.bytes ?: continue
            if (f.type != 2 || raw.size < 8 || printable(raw)) continue
            for (b in listOfNotNull(raw, inflate(raw))) {
                // A packed float array: try each stride and placement, keep the one landing inside the bounds.
                if (b.size % 4 == 0) {
                    val v = floats(b)
                    for (stride in 2..8) {
                        if (v.size % stride != 0 || v.size / stride < 2) continue
                        for (mode in 0..2) {
                            val pts = (0 until v.size / stride).map { k -> place(v[k * stride], v[k * stride + 1], mode) }
                            val inside = pts.count { (x, y) -> !x.isNaN() && box.contains(x, y) }
                            if (inside >= pts.size * 0.9 && (best == null || pts.size > best!!.size)) {
                                best = pts.map { floatArrayOf(it.first, it.second) }
                            }
                        }
                    }
                }
                // Or nested messages: points as repeated {x, y} messages, or deeper structure.
                if (depth < 4) parse(b)?.let { sub ->
                    val xy = sub.filter { it.type == 2 }.mapNotNull { m ->
                        val p = m.bytes?.let(::parse) ?: return@mapNotNull null
                        val fx = p.firstOrNull { it.num == 1 && it.type == 5 }?.bytes ?: return@mapNotNull null
                        val fy = p.firstOrNull { it.num == 2 && it.type == 5 }?.bytes ?: return@mapNotNull null
                        floats(fx)[0] to floats(fy)[0]
                    }
                    if (xy.size >= 2 && xy.count { (x, y) -> box.contains(x, y) } >= xy.size * 0.9 && (best == null || xy.size > best!!.size)) {
                        best = xy.map { floatArrayOf(it.first, it.second) }
                    }
                    points(sub, rect, depth + 1)?.let { if (best == null || it.size > best!!.size) best = it }
                }
            }
        }
        return best
    }

    /** The copied handwriting drawn black on white, at most [maxPx] on its longer side; null if it couldn't be read. */
    fun render(shapePb: ByteArray, maxPx: Int = 900): Bitmap? {
        val top = parse(shapePb)?.filter { it.type == 2 }?.mapNotNull { it.bytes?.let(::parse) } ?: return null
        var strokes = top.mapNotNull { s -> bounds(s)?.let { r -> points(s, r) } }
        if (strokes.isEmpty()) {
            // Points kept apart from the shapes: look in the other messages, inside all the shapes' bounds.
            val rects = top.mapNotNull(::bounds)
            if (rects.isEmpty()) return null
            val all = RectF(rects[0]).apply { rects.forEach { union(it) } }
            strokes = top.filter { bounds(it) == null }.mapNotNull { points(it, all) }
        }
        if (strokes.isEmpty()) return null
        val all = RectF(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        strokes.flatten().forEach { all.union(it[0], it[1]) }
        val pad = 8f
        val w = maxOf(all.width(), 1f)
        val h = maxOf(all.height(), 1f)
        val scale = minOf(maxPx / w, maxPx / h, 2f)
        val bmp = Bitmap.createBitmap((w * scale + pad * 2).toInt().coerceAtLeast(16), (h * scale + pad * 2).toInt().coerceAtLeast(16), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = (2.5f * scale).coerceIn(2f, 5f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (pts in strokes) {
            val path = Path()
            pts.forEachIndexed { k, p ->
                val x = (p[0] - all.left) * scale + pad
                val y = (p[1] - all.top) * scale + pad
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        return bmp
    }

    /** The first shape's structure, for the debug report when [render] can't find the points. */
    fun describe(shapePb: ByteArray): String {
        val top = parse(shapePb) ?: return "not readable as protocol buffers"
        val sb = StringBuilder("${top.size} top-level fields: " + top.take(12).joinToString { "${it.num}:${if (it.type == 2) "bytes ${it.bytes!!.size}" else "type ${it.type}"}" } + "; first shape:")
        fun walk(fields: List<Field>, prefix: String, depth: Int) {
            for (f in fields.take(40)) {
                val path = "$prefix${f.num}"
                when (f.type) {
                    0 -> sb.append("\n  $path varint ${f.varint}")
                    1, 5 -> sb.append("\n  $path fixed${if (f.type == 1) 64 else 32} ${if (f.type == 5) floats(f.bytes!!)[0] else ""}")
                    else -> {
                        val b = f.bytes!!
                        val sub = if (!printable(b) && depth < 3) parse(b) else null
                        sb.append("\n  $path bytes ${b.size}")
                        when {
                            printable(b) -> sb.append(" \"").append(String(b).take(120)).append('"')
                            b.size % 4 == 0 -> sb.append(" floats? ").append(floats(b).take(12).joinToString { "%.2f".format(it) })
                        }
                        if (sub != null && sub.isNotEmpty()) walk(sub, "$path.", depth + 1)
                    }
                }
            }
        }
        top.firstOrNull { it.type == 2 }?.bytes?.let(::parse)?.let { walk(it, "", 0) }
        return sb.toString()
    }
}
