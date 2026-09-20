package dev.rusty.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class H264ParamSnifferTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0x15, 0x14)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())

    /** RTP header: V=2, [seq], timestamp [ts], SSRC 3, optional CSRCs and a header extension, then [payload]. */
    private fun rtp(payload: ByteArray, csrcs: Int = 0, ext: ByteArray? = null, padding: Int = 0, marker: Boolean = false, seq: Int = 1, ts: Int = 2): ByteArray {
        val b0 = (2 shl 6) or (if (padding > 0) 0x20 else 0) or (if (ext != null) 0x10 else 0) or csrcs
        val b1 = (if (marker) 0x80 else 0) or 96
        val head = byteArrayOf(b0.toByte(), b1.toByte(), (seq shr 8).toByte(), seq.toByte(), 0, 0, 0, ts.toByte(), 0, 0, 0, 3) + ByteArray(4 * csrcs)
        val extBytes = ext?.let { byteArrayOf(0xBE.toByte(), 0xDE.toByte(), (it.size / 4 shr 8).toByte(), (it.size / 4).toByte()) + it } ?: ByteArray(0)
        val pad = if (padding > 0) ByteArray(padding).also { it[padding - 1] = padding.toByte() } else ByteArray(0)
        return head + extBytes + payload + pad
    }

    @Test
    fun `single NAL units for sps and pps complete the sniffer`() {
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(sps)))
        assertTrue(s.onRtpPacket(rtp(pps)))
        assertArrayEquals(sps, s.sps); assertArrayEquals(pps, s.pps)
    }

    @Test
    fun `stap-a carrying both parameter sets completes in one packet`() {
        val stap = byteArrayOf(24) + byteArrayOf(0, sps.size.toByte()) + sps + byteArrayOf(0, pps.size.toByte()) + pps
        val s = H264ParamSniffer()
        assertTrue(s.onRtpPacket(rtp(stap)))
        assertArrayEquals(sps, s.sps); assertArrayEquals(pps, s.pps)
    }

    @Test
    fun `fu-a fragments of an sps are reassembled`() {
        val indicator = (0x60 or 28).toByte()           // NRI from the SPS header, type 28
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 4)   // S=1, type 7
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(4, sps.size) // E=1
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(first, seq = 100)))
        assertFalse(s.onRtpPacket(rtp(last, seq = 101)))
        assertArrayEquals(sps, s.sps)
        assertTrue(s.onRtpPacket(rtp(pps, seq = 102)))
    }

    @Test
    fun `a lost fragment discards the fu-a in progress`() {
        val indicator = (0x60 or 28).toByte()
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 3)
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(5, sps.size)
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(first, seq = 100))
        s.onRtpPacket(rtp(last, seq = 102))          // 101 (the middle piece) never arrived
        assertNull(s.sps)
    }

    @Test
    fun `a fragment with another timestamp discards the fu-a in progress`() {
        val indicator = (0x60 or 28).toByte()
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 4)
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(4, sps.size)
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(first, seq = 100, ts = 2))
        s.onRtpPacket(rtp(last, seq = 101, ts = 3))
        assertNull(s.sps)
    }

    @Test
    fun `fu-a sequence numbers wrap at 65535`() {
        val indicator = (0x60 or 28).toByte()
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 4)
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(4, sps.size)
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(first, seq = 65535))
        s.onRtpPacket(rtp(last, seq = 0))
        assertArrayEquals(sps, s.sps)
    }

    @Test
    fun `csrc list, header extension and padding are skipped`() {
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(sps, csrcs = 2, ext = ByteArray(8), padding = 3))
        assertArrayEquals(sps, s.sps)
    }

    @Test
    fun `other nal types and garbage are ignored`() {
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(0x65, 1, 2, 3))))   // IDR slice
        assertFalse(s.onRtpPacket(byteArrayOf(1, 2, 3)))                // shorter than a header
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(24, 0, 9, 1))))       // STAP-A with a lying size
        assertNull(s.sps); assertNull(s.pps)
    }

    @Test
    fun `the first sps wins`() {
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(sps))
        s.onRtpPacket(rtp(byteArrayOf(0x67, 1, 2, 3)))
        assertArrayEquals(sps, s.sps)
    }

    @Test
    fun `offset and length select a window inside a larger buffer`() {
        val packet = rtp(pps)
        val buf = ByteArray(5) + packet + ByteArray(7)
        val s = H264ParamSniffer()
        s.onRtpPacket(buf, 5, packet.size)
        assertArrayEquals(pps, s.pps)
    }

    // --- Malformed input: every one of these is "ignore this packet", never an exception. ---

    @Test
    fun `a padding count larger than the packet is ignored`() {
        val s = H264ParamSniffer()
        val p = rtp(sps, padding = 4)
        p[p.size - 1] = 99                              // claims 99 bytes of padding in a 23-byte packet
        assertFalse(s.onRtpPacket(p))
        p[p.size - 1] = 0                               // a zero padding count is nonsense too
        assertFalse(s.onRtpPacket(p))
        assertNull(s.sps)
    }

    @Test
    fun `padding that swallows the whole payload is ignored`() {
        val s = H264ParamSniffer()
        val p = rtp(sps)
        p[0] = (p[0].toInt() or 0x20).toByte()          // padding bit on, and the count covers the payload
        p[p.size - 1] = sps.size.toByte()
        assertFalse(s.onRtpPacket(p))
        assertNull(s.sps)
    }

    @Test
    fun `an extension length that overruns the packet is ignored`() {
        val s = H264ParamSniffer()
        val p = rtp(sps, ext = ByteArray(8))
        p[14] = 0x7F; p[15] = 0xFF.toByte()             // 32767 extension words in a 31-byte packet
        assertFalse(s.onRtpPacket(p))
        val truncated = p.copyOfRange(0, 14)            // the extension header itself is cut off
        assertFalse(s.onRtpPacket(truncated))
        assertNull(s.sps)
    }

    @Test
    fun `a csrc count that overruns the packet is ignored`() {
        val s = H264ParamSniffer()
        val p = rtp(sps)
        p[0] = ((p[0].toInt() and 0xF0) or 15).toByte() // 15 CSRCs = 60 bytes that are not there
        assertFalse(s.onRtpPacket(p))
        assertNull(s.sps)
    }

    @Test
    fun `an empty payload and a truncated fu-a header do not throw`() {
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(ByteArray(0))))                          // header only
        assertFalse(s.onRtpPacket(rtp(byteArrayOf((0x60 or 28).toByte()))))    // FU-A minus its FU header
        val indicator = (0x60 or 28).toByte()
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(indicator, (0x80 or 7).toByte()))))  // S=1, no data
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(indicator, (0x40 or 7).toByte()), seq = 2)))
        assertNull(s.sps)                               // a 1-byte "SPS" is not a parameter set
    }

    @Test
    fun `a parameter set too short to be real is refused`() {
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(0x67, 0x64, 0x00))))          // single NAL, 3 bytes
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(0x68))))                      // single NAL, header only
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(24, 0, 1, 0x67, 0, 1, 0x68))))  // STAP-A, size 1 each
        assertNull(s.sps); assertNull(s.pps)
        assertFalse(s.onRtpPacket(rtp(sps)))            // the real ones still land afterwards
        assertTrue(s.onRtpPacket(rtp(pps)))
        assertArrayEquals(sps, s.sps); assertArrayEquals(pps, s.pps)
    }

    @Test
    fun `a three fragment fu-a is reassembled in order`() {
        val indicator = (0x60 or 28).toByte()
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 3)   // S=1
        val middle = byteArrayOf(indicator, 7) + sps.copyOfRange(3, 5)                     // S=0, E=0
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(5, sps.size) // E=1
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(first, seq = 100)))
        assertFalse(s.onRtpPacket(rtp(middle, seq = 101)))
        assertFalse(s.onRtpPacket(rtp(last, seq = 102)))
        assertArrayEquals(sps, s.sps)
    }

    @Test
    fun `a stap-a with a zero size or a truncated size field is ignored`() {
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(24, 0, 0, 0x67, 1))))   // size 0
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(24, 0))))               // size field cut in half
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(24))))                  // nothing after the header
        assertNull(s.sps)
    }

    @Test
    fun `a non-version-2 packet is ignored`() {
        val s = H264ParamSniffer()
        val p = rtp(sps)
        p[0] = (p[0].toInt() and 0x3F).toByte()         // version 0
        assertFalse(s.onRtpPacket(p))
        assertNull(s.sps)
    }

    @Test
    fun `an out of range offset or length is ignored`() {
        val s = H264ParamSniffer()
        val p = rtp(sps)
        assertFalse(s.onRtpPacket(p, 0, p.size + 1))
        assertFalse(s.onRtpPacket(p, p.size, 0))
        assertFalse(s.onRtpPacket(p, -4, p.size))
        assertFalse(s.onRtpPacket(p, 0, -1))
        assertFalse(s.onRtpPacket(p, Int.MAX_VALUE - 4, 64))   // offset + length overflows Int
        assertFalse(s.onRtpPacket(ByteArray(0), 0, 0))
        assertNull(s.sps)
    }

    @Test
    fun `an interleaved fu-a from another stream cannot splice a nal`() {
        val indicator = (0x60 or 28).toByte()
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 4)
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(4, sps.size)
        val other = rtp(last, seq = 101).also { it[8] = 9 }   // same seq and ts, another SSRC
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(first, seq = 100))
        s.onRtpPacket(other)
        assertNull(s.sps)
    }

    @Test
    fun `a fu-a of another nal type discards the fu-a in progress`() {
        val indicator = (0x60 or 28).toByte()
        val first = byteArrayOf(indicator, (0x80 or 7).toByte()) + sps.copyOfRange(1, 4)
        val slice = byteArrayOf(indicator, (0x40 or 5).toByte()) + byteArrayOf(1, 2, 3)
        val last = byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(4, sps.size)
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(first, seq = 100))
        s.onRtpPacket(rtp(slice, seq = 101))
        s.onRtpPacket(rtp(last, seq = 102))
        assertNull(s.sps)
    }

    @Test
    fun `a fu-a continuation without a start fragment is ignored`() {
        val indicator = (0x60 or 28).toByte()
        val s = H264ParamSniffer()
        assertFalse(s.onRtpPacket(rtp(byteArrayOf(indicator, (0x40 or 7).toByte()) + sps.copyOfRange(1, sps.size))))
        assertNull(s.sps)
    }

    @Test
    fun `an endless fu-a cannot grow without bound`() {
        val indicator = (0x60 or 28).toByte()
        val s = H264ParamSniffer()
        s.onRtpPacket(rtp(byteArrayOf(indicator, (0x80 or 7).toByte()) + ByteArray(1024), seq = 0))
        for (n in 1..128) {
            s.onRtpPacket(rtp(byteArrayOf(indicator, 7) + ByteArray(1024), seq = n))
        }
        s.onRtpPacket(rtp(byteArrayOf(indicator, (0x40 or 7).toByte()) + ByteArray(16), seq = 129))
        assertNull(s.sps)
    }
}
