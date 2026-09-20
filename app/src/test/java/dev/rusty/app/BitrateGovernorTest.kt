package dev.rusty.app

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BitrateGovernorTest {

    private val configured = 1_500_000
    private val floor = (configured * BitrateGovernor.FLOOR_FRACTION).toInt()

    /**
     * Feeds [frames] equal access units carrying [bps] over [ms], and returns the last correction.
     *
     * The first call carries no bytes and only opens the window, so the bytes that follow span
     * exactly [ms] — otherwise the window closes a frame early and the measured rate is not the
     * one the test asked for.
     */
    private fun BitrateGovernor.run(bps: Int, ms: Long, startMs: Long = 0L, frames: Int = 45): Int? {
        val bytesPerFrame = (bps.toLong() * ms / 8_000 / frames).toInt()
        var last: Int? = null
        onAccessUnit(0, startMs)
        for (i in 1..frames) {
            onAccessUnit(bytesPerFrame, startMs + ms * i / frames)?.let { last = it }
        }
        return last
    }

    @Test
    fun `nothing is corrected before a full window has elapsed`() {
        val governor = BitrateGovernor(configured)
        // A whole second of double-rate traffic, well inside the 3 s window.
        assertNull(governor.run(configured * 2, ms = 1_000))
        assertEquals(configured, governor.current)
    }

    @Test
    fun `a stream inside its budget is left exactly as the pipeline configured it`() {
        val governor = BitrateGovernor(configured)
        // The Lenovo's camera delivers 9 fps and 0.96 Mbps against a 1.5 Mbps budget. Undershoot
        // must not be "corrected" upward past the ceiling, or the direction that works regresses.
        assertNull(governor.run(960_000, ms = 6_000))
        assertEquals(configured, governor.current)
    }

    @Test
    fun `a stream on target is left alone`() {
        val governor = BitrateGovernor(configured)
        assertNull(governor.run(configured, ms = 6_000))
        assertEquals(configured, governor.current)
    }

    @Test
    fun `the measured Echo Show overshoot is halved in a single window`() {
        val governor = BitrateGovernor(configured)
        // 2.97 Mbps against a 1.5 Mbps budget: the figure measured off the device.
        val next = governor.run(2_970_000, ms = 3_000)
        assertEquals(757_575, next)
        assertEquals(757_575, governor.current)
    }

    @Test
    fun `correcting the overshoot brings the wire rate onto target`() {
        val governor = BitrateGovernor(configured)
        // The encoder spends its per-frame budget on twice as many frames as it was configured
        // for, so whatever it is asked for, twice that lands on the wire.
        governor.run(governor.current * 2, ms = 3_000)
        val settled = governor.current
        assertTrue(
            "the corrected setting should put the wire rate within tolerance of target",
            abs(settled * 2 - configured) < configured * BitrateGovernor.TOLERANCE,
        )
        assertNull(
            "a stream now on target needs no further correction",
            governor.run(settled * 2, ms = 3_000, startMs = 3_000),
        )
    }

    @Test
    fun `a camera that slows down climbs back toward the configured ceiling`() {
        val governor = BitrateGovernor(configured)
        governor.run(2_970_000, ms = 3_000)
        val lowered = governor.current
        assertTrue(lowered < configured)
        // The room dims and the camera halves its frame rate, so the same setting now puts far
        // less on the wire. Recovery has to be symmetric or the picture stays needlessly soft.
        val raised = governor.run(400_000, ms = 3_000, startMs = 3_000)
        assertTrue("should climb back up, got $raised", raised != null && raised > lowered)
    }

    @Test
    fun `the configured bitrate is a hard ceiling`() {
        val governor = BitrateGovernor(configured)
        var now = 0L
        repeat(10) {
            governor.run(100_000, ms = 3_000, startMs = now)
            now += 3_000
        }
        assertEquals(configured, governor.current)
    }

    @Test
    fun `a collapse in measured rate cannot push the setting below the floor`() {
        val governor = BitrateGovernor(configured)
        var now = 0L
        repeat(10) {
            governor.run(configured * 40, ms = 3_000, startMs = now)
            now += 3_000
        }
        assertEquals(floor, governor.current)
    }

    @Test
    fun `a correction too small to be worth a codec round trip is suppressed`() {
        val governor = BitrateGovernor(configured)
        // Land just above the floor, so the next clamp can only move the setting a few percent.
        val first = governor.run(5_769_230, ms = 3_000)
        assertTrue("expected a setting just above the floor, got $first", first != null && first > floor && first < floor * 1.05)
        // A vast overshoot now scales to far below the floor and clamps back to it — a change too
        // small to apply. Without MIN_STEP this would re-set the bitrate every window forever.
        assertNull(governor.run(configured * 40, ms = 3_000, startMs = 3_000))
        assertEquals(first, governor.current)
    }

    @Test
    fun `a window with no frames at all is not a division by zero`() {
        val governor = BitrateGovernor(configured)
        assertNull(governor.onAccessUnit(0, 0))
        assertNull(governor.onAccessUnit(0, 10_000))
        assertEquals(configured, governor.current)
    }

    @Test
    fun `a clock that goes backwards restarts the window instead of acting on it`() {
        val governor = BitrateGovernor(configured)
        governor.onAccessUnit(100_000, 10_000)
        assertNull(governor.onAccessUnit(100_000, 5_000))
        assertEquals(configured, governor.current)
    }

    @Test
    fun `a target below the configured bitrate is still capped by the configured one`() {
        val governor = BitrateGovernor(configuredBps = 1_500_000, targetBps = 800_000)
        val next = governor.run(1_600_000, ms = 3_000)
        assertTrue("should halve toward the 800 kbps target, got $next", next != null && next in 700_000..800_000)
    }

    @Test
    fun `reset returns to the configured bitrate for a restarted encoder`() {
        val governor = BitrateGovernor(configured)
        governor.run(2_970_000, ms = 3_000)
        assertTrue(governor.current < configured)
        governor.reset()
        assertEquals(configured, governor.current)
        assertNull("the window is forgotten too", governor.onAccessUnit(1_000, 100_000))
    }
}
