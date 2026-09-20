package dev.rusty.app

/**
 * Pulls the first SPS (NAL 7) and PPS (NAL 8) out of an H.264 RTP stream (RFC 6184): single NAL
 * units, STAP-A aggregates and FU-A fragments. Pure bytes; no `android.*`.
 *
 * Only what the SDP repair needs: a camera that omits `sprop-parameter-sets` still repeats SPS/PPS
 * in-band before every IDR, so a few hundred milliseconds of stream is enough.
 *
 * [onRtpPacket] parses bytes straight off a socket and never throws: a truncated packet, a length
 * field that overruns the buffer, an impossible padding count — each one is "ignore this packet".
 */
class H264ParamSniffer {
    var sps: ByteArray? = null; private set
    var pps: ByteArray? = null; private set
    val complete: Boolean get() = sps != null && pps != null

    /** FU-A reassembly buffer for the fragment currently in flight (SPS/PPS only), else null. */
    private var fragment: java.io.ByteArrayOutputStream? = null
    private var fragmentType = 0
    /** RTP sequence/timestamp/SSRC of the last fragment accepted into [fragment]. */
    private var fragSeq = 0
    private var fragTs = 0
    private var fragSsrc = 0

    private companion object {
        /** A parameter set is a few dozen bytes. A stream that keeps sending consecutive FU-A
         *  fragments without an end bit must not grow this buffer without limit. */
        const val MAX_FRAGMENT = 64 * 1024
    }

    fun onRtpPacket(buf: ByteArray, offset: Int = 0, length: Int = buf.size - offset): Boolean {
        // `length > buf.size - offset` rather than `offset + length > buf.size`: the sum of two
        // caller-supplied ints can overflow and slip past the bounds check.
        if (length < 12 || offset < 0 || length > buf.size - offset) return complete
        val b0 = buf[offset].toInt() and 0xFF
        if (b0 shr 6 != 2) return complete
        val csrcCount = b0 and 0x0F
        var start = offset + 12 + 4 * csrcCount
        var end = offset + length
        if (b0 and 0x20 != 0) {                        // padding: last byte says how much
            val pad = buf[end - 1].toInt() and 0xFF
            if (pad == 0 || pad > end - start) return complete
            end -= pad
        }
        if (b0 and 0x10 != 0) {                        // header extension: 4-byte head + N*4 bytes
            if (start + 4 > end) return complete
            val words = ((buf[start + 2].toInt() and 0xFF) shl 8) or (buf[start + 3].toInt() and 0xFF)
            start += 4 + 4 * words
        }
        if (start >= end) return complete
        val seq = ((buf[offset + 2].toInt() and 0xFF) shl 8) or (buf[offset + 3].toInt() and 0xFF)
        val ts = readInt(buf, offset + 4)
        val ssrc = readInt(buf, offset + 8)
        onPayload(buf, start, end, seq, ts, ssrc)
        return complete
    }

    private fun readInt(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 24) or ((buf[at + 1].toInt() and 0xFF) shl 16) or
            ((buf[at + 2].toInt() and 0xFF) shl 8) or (buf[at + 3].toInt() and 0xFF)

    private fun onPayload(buf: ByteArray, start: Int, end: Int, seq: Int, ts: Int, ssrc: Int) {
        val header = buf[start].toInt() and 0xFF
        when (val type = header and 0x1F) {
            in 1..23 -> offer(type, buf.copyOfRange(start, end))
            24 -> {                                    // STAP-A: (2-byte size, NAL)*
                var p = start + 1
                while (p + 2 <= end) {
                    val size = ((buf[p].toInt() and 0xFF) shl 8) or ((buf[p + 1].toInt() and 0xFF))
                    p += 2
                    if (size == 0 || size > end - p) return
                    offer(buf[p].toInt() and 0x1F, buf.copyOfRange(p, p + size))
                    p += size
                }
            }
            28 -> {                                    // FU-A: indicator, FU header (S E R type), data
                if (start + 2 > end) return
                val fu = buf[start + 1].toInt() and 0xFF
                val nalType = fu and 0x1F
                val startBit = fu and 0x80 != 0
                val endBit = fu and 0x40 != 0
                if (nalType != H264Nal.TYPE_SPS && nalType != H264Nal.TYPE_PPS) { fragment = null; return }
                if (startBit) {
                    fragment = java.io.ByteArrayOutputStream().also {
                        it.write((header and 0xE0) or nalType)   // rebuild the NAL header byte
                    }
                    fragmentType = nalType
                    fragSeq = seq; fragTs = ts; fragSsrc = ssrc
                } else {
                    if (fragment == null) return
                    // RFC 6184 §5.8: the fragments of one NAL are consecutive packets with one
                    // timestamp. A sequence gap, another timestamp/SSRC or a type change means a
                    // piece is missing — gluing the rest together would hand media3 a corrupt SPS.
                    if (seq != ((fragSeq + 1) and 0xFFFF) || ts != fragTs || ssrc != fragSsrc || fragmentType != nalType) { fragment = null; return }
                    fragSeq = seq
                }
                val f = fragment ?: return
                f.write(buf, start + 2, end - start - 2)
                if (f.size() > MAX_FRAGMENT) { fragment = null; return }
                if (endBit) { offer(nalType, f.toByteArray()); fragment = null }
            }
            else -> Unit
        }
    }

    /** First one wins — but only if it could plausibly BE a parameter set. A truncated packet can
     *  yield a 1-byte "SPS" (a bare NAL header), and latching that would silently poison the
     *  repair: [SdpRepair.profileLevelId] falls back to a canned profile below 4 bytes, and the
     *  fetcher caches whatever it got. Both thresholds sit under any real parameter set. */
    private fun offer(type: Int, nal: ByteArray) {
        when (type) {
            H264Nal.TYPE_SPS -> if (sps == null && nal.size >= 4) sps = nal
            H264Nal.TYPE_PPS -> if (pps == null && nal.size >= 2) pps = nal
        }
    }
}
