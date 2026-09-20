package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoStatsTest {
    private val f = VideoFormatInfo("video/avc", 1280, 720, -1f)

    @Test fun `no format yet means no chip`() {
        assertNull(VideoStats(null, null).chipText())
        assertNull(VideoStats(VideoFormatInfo("video/avc", 0, 0, -1f), 30).chipText())
    }

    @Test fun `size and codec with the frame rate pending`() {
        assertEquals("1280×720 · … fps · H.264", VideoStats(f, null).chipText())
    }

    @Test fun `size, measured frame rate and codec`() {
        assertEquals("1280×720 · 30 fps · H.264", VideoStats(f, 30).chipText())
        assertEquals("640×360 · 12 fps · H.265", VideoStats(VideoFormatInfo("video/hevc", 640, 360, 25f), 12).chipText())
    }
}
