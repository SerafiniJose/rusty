package dev.rusty.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264NalTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `splits on 4-byte and 3-byte start codes and drops the codes`() {
        val buf = b(0, 0, 0, 1, 0x67, 0xAA, 0, 0, 1, 0x68, 0xBB, 0xCC, 0, 0, 0, 1, 0x65, 1, 2, 3)
        val nals = H264Nal.splitAnnexB(buf)
        assertEquals(3, nals.size)
        assertArrayEquals(b(0x67, 0xAA), nals[0])
        assertArrayEquals(b(0x68, 0xBB, 0xCC), nals[1])
        assertArrayEquals(b(0x65, 1, 2, 3), nals[2])
    }

    @Test fun `leading garbage before the first start code is ignored and no start code means one nal`() {
        assertEquals(1, H264Nal.splitAnnexB(b(0x09, 0, 0, 0, 1, 0x65, 7)).size)
        assertArrayEquals(b(0x65, 7), H264Nal.splitAnnexB(b(0x65, 7))[0])
        assertTrue(H264Nal.splitAnnexB(ByteArray(0)).isEmpty())
    }

    @Test fun `type and keyframe detection`() {
        assertEquals(7, H264Nal.type(b(0x67)))
        assertEquals(5, H264Nal.type(b(0x65)))
        assertTrue(H264Nal.isKeyframe(listOf(b(0x67), b(0x68), b(0x65, 1))))
        assertFalse(H264Nal.isKeyframe(listOf(b(0x41, 1))))
    }
}
