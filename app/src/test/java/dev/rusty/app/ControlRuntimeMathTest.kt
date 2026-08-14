package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The spec-binding arithmetic behind `/api/volume` and the Host guard. Extracted out of
 * `ControlServiceRuntime` (a private class inside a Service, untestable off-device) for exactly
 * the reason `MediaRendererController.shouldRun` was: the decision is pure, so it should be
 * pinned by tests rather than by inspection.
 */
class ControlRuntimeMathTest {

    // -- ControlVolumeMath.isFixed -------------------------------------------------------

    @Test
    fun `a stream with no steps is fixed — the API never reports it as 0 percent`() {
        assertEquals(true, ControlVolumeMath.isFixed(max = 0, systemReportsFixed = false))
    }

    @Test
    fun `a negative max is fixed too — a nonsense answer is not a volume`() {
        assertEquals(true, ControlVolumeMath.isFixed(max = -1, systemReportsFixed = false))
    }

    @Test
    fun `the system's own isVolumeFixed is honoured even with steps available`() {
        assertEquals(true, ControlVolumeMath.isFixed(max = 15, systemReportsFixed = true))
    }

    @Test
    fun `a normal music stream is not fixed`() {
        assertEquals(false, ControlVolumeMath.isFixed(max = 15, systemReportsFixed = false))
    }

    // -- ControlVolumeMath.percent -------------------------------------------------------

    @Test
    fun `percent spans the full range of a real 15-step stream`() {
        assertEquals(0, ControlVolumeMath.percent(value = 0, max = 15))
        assertEquals(100, ControlVolumeMath.percent(value = 15, max = 15))
    }

    @Test
    fun `percent rounds to nearest, so adjacent steps stay distinguishable`() {
        assertEquals(47, ControlVolumeMath.percent(value = 7, max = 15))
        assertEquals(53, ControlVolumeMath.percent(value = 8, max = 15))
    }

    @Test
    fun `percent of an unusable stream is 0, never a divide by zero`() {
        assertEquals(0, ControlVolumeMath.percent(value = 0, max = 0))
    }

    // -- ControlVolumeMath.step ----------------------------------------------------------

    @Test
    fun `step maps the extremes onto the extremes`() {
        assertEquals(0, ControlVolumeMath.step(percent = 0, max = 15))
        assertEquals(15, ControlVolumeMath.step(percent = 100, max = 15))
    }

    @Test
    fun `a request quantizes to a step, and the answer is the step's percentage — not the request`() {
        // The design doc's "the response reports the *actual* resulting percentage (quantized)":
        // asking for 55% on a 15-step stream lands on step 8, which IS 53%.
        val step = ControlVolumeMath.step(percent = 55, max = 15)
        assertEquals(8, step)
        assertEquals(53, ControlVolumeMath.percent(value = step, max = 15))
    }

    @Test
    fun `step never leaves the device's range, whatever it is handed`() {
        // The router validates 0..100 before this is reached; clamping here means an out-of-range
        // value can still never reach setStreamVolume, which throws on one.
        assertEquals(15, ControlVolumeMath.step(percent = 120, max = 15))
        assertEquals(0, ControlVolumeMath.step(percent = -5, max = 15))
        assertEquals(0, ControlVolumeMath.step(percent = 50, max = 0))
    }

    // -- ControlHosts.localHosts ---------------------------------------------------------

    @Test
    fun `loopback is always allowed, even with no interfaces at all`() {
        assertEquals(setOf("localhost", "127.0.0.1"), ControlHosts.localHosts(emptyList()))
    }

    @Test
    fun `an IPv6 zone suffix is stripped — a Host header never carries one`() {
        val hosts = ControlHosts.localHosts(listOf("fe80::1%wlan0"))
        assertEquals(setOf("localhost", "127.0.0.1", "fe80::1"), hosts)
    }

    @Test
    fun `duplicate and blank addresses collapse`() {
        val hosts = ControlHosts.localHosts(listOf("192.168.1.9", "192.168.1.9", "  ", "192.168.1.9%eth0"))
        assertEquals(setOf("localhost", "127.0.0.1", "192.168.1.9"), hosts)
    }
}
