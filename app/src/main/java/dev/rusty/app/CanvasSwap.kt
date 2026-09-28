package dev.rusty.app

/** What the Canvas layer should do about a new [CanvasState]. See [CanvasSwap.decide]. */
sealed interface CanvasAction {
    /** Leave the layer exactly as it is. */
    object Nothing : CanvasAction

    /** Keep the loop on screen and dip it towards the swap point — the next URL is still resolving. */
    object Hold : CanvasAction

    /** This track has no canvas: fade the loop out and give the codec back. */
    object Hide : CanvasAction

    /** Nothing is on screen: start this loop and fade it up. */
    data class Start(val url: String) : CanvasAction

    /**
     * The loop on screen is already the right one for this track: lift any dip, but do not touch
     * the player. Albums routinely share one Canvas across several tracks, and a re-resolve of the
     * track already playing lands here too.
     */
    object Resume : CanvasAction

    /** Replace the loop on screen with this one, under the dip so the shutter frame never shows. */
    data class Swap(val url: String) : CanvasAction
}

/**
 * The Canvas layer's half of a track transition, shared by the now-playing screen and the
 * lockscreen so the two can't drift apart.
 *
 * A track change publishes [CanvasState.Loading] before the new loop's URL is known. Treating that
 * as "hide" is what made a crossfade read as canvas → album art → dissolve → canvas; the loop is
 * held instead, dipped through black, swapped on the one ExoPlayer at the bottom of the dip — one
 * video codec at any instant, which is what keeps the librespot stream from being starved — and
 * brought back up under the new track.
 *
 * The dip rides a black scrim OVER the loop, never the loop's own alpha. A Canvas layer is always
 * stacked on the thing it replaces — the album-art card on the now-playing screen, the blurred wash
 * on the lockscreen — so fading the video out does not darken it, it cross-dissolves to the picture
 * behind it. That is the same cover art the on-screen crossfade is dissolving at that instant, which
 * is why a dip on the video's own alpha read as canvas → cover → next cover → next canvas.
 */
object CanvasSwap {

    /** The scrim is fully opaque at the swap point: the dip is a dip to black, and hides it all. */
    const val DIM_FULL = 1f

    /** Float slack for "the scrim has arrived" — animators land a hair off the target. */
    const val DIM_EPSILON = 0.01f

    /** A ramp shorter than this reads as a flicker rather than a transition. */
    const val MIN_RAMP_MS = 200L

    /** The fade for a loop arriving over nothing — no outgoing video to stay in step with. */
    const val ARRIVAL_FADE_MS = 300L

    /**
     * How long the layer may sit black with no URL in hand before it gives up and brings the
     * outgoing loop back. The resolve is a network call behind an 8 s timeout (twice that if the
     * token needs refreshing), and staring at a black card for that long is worse than the loop it
     * replaced; a URL that arrives afterwards simply dips again.
     */
    const val DIP_HOLD_MS = 2_000L

    /**
     * How long the incoming loop gets to put a frame on the surface before the scrim lifts anyway.
     * media3's shutter is transparent, so lifting the scrim over a surface that has not drawn yet
     * shows the album art through the video — the very thing the dip exists to prevent. Waiting for
     * the first frame costs nothing when the loop is quick and saves the transition when it is not.
     */
    const val FIRST_FRAME_GRACE_MS = 1_200L

    /** Down to black, then back up: half the transition each way. */
    fun rampMs(totalMs: Long): Long = (totalMs / 2).coerceAtLeast(MIN_RAMP_MS)

    /** True once the scrim is opaque enough that the media item can be replaced unseen. */
    fun isUnderDip(dim: Float): Boolean = dim >= DIM_FULL - DIM_EPSILON

    fun decide(showing: Boolean, currentUrl: String?, next: CanvasState): CanvasAction = when (next) {
        CanvasState.Loading -> if (showing) CanvasAction.Hold else CanvasAction.Nothing
        CanvasState.None -> if (showing) CanvasAction.Hide else CanvasAction.Nothing
        is CanvasState.Found -> when {
            !showing -> CanvasAction.Start(next.url)
            // The loop playing is already this one: resume it rather than restart it mid-shot. It
            // still has to be resumed and not ignored, or a track change into a Canvas shared with
            // the track before it would leave the dip it opened parked on black.
            next.url == currentUrl -> CanvasAction.Resume
            else -> CanvasAction.Swap(next.url)
        }
    }
}
