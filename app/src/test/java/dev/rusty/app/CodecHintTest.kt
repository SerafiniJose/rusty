package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecHintTest {
    @Test
    fun `onvif codec names map to mimes`() {
        assertEquals("video/avc", CodecNames.mimeForOnvif("H264"))
        assertEquals("video/avc", CodecNames.mimeForOnvif("h.264"))
        assertEquals("video/hevc", CodecNames.mimeForOnvif("HEVC"))
        assertEquals("video/hevc", CodecNames.mimeForOnvif("H265"))
        assertEquals("video/mjpeg", CodecNames.mimeForOnvif("JPEG"))
        assertNull(CodecNames.mimeForOnvif("VP8000"))
    }

    @Test
    fun `mime labels`() {
        assertEquals("H.264", CodecNames.label("video/avc"))
        assertEquals("H.265", CodecNames.label("video/hevc"))
        assertEquals("MJPEG", CodecNames.label("video/mjpeg"))
        assertEquals("VP9", CodecNames.label("video/vp9"))
    }

    @Test
    fun `describe joins codec size and fps`() {
        assertEquals("H.264 · 640×360 · 15 fps", CodecHint.describe(VideoFormatInfo("video/avc", 640, 360, 15f)))
        assertEquals("H.265 · 2560×1440", CodecHint.describe(VideoFormatInfo("video/hevc", 2560, 1440, 0f)))
    }

    @Test
    fun `hint only when no decoder`() {
        val f = VideoFormatInfo("video/hevc", 2560, 1440, 20f)
        assertNull(CodecHint.build(f, decoderFound = true, suffix = "x"))
        assertEquals(
            "This device can't decode H.265 at 2560×1440 — set the camera to H.264.",
            CodecHint.build(f, decoderFound = false, suffix = "set the camera to H.264."),
        )
    }
}
