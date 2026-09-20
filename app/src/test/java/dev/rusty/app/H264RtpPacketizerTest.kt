package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264RtpPacketizerTest {
    private fun header(p: ByteArray) = object {
        val version = (p[0].toInt() shr 6) and 3
        val marker = (p[1].toInt() and 0x80) != 0
        val pt = p[1].toInt() and 0x7F
        val seq = ((p[2].toInt() and 0xFF) shl 8) or (p[3].toInt() and 0xFF)
        val ts = ((p[4].toLong() and 0xFF) shl 24) or ((p[5].toLong() and 0xFF) shl 16) or ((p[6].toLong() and 0xFF) shl 8) or (p[7].toLong() and 0xFF)
        val ssrc = ((p[8].toInt() and 0xFF) shl 24) or ((p[9].toInt() and 0xFF) shl 16) or ((p[10].toInt() and 0xFF) shl 8) or (p[11].toInt() and 0xFF)
    }

    @Test fun `small nal becomes one single-nal packet with marker`() {
        val pk = H264RtpPacketizer(ssrc = 0x11223344, initialSeq = 10)
        val out = pk.packetize(listOf(byteArrayOf(0x65, 1, 2, 3)), rtpTimestamp = 90_000)
        assertEquals(1, out.size)
        val h = header(out[0])
        assertEquals(2, h.version); assertTrue(h.marker); assertEquals(96, h.pt)
        assertEquals(10, h.seq); assertEquals(90_000L, h.ts); assertEquals(0x11223344, h.ssrc)
        assertEquals(0x65, out[0][12].toInt()); assertEquals(16, out[0].size)
        assertEquals(11, pk.nextSeq)
    }

    @Test fun `two nals in one access unit - marker only on the last packet`() {
        val pk = H264RtpPacketizer(ssrc = 1)
        val out = pk.packetize(listOf(byteArrayOf(0x67, 9), byteArrayOf(0x65, 8)), 0)
        assertEquals(2, out.size)
        assertFalse(header(out[0]).marker); assertTrue(header(out[1]).marker)
    }

    @Test fun `large nal is fragmented into FU-A with correct S and E bits`() {
        val nal = ByteArray(3001) { if (it == 0) 0x65 else (it and 0xFF).toByte() }   // NRI=3, type 5
        val pk = H264RtpPacketizer(ssrc = 1, mtu = 1400)
        val out = pk.packetize(listOf(nal), 0)
        // payload per fragment = 1400 - 12 header - 2 FU bytes = 1386; 3000 body bytes -> 3 fragments
        assertEquals(3, out.size)
        for ((i, p) in out.withIndex()) {
            assertTrue(p.size <= 1400)
            assertEquals(0x7C, p[12].toInt() and 0xFF)              // FU indicator: NRI 3 | type 28
            val fuHeader = p[13].toInt() and 0xFF
            assertEquals(if (i == 0) 0x80 else 0, fuHeader and 0x80) // S
            assertEquals(if (i == 2) 0x40 else 0, fuHeader and 0x40) // E
            assertEquals(5, fuHeader and 0x1F)                       // original type
            assertEquals(i == 2, header(out[i]).marker)
        }
        // Reassemble and compare against the original nal body.
        val body = out.flatMap { it.copyOfRange(14, it.size).toList() }
        assertEquals(nal.copyOfRange(1, nal.size).toList(), body)
    }

    @Test fun `sequence number wraps at 16 bits`() {
        val pk = H264RtpPacketizer(ssrc = 1, initialSeq = 65535)
        pk.packetize(listOf(byteArrayOf(0x65)), 0)
        assertEquals(0, pk.nextSeq)
    }

    @Test fun `timestamp conversion is 90 kHz`() {
        assertEquals(90_000L, H264RtpPacketizer.rtpTimestamp(1_000_000))
        assertEquals(2_999L, H264RtpPacketizer.rtpTimestamp(33_333))   // integer truncation, by design: 33333*90/1000 = 2999.97
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mtu too small for the FU-A header is rejected immediately`() {
        H264RtpPacketizer(ssrc = 1, mtu = 14)
    }
}
