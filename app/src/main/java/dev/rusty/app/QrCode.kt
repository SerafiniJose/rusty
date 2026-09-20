package dev.rusty.app

/**
 * A QR code (ISO/IEC 18004) for short text — the control page's URL — with no library: the
 * build runs offline and an unshrunk ZXing jar would add half a megabyte to the APK for one
 * dialog. Byte mode, error-correction level M, versions 1–10 (up to 213 bytes), mask chosen by
 * the standard penalty score. [get] answers "is this module dark?"; rendering is [QrBitmap]'s.
 *
 * Verified by [QrCodeTest]'s independent read-back (mask, placement, interleave and Reed-Solomon
 * syndromes), not by matching another encoder's output.
 */
class QrCode private constructor(val version: Int, private val modules: Array<BooleanArray>) {

    val size: Int get() = modules.size

    /** True when the module at column [x], row [y] is dark. */
    operator fun get(x: Int, y: Int): Boolean = modules[y][x]

    companion object {
        private const val MAX_VERSION = 10

        /** Per version (index = version), level M: total codewords, EC codewords per block,
         *  then the blocks as (count, dataCodewords) pairs — ISO 18004 table 9. */
        private class Spec(val total: Int, val ecPerBlock: Int, val blocks: List<Pair<Int, Int>>) {
            val dataCodewords: Int get() = blocks.sumOf { it.first * it.second }
        }

        private val SPECS = arrayOf(
            null,
            Spec(26, 10, listOf(1 to 16)),
            Spec(44, 16, listOf(1 to 28)),
            Spec(70, 26, listOf(1 to 44)),
            Spec(100, 18, listOf(2 to 32)),
            Spec(134, 24, listOf(2 to 43)),
            Spec(172, 16, listOf(4 to 27)),
            Spec(196, 18, listOf(4 to 31)),
            Spec(242, 22, listOf(2 to 38, 2 to 39)),
            Spec(292, 22, listOf(3 to 36, 2 to 37)),
            Spec(346, 26, listOf(4 to 43, 1 to 44)),
        )

        /** Alignment pattern centre coordinates per version (both axes). */
        private val ALIGNMENT = arrayOf(
            intArrayOf(), intArrayOf(), intArrayOf(6, 18), intArrayOf(6, 22), intArrayOf(6, 26),
            intArrayOf(6, 30), intArrayOf(6, 34), intArrayOf(6, 22, 38), intArrayOf(6, 24, 42),
            intArrayOf(6, 26, 46), intArrayOf(6, 28, 50),
        )

        fun encode(text: String): QrCode = encodeBytes(text.toByteArray(Charsets.UTF_8))

        fun encodeBytes(data: ByteArray): QrCode {
            val version = (1..MAX_VERSION).firstOrNull { v -> data.size <= capacity(v) }
                ?: throw IllegalArgumentException("${data.size} bytes exceed QR version $MAX_VERSION at level M")
            val spec = SPECS[version]!!
            val codewords = interleave(dataCodewords(data, version, spec), spec)
            val size = version * 4 + 17
            val function = Array(size) { BooleanArray(size) }
            val modules = Array(size) { BooleanArray(size) }
            drawFunctionPatterns(version, size, modules, function)
            placeData(codewords, size, modules, function)
            // Try every mask on the data region and keep the one the penalty rules like best.
            var best = 0
            var bestScore = Int.MAX_VALUE
            for (mask in 0..7) {
                applyMask(mask, size, modules, function)
                drawFormat(mask, size, modules, function)
                val score = penalty(size, modules)
                if (score < bestScore) { bestScore = score; best = mask }
                applyMask(mask, size, modules, function) // XOR twice = undo
            }
            applyMask(best, size, modules, function)
            drawFormat(best, size, modules, function)
            return QrCode(version, modules)
        }

        /** Byte-mode payload capacity: data codewords minus the mode/count header. */
        private fun capacity(version: Int): Int = SPECS[version]!!.dataCodewords - if (version <= 9) 2 else 3

        // ---- data ---------------------------------------------------------------------------------

        private fun dataCodewords(data: ByteArray, version: Int, spec: Spec): ByteArray {
            val bits = BitWriter()
            bits.write(0b0100, 4)
            bits.write(data.size, if (version <= 9) 8 else 16)
            for (b in data) bits.write(b.toInt() and 0xFF, 8)
            val capacityBits = spec.dataCodewords * 8
            bits.write(0, minOf(4, capacityBits - bits.length)) // terminator
            bits.write(0, (8 - bits.length % 8) % 8)               // byte-align
            var pad = 0xEC
            while (bits.length < capacityBits) { bits.write(pad, 8); pad = if (pad == 0xEC) 0x11 else 0xEC }
            return bits.toByteArray()
        }

        private fun interleave(data: ByteArray, spec: Spec): ByteArray {
            val lengths = spec.blocks.flatMap { (count, len) -> List(count) { len } }
            val dataBlocks = ArrayList<ByteArray>()
            var p = 0
            for (len in lengths) { dataBlocks.add(data.copyOfRange(p, p + len)); p += len }
            val ecBlocks = dataBlocks.map { ReedSolomon.remainder(it, spec.ecPerBlock) }
            val out = ByteArray(spec.total)
            var o = 0
            for (i in 0 until lengths.max()) for (blk in dataBlocks) if (i < blk.size) out[o++] = blk[i]
            for (i in 0 until spec.ecPerBlock) for (blk in ecBlocks) out[o++] = blk[i]
            return out
        }

        // ---- function patterns ------------------------------------------------------------------

        private fun drawFunctionPatterns(version: Int, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; f[y][x] = true }
            // Timing.
            for (i in 0 until size) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0) }
            // Finders with separators.
            for ((ox, oy) in listOf(0 to 0, size - 7 to 0, 0 to size - 7)) {
                for (dy in -1..7) for (dx in -1..7) {
                    val x = ox + dx; val y = oy + dy
                    if (x !in 0 until size || y !in 0 until size) continue
                    val ring = maxOf(Math.abs(dx - 3), Math.abs(dy - 3))
                    set(x, y, ring != 2 && ring != 4)
                }
            }
            // Alignment patterns, skipping the three that would sit on a finder.
            val centres = ALIGNMENT[version]
            for (cx in centres) for (cy in centres) {
                val onFinder = (cx <= 8 && cy <= 8) || (cx >= size - 9 && cy <= 8) || (cx <= 8 && cy >= size - 9)
                if (onFinder) continue
                for (dy in -2..2) for (dx in -2..2) set(cx + dx, cy + dy, maxOf(Math.abs(dx), Math.abs(dy)) != 1)
            }
            // Format areas are reserved now (their bits come with the mask); dark module is fixed.
            for (i in 0 until 9) { f[8][i] = true; f[i][8] = true }
            for (i in 0 until 8) { f[8][size - 1 - i] = true; f[size - 1 - i][8] = true }
            set(8, size - 8, true)
            // Version information, versions 7+.
            if (version >= 7) {
                var rem = version
                for (i in 0 until 12) rem = (rem shl 1) xor ((rem ushr 11) * 0x1F25)
                val word = (version shl 12) or rem
                for (i in 0 until 18) {
                    val bit = (word ushr i) and 1 == 1
                    set(i / 3, size - 11 + i % 3, bit)
                    set(size - 11 + i % 3, i / 3, bit)
                }
            }
        }

        private fun drawFormat(mask: Int, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            val data = (0b00 shl 3) or mask // level M = 00
            var rem = data
            for (i in 0 until 10) rem = (rem shl 1) xor ((rem ushr 9) * 0x537)
            val bits = ((data shl 10) or rem) xor 0b101010000010010
            fun bit(i: Int) = (bits ushr i) and 1 == 1
            fun set(x: Int, y: Int, dark: Boolean) { m[y][x] = dark; f[y][x] = true }
            // First copy, around the top-left finder.
            for (i in 0..5) set(8, i, bit(i))
            set(8, 7, bit(6)); set(8, 8, bit(7)); set(7, 8, bit(8))
            for (i in 9..14) set(14 - i, 8, bit(i))
            // Second copy, split between the other two finders.
            for (i in 0..7) set(size - 1 - i, 8, bit(i))
            for (i in 8..14) set(8, size - 15 + i, bit(i))
        }

        // ---- data placement and masking ---------------------------------------------------------

        private fun placeData(codewords: ByteArray, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            var i = 0
            val total = codewords.size * 8
            var right = size - 1
            var upward = true
            while (right >= 1) {
                if (right == 6) right = 5
                for (vert in 0 until size) {
                    val y = if (upward) size - 1 - vert else vert
                    for (j in 0..1) {
                        val x = right - j
                        if (f[y][x]) continue
                        m[y][x] = i < total && (codewords[i ushr 3].toInt() ushr (7 - (i and 7))) and 1 == 1
                        i++ // remainder bits past `total` stay light
                    }
                }
                upward = !upward
                right -= 2
            }
        }

        private fun applyMask(mask: Int, size: Int, m: Array<BooleanArray>, f: Array<BooleanArray>) {
            for (y in 0 until size) for (x in 0 until size) {
                if (f[y][x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (y / 2 + x / 3) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                }
                if (invert) m[y][x] = !m[y][x]
            }
        }

        /** The four penalty rules of ISO 18004 §7.8.3. Lower is better. */
        private fun penalty(size: Int, m: Array<BooleanArray>): Int {
            var score = 0
            // Rule 1: runs of 5+ same-colour modules in a row/column.
            for (pass in 0..1) for (a in 0 until size) {
                var run = 0
                var prev = false
                for (b in 0 until size) {
                    val v = if (pass == 0) m[a][b] else m[b][a]
                    if (v == prev) { run++; if (run == 5) score += 3 else if (run > 5) score++ } else { prev = v; run = 1 }
                }
            }
            // Rule 2: 2×2 blocks of one colour.
            for (y in 0 until size - 1) for (x in 0 until size - 1) {
                val v = m[y][x]
                if (v == m[y][x + 1] && v == m[y + 1][x] && v == m[y + 1][x + 1]) score += 3
            }
            // Rule 3: finder-like 1:1:3:1:1 runs with 4 light modules on either side.
            val pat = booleanArrayOf(true, false, true, true, true, false, true)
            for (pass in 0..1) for (a in 0 until size) for (b in 0 until size - 6) {
                fun at(k: Int) = if (pass == 0) m[a][k] else m[k][a]
                var match = true
                for (k in 0 until 7) if (at(b + k) != pat[k]) { match = false; break }
                if (!match) continue
                val lightBefore = b >= 4 && (0 until 4).all { !at(b - 1 - it) }
                val lightAfter = b + 10 < size && (0 until 4).all { !at(b + 7 + it) }
                if (lightBefore || lightAfter) score += 40
            }
            // Rule 4: dark-module proportion away from 50%.
            var dark = 0
            for (row in m) for (v in row) if (v) dark++
            val percent = dark * 100 / (size * size)
            score += (Math.abs(percent - 50) / 5) * 10
            return score
        }
    }

    private class BitWriter {
        private val bits = ArrayList<Boolean>()
        val length: Int get() = bits.size
        fun write(value: Int, count: Int) { for (i in count - 1 downTo 0) bits.add((value ushr i) and 1 == 1) }
        fun toByteArray(): ByteArray = ByteArray(bits.size / 8) { i ->
            (0 until 8).fold(0) { acc, k -> (acc shl 1) or (if (bits[i * 8 + k]) 1 else 0) }.toByte()
        }
    }

    /** GF(2^8) with the QR primitive polynomial 0x11D; generator roots α^0 … α^(n−1). */
    private object ReedSolomon {
        private val exp = IntArray(512)
        private val log = IntArray(256)
        init {
            var v = 1
            for (i in 0 until 255) { exp[i] = v; log[v] = i; v = v shl 1; if (v and 0x100 != 0) v = v xor 0x11D }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }
        private fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]

        private fun generator(degree: Int): IntArray {
            var g = intArrayOf(1)
            for (i in 0 until degree) {
                val next = IntArray(g.size + 1)
                for (j in g.indices) { next[j] = next[j] xor g[j]; next[j + 1] = next[j + 1] xor mul(g[j], exp[i]) }
                g = next
            }
            return g
        }

        fun remainder(data: ByteArray, degree: Int): ByteArray {
            val g = generator(degree)
            val r = IntArray(degree)
            for (b in data) {
                val factor = (b.toInt() and 0xFF) xor r[0]
                System.arraycopy(r, 1, r, 0, degree - 1)
                r[degree - 1] = 0
                for (j in 0 until degree) r[j] = r[j] xor mul(g[j + 1], factor)
            }
            return ByteArray(degree) { r[it].toByte() }
        }
    }
}
