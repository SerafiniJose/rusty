package dev.rusty.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [QrCode] against the parts of ISO 18004 a scanner relies on, WITHOUT a decoder library:
 * the fixed patterns by position, the format information by its BCH code, and — the real
 * oracle — a read-back of the data region through the declared mask, de-interleaved, whose
 * Reed-Solomon syndromes must all be zero and whose payload must be the input. A wrong
 * placement, mask, generator polynomial or interleave fails that last check.
 */
class QrCodeTest {

    private val url = "http://192.168.7.251:8765"

    // ---- size and determinism ---------------------------------------------------------------

    @Test fun aControlUrlFitsVersion2() {
        val qr = QrCode.encode(url)
        assertEquals(2, qr.version)
        assertEquals(25, qr.size)
    }

    @Test fun sizeFollowsTheVersion() {
        assertEquals(21, QrCode.encode("a").size)                        // v1: 14 bytes at M
        assertEquals(29, QrCode.encode("x".repeat(40)).size)             // v3: 44 bytes at M
        assertEquals(57, QrCode.encode("x".repeat(200)).size)            // v10: 213 bytes at M
    }

    @Test fun beyondVersion10Refuses() {
        assertTrue(runCatching { QrCode.encode("x".repeat(214)) }.isFailure)
    }

    @Test fun sameInputSameCode() {
        val a = QrCode.encode(url)
        val b = QrCode.encode(url)
        for (y in 0 until a.size) for (x in 0 until a.size) assertEquals(a[x, y], b[x, y])
    }

    // ---- fixed patterns ---------------------------------------------------------------------

    @Test fun finderPatternsSitInThreeCorners() {
        val qr = QrCode.encode(url)
        val n = qr.size
        listOf(0 to 0, n - 7 to 0, 0 to n - 7).forEach { (ox, oy) ->
            for (dy in 0 until 7) for (dx in 0 until 7) {
                val edge = dx == 0 || dx == 6 || dy == 0 || dy == 6
                val core = dx in 2..4 && dy in 2..4
                assertEquals("finder at ($ox,$oy) module ($dx,$dy)", edge || core, qr[ox + dx, oy + dy])
            }
        }
        // Separators: the light ring around each finder.
        for (i in 0 until 8) {
            assertFalse(qr[7, i]); assertFalse(qr[i, 7])
            assertFalse(qr[n - 8, i]); assertFalse(qr[n - 8 + i, 7])
            assertFalse(qr[7, n - 8 + i]); assertFalse(qr[i, n - 8])
        }
    }

    @Test fun timingPatternsAlternate() {
        val qr = QrCode.encode(url)
        for (i in 8 until qr.size - 8) {
            assertEquals(i % 2 == 0, qr[i, 6])
            assertEquals(i % 2 == 0, qr[6, i])
        }
    }

    @Test fun darkModuleIsDark() {
        val qr = QrCode.encode(url)
        assertTrue(qr[8, 4 * qr.version + 9])
    }

    @Test fun alignmentPatternOnVersion2() {
        val qr = QrCode.encode(url)
        for (dy in -2..2) for (dx in -2..2) {
            val ring = maxOf(Math.abs(dx), Math.abs(dy))
            assertEquals("alignment ($dx,$dy)", ring != 1, qr[18 + dx, 18 + dy])
        }
    }

    // ---- format information -----------------------------------------------------------------

    @Test fun formatInfoIsAValidBchWordAtLevelMAndBothCopiesAgree() {
        listOf("a", url, "x".repeat(40), "x".repeat(60), "x".repeat(100)).forEach { text ->
            val qr = QrCode.encode(text)
            val a = readFormatA(qr)
            val b = readFormatB(qr)
            assertEquals("format copies for '${text.take(8)}'", a, b)
            val unmasked = a xor FORMAT_MASK
            assertEquals("BCH remainder", 0, bchRemainder(unmasked))
            assertEquals("EC level M", 0b00, (unmasked shr 13) and 0b11)
            assertTrue((unmasked shr 10) and 0b111 in 0..7)
        }
    }

    /** Versions 7+ carry an 18-bit BCH(18,6) version word in two 6×3 blocks. */
    @Test fun versionInfoIsValidOnVersion10() {
        val qr = QrCode.encode("x".repeat(200))
        val n = qr.size
        var a = 0
        var t = 0
        for (i in 0 until 18) {
            val bit = b(qr[i / 3, n - 11 + i % 3])
            val tbit = b(qr[n - 11 + i % 3, i / 3])
            a = a or (bit shl i)
            t = t or (tbit shl i)
        }
        assertEquals(a, t)
        assertEquals(10, a shr 12)
        var r = a
        for (i in 17 downTo 12) if ((r shr i) and 1 == 1) r = r xor (0x1F25 shl (i - 12))
        assertEquals(0, r and 0xFFF)
    }

    // ---- the oracle: read the data back ------------------------------------------------------

    @Test fun payloadReadsBackAndReedSolomonChecksOut_singleBlockVersions() {
        listOf("a", "HELLO", url, "x".repeat(42)).forEach { text -> assertRoundTrip(text) }
    }

    @Test fun payloadReadsBackAndReedSolomonChecksOut_multiBlockVersions() {
        listOf("x".repeat(45), "http://" + "a".repeat(80) + ":8765", "z".repeat(106)).forEach { text -> assertRoundTrip(text) }
    }

    @Test fun nonAsciiIsEncodedAsUtf8Bytes() {
        val text = "café"
        val qr = QrCode.encode(text)
        val (_, data) = readCodewords(qr)
        assertEquals(0x4, data[0].toInt() shr 4)                 // byte mode
        val len = ((data[0].toInt() and 0xF) shl 4) or ((data[1].toInt() and 0xFF) shr 4)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, len)
    }

    private fun assertRoundTrip(text: String) {
        val qr = QrCode.encode(text)
        val spec = BLOCKS[qr.version] ?: error("no oracle for version ${qr.version}")
        val (blocks, _) = readBlocks(qr, spec)
        blocks.forEachIndexed { i, block ->
            for (s in 0 until spec.ecPerBlock) {
                assertEquals("'${text.take(8)}' v${qr.version} block $i syndrome $s", 0, evalPoly(block, GF.exp[s]))
            }
        }
        val data = readCodewords(qr).second
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(0x4, data[0].toInt() shr 4)
        val len = ((data[0].toInt() and 0xF) shl 4) or ((data[1].toInt() and 0xFF) shr 4)
        assertEquals(bytes.size, len)
        val payload = ByteArray(bytes.size) { i ->
            (((data[1 + i].toInt() and 0xF) shl 4) or ((data[2 + i].toInt() and 0xFF) shr 4)).toByte()
        }
        assertArrayEquals(bytes, payload)
    }

    // ---- oracle helpers -----------------------------------------------------------------------

    private class BlockSpec(val total: Int, val blocks: List<Pair<Int, Int>>) { // (count, dataLen)
        val ecPerBlock: Int get() = (total - blocks.sumOf { it.first * it.second }) / blocks.sumOf { it.first }
    }

    /** ISO 18004 table 9, error-correction level M, versions 1–6. */
    private val BLOCKS = mapOf(
        1 to BlockSpec(26, listOf(1 to 16)),
        2 to BlockSpec(44, listOf(1 to 28)),
        3 to BlockSpec(70, listOf(1 to 44)),
        4 to BlockSpec(100, listOf(2 to 32)),
        5 to BlockSpec(134, listOf(2 to 43)),
        6 to BlockSpec(172, listOf(4 to 27)),
    )

    private val FORMAT_MASK = 0b101010000010010
    private fun bchRemainder(word15: Int): Int {
        var r = word15
        for (i in 14 downTo 10) if ((r shr i) and 1 == 1) r = r xor (0b10100110111 shl (i - 10))
        return r and 0x3FF
    }

    private fun readFormatA(qr: QrCode): Int {
        val xs = listOf(0, 1, 2, 3, 4, 5, 7, 8)
        var v = 0
        var bit = 14
        for (x in xs) { v = v or (b(qr[x, 8]) shl bit); bit-- }        // bits 14..7 along row 8
        for (y in listOf(7, 5, 4, 3, 2, 1, 0)) { v = v or (b(qr[8, y]) shl bit); bit-- } // bits 6..0 up column 8
        return v
    }

    private fun readFormatB(qr: QrCode): Int {
        val n = qr.size
        var v = 0
        var bit = 14
        for (i in 0 until 7) { v = v or (b(qr[8, n - 1 - i]) shl bit); bit-- }   // bits 14..8 down column 8, from bottom
        for (i in 0 until 8) { v = v or (b(qr[n - 8 + i, 8]) shl bit); bit-- }   // bits 7..0 along row 8, right block
        return v
    }

    private fun b(dark: Boolean) = if (dark) 1 else 0

    private fun isFunction(qr: QrCode, x: Int, y: Int): Boolean {
        val n = qr.size
        if (x < 9 && y < 9) return true
        if (x >= n - 8 && y < 9) return true
        if (x < 9 && y >= n - 8) return true
        if (x == 6 || y == 6) return true
        val centers = ALIGN[qr.version] ?: emptyList()
        for (cx in centers) for (cy in centers) {
            if ((cx < 9 && cy < 9) || (cx >= n - 9 && cy < 9) || (cx < 9 && cy >= n - 9)) continue
            if (Math.abs(x - cx) <= 2 && Math.abs(y - cy) <= 2) return true
        }
        return false
    }

    private val ALIGN = mapOf(2 to listOf(6, 18), 3 to listOf(6, 22), 4 to listOf(6, 26), 5 to listOf(6, 30), 6 to listOf(6, 34))

    private fun maskBit(mask: Int, x: Int, y: Int): Boolean = when (mask) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (y / 2 + x / 3) % 2 == 0
        5 -> (x * y) % 2 + (x * y) % 3 == 0
        6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
        else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
    }

    /** All codewords in placement order (still interleaved), and the de-interleaved data. */
    private fun readCodewords(qr: QrCode): Pair<ByteArray, ByteArray> {
        val spec = BLOCKS[qr.version] ?: error("no oracle for version ${qr.version}")
        val (blocks, raw) = readBlocks(qr, spec)
        val data = blocks.flatMap { blk -> blk.take(blk.size - spec.ecPerBlock) }.toByteArray()
        return raw to data
    }

    /** Walks the zigzag, unmasks, de-interleaves into per-block codeword arrays (data ++ ec). */
    private fun readBlocks(qr: QrCode, spec: BlockSpec): Pair<List<ByteArray>, ByteArray> {
        val mask = ((readFormatA(qr) xor FORMAT_MASK) shr 10) and 0b111
        val n = qr.size
        val bits = ArrayList<Int>()
        var x = n - 1
        var upward = true
        while (x > 0) {
            if (x == 6) x--
            val ys = if (upward) (n - 1 downTo 0) else (0 until n)
            for (y in ys) for (dx in 0..1) {
                val cx = x - dx
                if (isFunction(qr, cx, y)) continue
                bits.add(b(qr[cx, y] xor maskBit(mask, cx, y)))
            }
            upward = !upward
            x -= 2
        }
        val raw = ByteArray(spec.total) { i -> (0 until 8).fold(0) { acc, k -> (acc shl 1) or bits[i * 8 + k] }.toByte() }
        // De-interleave.
        val lens = spec.blocks.flatMap { (count, len) -> List(count) { len } }
        val dataBlocks = lens.map { ByteArray(it) }
        val ecBlocks = lens.map { ByteArray(spec.ecPerBlock) }
        var p = 0
        for (i in 0 until lens.max()) for (bi in lens.indices) if (i < lens[bi]) dataBlocks[bi][i] = raw[p++]
        for (i in 0 until spec.ecPerBlock) for (bi in lens.indices) ecBlocks[bi][i] = raw[p++]
        return lens.indices.map { dataBlocks[it] + ecBlocks[it] } to raw
    }

    private object GF {
        val exp = IntArray(512)
        val log = IntArray(256)
        init {
            var v = 1
            for (i in 0 until 255) { exp[i] = v; log[v] = i; v = v shl 1; if (v and 0x100 != 0) v = v xor 0x11d }
            for (i in 255 until 512) exp[i] = exp[i - 255]
        }
        fun mul(a: Int, b: Int) = if (a == 0 || b == 0) 0 else exp[log[a] + log[b]]
    }

    /** Horner evaluation of the codeword polynomial (first codeword = highest degree) at [at]. */
    private fun evalPoly(codewords: ByteArray, at: Int): Int =
        codewords.fold(0) { acc, c -> GF.mul(acc, at) xor (c.toInt() and 0xFF) }
}
