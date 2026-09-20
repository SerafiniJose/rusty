package dev.rusty.app

/** Annex-B helpers. Pure: no android.* imports. */
object H264Nal {
    const val TYPE_IDR = 5
    const val TYPE_SPS = 7
    const val TYPE_PPS = 8

    fun type(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    fun isKeyframe(nals: List<ByteArray>): Boolean = nals.any { it.isNotEmpty() && type(it) == TYPE_IDR }

    /** Splits [buf] on 00 00 01 / 00 00 00 01 start codes. Bytes before the first start code are
     *  dropped; a buffer with no start code is returned as a single NAL. Trailing zero bytes that
     *  belong to the next start code are not included in the previous NAL. */
    fun splitAnnexB(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): List<ByteArray> {
        val end = offset + length
        if (length <= 0) return emptyList()
        val starts = ArrayList<Int>()   // index of the first byte AFTER each start code
        val codeStarts = ArrayList<Int>()
        var i = offset
        while (i + 2 < end) {
            if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0) {
                if (buf[i + 2].toInt() == 1) {
                    codeStarts.add(i); starts.add(i + 3); i += 3; continue
                }
                if (buf[i + 2].toInt() == 0 && i + 3 < end && buf[i + 3].toInt() == 1) {
                    codeStarts.add(i); starts.add(i + 4); i += 4; continue
                }
            }
            i++
        }
        if (starts.isEmpty()) return listOf(buf.copyOfRange(offset, end))
        val out = ArrayList<ByteArray>(starts.size)
        for (n in starts.indices) {
            val s = starts[n]
            val e = if (n + 1 < starts.size) codeStarts[n + 1] else end
            if (e > s) out.add(buf.copyOfRange(s, e))
        }
        return out
    }
}

data class AccessUnit(val nals: List<ByteArray>, val ptsUs: Long, val keyframe: Boolean)
