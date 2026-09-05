package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TestReportTest {
    @Test
    fun `lines carry the mark and the format when known`() {
        assertEquals("Stream ✓ · H.264 · 640×360 · 15 fps", TestReport.line("Stream", true, VideoFormatInfo("video/avc", 640, 360, 15f)))
        assertEquals("Main ✗", TestReport.line("Main", false, null))
        assertEquals("Snapshot ✓", TestReport.line("Snapshot", true, null))
    }
}
