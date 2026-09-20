package dev.rusty.app

/** RFC 6184 packetizer: single-NAL units when they fit, FU-A otherwise. One instance per client. */
class H264RtpPacketizer(
    private val ssrc: Int,
    initialSeq: Int = 0,
    private val mtu: Int = 1400,
    private val payloadType: Int = 96,
) {
    private var seq = initialSeq and 0xFFFF
    val nextSeq: Int get() = seq

    companion object {
        const val HEADER = 12
        fun rtpTimestamp(ptsUs: Long): Long = (ptsUs * 90L) / 1000L
    }

    init {
        require(mtu > HEADER + 2) { "mtu must exceed the RTP header plus the FU-A header" }
    }

    /** Packetizes one complete access unit (every NAL for a single encoded frame) into RTP
     *  packets. The marker bit is set only on the last packet this call produces, and every
     *  packet from this call shares one [rtpTimestamp] — both are only correct when called once
     *  per access unit. Calling it once per NAL instead sets the marker on every NAL, breaking
     *  frame boundaries for downstream players (media3, VLC, live555). */
    fun packetize(nals: List<ByteArray>, rtpTimestamp: Long): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val maxPayload = mtu - HEADER
        for ((n, nal) in nals.withIndex()) {
            val last = n == nals.lastIndex
            if (nal.size <= maxPayload) {
                out.add(packet(nal, 0, nal.size, rtpTimestamp, marker = last, prefix = null))
            } else {
                val indicator = ((nal[0].toInt() and 0xE0) or 28).toByte()
                val type = (nal[0].toInt() and 0x1F)
                val chunk = maxPayload - 2
                var pos = 1
                while (pos < nal.size) {
                    val len = minOf(chunk, nal.size - pos)
                    val start = pos == 1
                    val endOfNal = pos + len == nal.size
                    val fuHeader = ((if (start) 0x80 else 0) or (if (endOfNal) 0x40 else 0) or type).toByte()
                    out.add(packet(nal, pos, len, rtpTimestamp, marker = last && endOfNal, prefix = byteArrayOf(indicator, fuHeader)))
                    pos += len
                }
            }
        }
        return out
    }

    private fun packet(src: ByteArray, off: Int, len: Int, ts: Long, marker: Boolean, prefix: ByteArray?): ByteArray {
        val plen = (prefix?.size ?: 0)
        val p = ByteArray(HEADER + plen + len)
        p[0] = 0x80.toByte()
        p[1] = ((if (marker) 0x80 else 0) or payloadType).toByte()
        p[2] = (seq shr 8).toByte(); p[3] = seq.toByte()
        p[4] = (ts shr 24).toByte(); p[5] = (ts shr 16).toByte(); p[6] = (ts shr 8).toByte(); p[7] = ts.toByte()
        p[8] = (ssrc shr 24).toByte(); p[9] = (ssrc shr 16).toByte(); p[10] = (ssrc shr 8).toByte(); p[11] = ssrc.toByte()
        if (prefix != null) System.arraycopy(prefix, 0, p, HEADER, plen)
        System.arraycopy(src, off, p, HEADER + plen, len)
        seq = (seq + 1) and 0xFFFF
        return p
    }
}
