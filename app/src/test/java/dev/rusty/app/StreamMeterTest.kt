package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamMeterTest {

    @Test fun `nothing is reported inside the first window`() {
        val m = StreamMeter(windowMs = 1_000)
        assertNull(m.onFrame(1_000, 0))
        assertNull(m.onFrame(1_000, 500))
        assertNull(m.onFrame(1_000, 999))
    }

    @Test fun `one sample per window, scaled to a full second`() {
        val m = StreamMeter(windowMs = 1_000)
        // 30 frames of 6 250 bytes across exactly one second = 30 fps, 1.5 Mbit/s.
        var sample: StreamMeter.Sample? = null
        for (i in 0 until 30) sample = m.onFrame(6_250, (i * 1_000L) / 30)
        assertNull(sample)
        sample = m.onFrame(6_250, 1_000)
        assertEquals(StreamMeter.Sample(fps = 30, bps = 1_500_000), sample)
    }

    @Test fun `the frame that closes a window belongs to the next one`() {
        val m = StreamMeter(windowMs = 1_000)
        repeat(10) { m.onFrame(100, it * 100L) }
        m.onFrame(100, 1_000)                 // closes window 1 (10 frames), opens window 2 with itself
        repeat(4) { m.onFrame(100, 1_100 + it * 100L) }
        val s = m.onFrame(100, 2_000)         // window 2 held 5 frames
        assertEquals(5, s?.fps)
        assertEquals(4_000, s?.bps)
    }

    @Test fun `a shorter or longer window is still scaled per second`() {
        val m = StreamMeter(windowMs = 500)
        repeat(5) { m.onFrame(1_000, it * 100L) }
        assertEquals(StreamMeter.Sample(fps = 10, bps = 80_000), m.onFrame(1_000, 500))
    }

    @Test fun `a clock that goes backwards restarts the window instead of dividing by it`() {
        val m = StreamMeter(windowMs = 1_000)
        m.onFrame(100, 5_000)
        assertNull(m.onFrame(100, 4_000))
        repeat(9) { m.onFrame(100, 4_100 + it * 100L) }
        assertEquals(10, m.onFrame(100, 5_000)?.fps)
    }

    @Test fun `reset forgets the open window`() {
        val m = StreamMeter(windowMs = 1_000)
        repeat(20) { m.onFrame(100, it * 50L) }
        m.reset()
        assertNull(m.onFrame(100, 1_000))
        assertNull(m.onFrame(100, 1_999))
        assertEquals(2, m.onFrame(100, 2_000)?.fps)
    }
}
