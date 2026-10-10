package com.superboox.quickaccess

import android.os.SystemClock
import java.util.zip.ZipFile

/**
 * Lists the string constants used by specific classes inside another app's
 * APK, to learn which intent extras an exported activity reads (constants
 * passed to getStringExtra are inlined as const-string instructions).
 *
 * A minimal DEX reader: header → string/type ids → class defs → class data →
 * code items → const-string / const-string/jumbo operands. No code is run or
 * loaded, the APK is only read. Used by the debug report, time-boxed.
 */
object DexProbe {

    /** For each class whose descriptor starts with one of [prefixes], the strings its code references. */
    fun classStrings(apkPath: String, prefixes: List<String>, deadline: Long, maxPerClass: Int = 60): Map<String, List<String>> {
        val out = LinkedHashMap<String, List<String>>()
        ZipFile(apkPath).use { zip ->
            val dexes = zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.toList()
            for (entry in dexes) {
                if (SystemClock.uptimeMillis() > deadline) break
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                runCatching { scanDex(bytes, prefixes, maxPerClass, deadline, out) }
            }
        }
        return out
    }

    private fun scanDex(d: ByteArray, prefixes: List<String>, maxPerClass: Int, deadline: Long, out: MutableMap<String, List<String>>) {
        val stringIdsSize = u32(d, 0x38)
        val stringIdsOff = u32(d, 0x3C)
        val typeIdsSize = u32(d, 0x40)
        val typeIdsOff = u32(d, 0x44)
        val classDefsSize = u32(d, 0x60)
        val classDefsOff = u32(d, 0x64)

        fun string(idx: Int): String {
            val off = u32(d, stringIdsOff + idx * 4)
            var p = off
            // uleb128 utf16 length, then MUTF-8 bytes up to a 0 terminator.
            while (d[p].toInt() and 0x80 != 0) p++
            p++
            var end = p
            while (d[end].toInt() != 0) end++
            return String(d, p, end - p, Charsets.UTF_8)
        }

        fun typeName(typeIdx: Int): String = string(u32(d, typeIdsOff + typeIdx * 4))

        for (c in 0 until classDefsSize) {
            if (SystemClock.uptimeMillis() > deadline) return
            val def = classDefsOff + c * 32
            val name = typeName(u32(d, def))
            if (prefixes.none { name.startsWith(it) }) continue
            val classDataOff = u32(d, def + 24)
            if (classDataOff == 0) continue
            val strings = LinkedHashSet<String>()
            for (codeOff in codeOffsets(d, classDataOff)) {
                val insnsSize = u32(d, codeOff + 12)
                var pc = codeOff + 16
                val end = pc + insnsSize * 2
                while (pc < end && strings.size < maxPerClass) {
                    val op = d[pc].toInt() and 0xFF
                    when (op) {
                        0x1a -> strings += safeString(::string, u16(d, pc + 2), stringIdsSize)
                        0x1b -> strings += safeString(::string, u32(d, pc + 2), stringIdsSize)
                    }
                    pc += 2 * insnUnits(d, pc, op)
                }
            }
            out[name.removePrefix("L").removeSuffix(";").replace('/', '.')] = strings.filter { it.isNotBlank() }
        }
    }

    private fun safeString(read: (Int) -> String, idx: Int, size: Int): String =
        if (idx in 0 until size) runCatching { read(idx) }.getOrDefault("") else ""

    /** Code item offsets of all direct and virtual methods in a class_data_item. */
    private fun codeOffsets(d: ByteArray, start: Int): List<Int> {
        val r = Uleb(d, start)
        val staticFields = r.next()
        val instanceFields = r.next()
        val directMethods = r.next()
        val virtualMethods = r.next()
        repeat(staticFields + instanceFields) { r.next(); r.next() }
        val out = ArrayList<Int>()
        repeat(directMethods + virtualMethods) {
            r.next() // method_idx_diff
            r.next() // access_flags
            val code = r.next()
            if (code != 0) out += code
        }
        return out
    }

    /** Instruction length in 16-bit code units, including the payload pseudo-instructions. */
    private fun insnUnits(d: ByteArray, pc: Int, op: Int): Int {
        if (op == 0x00) {
            return when (d[pc + 1].toInt() and 0xFF) {
                0x01 -> 4 + u16(d, pc + 2) * 2 // packed-switch-payload
                0x02 -> 2 + u16(d, pc + 2) * 4 // sparse-switch-payload
                0x03 -> 4 + (u16(d, pc + 2) * u32(d, pc + 4) + 1) / 2 // fill-array-data-payload
                else -> 1
            }
        }
        return FORMAT_UNITS[op]
    }

    /** Code units per opcode (Dalvik formats). */
    private val FORMAT_UNITS: IntArray = IntArray(256) { 1 }.also { u ->
        fun set(units: Int, vararg ops: Int) = ops.forEach { u[it] = units }
        fun range(units: Int, from: Int, to: Int) = (from..to).forEach { u[it] = units }
        set(2, 0x02, 0x05, 0x08, 0x13, 0x15, 0x16, 0x19, 0x1a, 0x1c, 0x1f, 0x20, 0x22, 0x23, 0x29)
        set(3, 0x03, 0x06, 0x09, 0x14, 0x17, 0x1b, 0x24, 0x25, 0x26, 0x2a, 0x2b, 0x2c)
        set(5, 0x18)
        range(2, 0x2d, 0x31) // cmp*
        range(2, 0x32, 0x37) // if-test
        range(2, 0x38, 0x3d) // if-testz
        range(2, 0x44, 0x51) // aget/aput
        range(2, 0x52, 0x5f) // iget/iput
        range(2, 0x60, 0x6d) // sget/sput
        range(3, 0x6e, 0x72) // invoke-kind
        range(3, 0x74, 0x78) // invoke-kind/range
        range(2, 0x90, 0xaf) // binop
        range(2, 0xd0, 0xd7) // binop/lit16
        range(2, 0xd8, 0xe2) // binop/lit8
        set(4, 0xfa, 0xfb) // invoke-polymorphic(/range)
        set(3, 0xfc, 0xfd) // invoke-custom(/range)
        set(2, 0xfe, 0xff) // const-method-handle/type
    }

    private fun u16(d: ByteArray, o: Int) = (d[o].toInt() and 0xFF) or ((d[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(d: ByteArray, o: Int) = u16(d, o) or (u16(d, o + 2) shl 16)

    private class Uleb(val d: ByteArray, var p: Int) {
        fun next(): Int {
            var result = 0
            var shift = 0
            while (true) {
                val b = d[p++].toInt() and 0xFF
                result = result or ((b and 0x7F) shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }
    }
}
