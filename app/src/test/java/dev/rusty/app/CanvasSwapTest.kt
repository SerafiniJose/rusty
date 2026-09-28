package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Canvas layer should do when the resolver's state changes.
 *
 * The rule that matters: a track change publishes [CanvasState.Loading] before the new loop's URL
 * is known, and that must HOLD the loop already on screen rather than tear it down. Hiding on
 * Loading is what made a crossfade read as canvas → album art → dissolve → canvas.
 */
class CanvasSwapTest {

    @Test
    fun `loading holds the loop that is already on screen`() {
        assertEquals(
            CanvasAction.Hold,
            CanvasSwap.decide(showing = true, currentUrl = "a.mp4", next = CanvasState.Loading),
        )
    }

    @Test
    fun `loading does nothing when there is no loop to hold`() {
        assertEquals(
            CanvasAction.Nothing,
            CanvasSwap.decide(showing = false, currentUrl = null, next = CanvasState.Loading),
        )
    }

    @Test
    fun `a track without a canvas fades the loop out`() {
        assertEquals(
            CanvasAction.Hide,
            CanvasSwap.decide(showing = true, currentUrl = "a.mp4", next = CanvasState.None),
        )
    }

    @Test
    fun `nothing to do when there is no canvas and none is showing`() {
        assertEquals(
            CanvasAction.Nothing,
            CanvasSwap.decide(showing = false, currentUrl = null, next = CanvasState.None),
        )
    }

    @Test
    fun `the first canvas of a track starts from nothing`() {
        assertEquals(
            CanvasAction.Start("a.mp4"),
            CanvasSwap.decide(showing = false, currentUrl = null, next = CanvasState.Found("a.mp4")),
        )
    }

    @Test
    fun `the loop already playing is resumed, never restarted`() {
        // A re-resolve of the same track used to call play() again, restarting the loop mid-shot.
        // Resume is what says "leave the player alone" without also saying "leave the dip alone":
        // albums share one Canvas across tracks, and that track change opens a dip like any other.
        assertEquals(
            CanvasAction.Resume,
            CanvasSwap.decide(showing = true, currentUrl = "a.mp4", next = CanvasState.Found("a.mp4")),
        )
    }

    @Test
    fun `a canvas shared with the previous track still lifts the dip`() {
        // The failure this pins: Loading opens the dip, the resolve comes back with the SAME url,
        // and a decision of Nothing would leave the card parked on black for the whole track.
        val shared = CanvasSwap.decide(
            showing = true,
            currentUrl = "shared.mp4",
            next = CanvasState.Found("shared.mp4"),
        )
        assertTrue("a shared canvas must resume, not do nothing", shared is CanvasAction.Resume)
    }

    @Test
    fun `a different canvas swaps under the dip`() {
        assertEquals(
            CanvasAction.Swap("b.mp4"),
            CanvasSwap.decide(showing = true, currentUrl = "a.mp4", next = CanvasState.Found("b.mp4")),
        )
    }

    @Test
    fun `each half of the transition gets half the window`() {
        assertEquals(2_500L, CanvasSwap.rampMs(5_000L))
        assertEquals(6_000L, CanvasSwap.rampMs(12_000L))
    }

    @Test
    fun `a gapless boundary still ramps slowly enough to see`() {
        // TrackTransition's floor is 350 ms; half of that would read as a flicker.
        assertEquals(CanvasSwap.MIN_RAMP_MS, CanvasSwap.rampMs(TrackTransition.MIN_DURATION_MS))
    }

    @Test
    fun `the dip goes all the way to black`() {
        // The dip used to stop at 10% — of the VIDEO's own alpha, which darkens nothing: the Canvas
        // layer is stacked on the album-art card, so 10% video is 90% cover art, and that cover is
        // the one the on-screen crossfade is dissolving at that very instant. A dip that leaves
        // anything showing is a cross-dissolve to the picture behind it, not a dim.
        assertEquals(1f, CanvasSwap.DIM_FULL, 0f)
    }

    @Test
    fun `the media item is only swapped once the scrim is opaque`() {
        assertTrue(CanvasSwap.isUnderDip(CanvasSwap.DIM_FULL))
        // Animators land a hair short of their target; a hair is not a hole.
        assertTrue(CanvasSwap.isUnderDip(CanvasSwap.DIM_FULL - CanvasSwap.DIM_EPSILON / 2f))
        assertFalse("a scrim this thin still shows the art through it", CanvasSwap.isUnderDip(0.9f))
        assertFalse(CanvasSwap.isUnderDip(0f))
    }
}
