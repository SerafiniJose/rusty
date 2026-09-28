package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The on-screen half of the crossfade: how long the dissolve lasts and what each layer's alpha is
 * at a given point through it. Pure maths so the choreography is pinned here instead of inside an
 * animator listener.
 */
class TrackTransitionTest {

    // ---- Duration: "follow the audio" -------------------------------------------------------

    @Test
    fun `dissolve lasts exactly as long as the audio crossfade`() {
        assertEquals(8_000L, TrackTransition.durationMs(8))
        assertEquals(1_000L, TrackTransition.durationMs(1))
        assertEquals(12_000L, TrackTransition.durationMs(12))
    }

    @Test
    fun `a gapless boundary still gets the short floor dissolve`() {
        // Crossfade off means there is no overlap to match, but a hard cut is what this replaces.
        assertEquals(TrackTransition.MIN_DURATION_MS, TrackTransition.durationMs(0))
    }

    @Test
    fun `an out-of-range crossfade is clamped like the setting itself`() {
        assertEquals(12_000L, TrackTransition.durationMs(99))
        assertEquals(TrackTransition.MIN_DURATION_MS, TrackTransition.durationMs(-4))
    }

    // ---- Art + wash + accent: one linear ramp ----------------------------------------------

    @Test
    fun `the incoming cover fades up linearly across the window`() {
        assertEquals(0f, TrackTransition.incomingAlpha(0f), 0.001f)
        assertEquals(0.25f, TrackTransition.incomingAlpha(0.25f), 0.001f)
        assertEquals(0.5f, TrackTransition.incomingAlpha(0.5f), 0.001f)
        assertEquals(1f, TrackTransition.incomingAlpha(1f), 0.001f)
    }

    @Test
    fun `alphas stay inside zero and one when the fraction overshoots`() {
        assertEquals(0f, TrackTransition.incomingAlpha(-0.3f), 0.001f)
        assertEquals(1f, TrackTransition.incomingAlpha(1.4f), 0.001f)
    }

    // ---- Words: out, swap, in, over the middle third ----------------------------------------

    @Test
    fun `the outgoing title holds while the covers do the early blending`() {
        assertEquals(1f, TrackTransition.outgoingWordAlpha(0f), 0.001f)
        assertEquals(1f, TrackTransition.outgoingWordAlpha(0.2f), 0.001f)
        assertEquals(1f, TrackTransition.outgoingWordAlpha(TrackTransition.WORDS_OUT_FROM), 0.001f)
    }

    @Test
    fun `both titles are invisible at the midpoint so the words never double-expose`() {
        assertEquals(0f, TrackTransition.outgoingWordAlpha(0.5f), 0.001f)
        assertEquals(0f, TrackTransition.incomingWordAlpha(0.5f), 0.001f)
    }

    @Test
    fun `the incoming title is fully readable once the middle third is over`() {
        assertEquals(1f, TrackTransition.incomingWordAlpha(TrackTransition.WORDS_IN_UNTIL), 0.001f)
        assertEquals(1f, TrackTransition.incomingWordAlpha(1f), 0.001f)
    }

    @Test
    fun `the words change over at the midpoint`() {
        assertFalse(TrackTransition.wordsSwapped(0.49f))
        assertTrue(TrackTransition.wordsSwapped(0.5f))
        assertTrue(TrackTransition.wordsSwapped(1f))
    }

    // ---- The background dim that follows the blend ---------------------------------------------

    @Test
    fun `the background is undimmed at both ends of the transition`() {
        assertEquals(0f, TrackTransition.dimAlpha(0f), 0.001f)
        assertEquals(0f, TrackTransition.dimAlpha(1f), 0.001f)
    }

    @Test
    fun `the dim peaks where the words trade`() {
        assertEquals(TrackTransition.DIM_PEAK, TrackTransition.dimAlpha(TrackTransition.WORDS_SWAP_AT), 0.001f)
    }

    @Test
    fun `the dim arches symmetrically so it comes back the way it went`() {
        val early = TrackTransition.dimAlpha(0.25f)
        val late = TrackTransition.dimAlpha(0.75f)
        assertEquals(early, late, 0.001f)
        assertTrue("a quarter in should already be dimming", early > 0f)
        assertTrue("but never past the peak", early < TrackTransition.DIM_PEAK)
    }

    @Test
    fun `a fraction outside the window leaves the background alone`() {
        assertEquals(0f, TrackTransition.dimAlpha(-0.4f), 0.001f)
        assertEquals(0f, TrackTransition.dimAlpha(1.6f), 0.001f)
    }

    // ---- The words land even when the cover never arrives -------------------------------------

    @Test
    fun `the new track's words are on screen within a crossfade of the boundary`() {
        // The hold is armed when the incoming cover STARTS loading, and a load that never completes
        // (Coil cannot size a view it cannot measure; a stalled network does the same) must not
        // leave the outgoing track's title on screen forever. This is the backstop's deadline.
        assertEquals(
            8_000L + TrackTransition.LOAD_GRACE_MS,
            TrackTransition.wordsLandByMs(8),
        )
        assertEquals(
            5_000L + TrackTransition.LOAD_GRACE_MS,
            TrackTransition.wordsLandByMs(5),
        )
    }

    @Test
    fun `a gapless boundary gives the cover the same grace, not the floor alone`() {
        assertEquals(
            TrackTransition.MIN_DURATION_MS + TrackTransition.LOAD_GRACE_MS,
            TrackTransition.wordsLandByMs(0),
        )
    }

    // ---- When to dissolve at all -------------------------------------------------------------

    @Test
    fun `a cover arriving over an empty card just appears`() {
        // First track of a session, or a card rebuilt after the view was recreated: nothing to
        // dissolve from, so the normal load path (and its own short fade) owns it.
        assertFalse(TrackTransition.shouldDissolve(hasOutgoingArt = false, isActive = true))
    }

    @Test
    fun `nothing dissolves while the dashboard is idle`() {
        assertFalse(TrackTransition.shouldDissolve(hasOutgoingArt = true, isActive = false))
    }

    @Test
    fun `a new cover over a showing one dissolves`() {
        assertTrue(TrackTransition.shouldDissolve(hasOutgoingArt = true, isActive = true))
    }
}
