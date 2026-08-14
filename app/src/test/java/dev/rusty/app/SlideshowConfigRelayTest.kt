package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * The relay is the seam between a filter write arriving on an HTTP pool thread and the mounted
 * slideshow that must reload — pure by construction (no `android.*`), so its fan-out and
 * unsubscribe contract are testable here rather than only on-device.
 */
class SlideshowConfigRelayTest {

    @Before
    fun reset() = SlideshowConfigRelay.resetForTest()

    @Test
    fun `notifyChanged reaches every subscriber`() {
        var a = 0
        var b = 0
        SlideshowConfigRelay.addListener { a++ }
        SlideshowConfigRelay.addListener { b++ }

        SlideshowConfigRelay.notifyChanged()

        assertEquals(1, a)
        assertEquals(1, b)
    }

    @Test
    fun `a removed listener stops being called`() {
        var calls = 0
        val l: () -> Unit = { calls++ }
        SlideshowConfigRelay.addListener(l)
        SlideshowConfigRelay.removeListener(l)

        SlideshowConfigRelay.notifyChanged()

        assertEquals("a destroyed Activity must not be reloaded", 0, calls)
    }

    @Test
    fun `notifying with no subscriber is a no-op`() {
        SlideshowConfigRelay.notifyChanged()   // no Activity mounted; must not throw
    }

    @Test
    fun `a listener may unsubscribe from inside the callback`() {
        var calls = 0
        lateinit var l: () -> Unit
        l = {
            calls++
            SlideshowConfigRelay.removeListener(l)   // would CME if we iterated the live list
        }
        SlideshowConfigRelay.addListener(l)

        SlideshowConfigRelay.notifyChanged()
        SlideshowConfigRelay.notifyChanged()

        assertEquals(1, calls)
    }
}
