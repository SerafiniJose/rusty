package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The photo stage may only swallow a tap when the tap has somewhere to go. Every case that does not
 * reveal the pill must fall through to the screensaver's wake/exit path, or a touch user with the
 * clock hidden has no way out of the saver at all.
 */
class ImmichStageTapTest {

    @Test
    fun `showing photos consumes the tap`() {
        assertTrue(
            SlideshowStage.consumesTaps(sleepLayer = false, hasController = true, hasStatus = false),
        )
    }

    @Test
    fun `unconfigured frame never consumes the tap`() {
        // No controller is ever constructed for an unconfigured frame (the common first-run state),
        // so the tap must reach the wake path.
        assertFalse(
            SlideshowStage.consumesTaps(sleepLayer = false, hasController = false, hasStatus = true),
        )
        assertFalse(
            SlideshowStage.consumesTaps(sleepLayer = false, hasController = false, hasStatus = false),
        )
    }

    @Test
    fun `a status line never consumes the tap`() {
        // Auth / unreachable / no-photos: a controller exists but the pill stays suppressed.
        assertFalse(
            SlideshowStage.consumesTaps(sleepLayer = false, hasController = true, hasStatus = true),
        )
    }

    @Test
    fun `a sleep layer never consumes the tap`() {
        for (hasController in listOf(true, false)) {
            for (hasStatus in listOf(true, false)) {
                assertFalse(
                    "sleepLayer must fall through (controller=$hasController status=$hasStatus)",
                    SlideshowStage.consumesTaps(
                        sleepLayer = true,
                        hasController = hasController,
                        hasStatus = hasStatus,
                    ),
                )
            }
        }
    }

    @Test
    fun `exactly one of the eight configurations consumes`() {
        val consuming = mutableListOf<Triple<Boolean, Boolean, Boolean>>()
        for (sleepLayer in listOf(true, false)) {
            for (hasController in listOf(true, false)) {
                for (hasStatus in listOf(true, false)) {
                    if (SlideshowStage.consumesTaps(sleepLayer, hasController, hasStatus)) {
                        consuming += Triple(sleepLayer, hasController, hasStatus)
                    }
                }
            }
        }
        assertTrue("unexpected consuming set: $consuming", consuming.size == 1)
        assertTrue(consuming.single() == Triple(false, true, false))
    }

    // ---- consumesNavKeys: when the slideshow owns the remote ---------------------------------

    @Test
    fun `nav keys drive a running slideshow — even as a sleep layer`() {
        assertTrue(SlideshowStage.consumesNavKeys(hasController = true, hasStatus = false))
    }

    @Test
    fun `nav keys refuse an unconfigured or status-showing frame`() {
        // No controller (unconfigured / torn down) or a status line up: the old any-key-wakes
        // rule must apply so nobody is stuck staring at an error screen.
        assertFalse(SlideshowStage.consumesNavKeys(hasController = false, hasStatus = false))
        assertFalse(SlideshowStage.consumesNavKeys(hasController = true, hasStatus = true))
        assertFalse(SlideshowStage.consumesNavKeys(hasController = false, hasStatus = true))
    }
}
