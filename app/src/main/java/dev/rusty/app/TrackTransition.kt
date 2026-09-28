package dev.rusty.app

import kotlin.math.PI
import kotlin.math.sin

/**
 * The on-screen half of the crossfade.
 *
 * [CrossfadeSettings] makes the audio overlap two tracks; this makes the picture overlap them for
 * the same length of time — cover art, palette wash and accent colour on one linear ramp, with the
 * title and artist trading over the middle third so the words are never half-legible.
 *
 * Pure maths: the fragment owns the animator and the views, this owns the choreography, so the
 * timings are pinned by unit tests instead of read out of an animator listener.
 */
object TrackTransition {

    /** With crossfade off there is no overlap to match, but a hard cut is what this replaces. */
    const val MIN_DURATION_MS = 350L

    /** The outgoing words hold until here, then fade out by [WORDS_SWAP_AT]. */
    const val WORDS_OUT_FROM = 0.35f

    /** Both titles are invisible at this instant; the text is swapped on it. */
    const val WORDS_SWAP_AT = 0.5f

    /** The incoming words are fully readable from here on. */
    const val WORDS_IN_UNTIL = 0.65f

    /** "Follow the audio": the dissolve is the crossfade, floored so a gapless cut still softens. */
    fun durationMs(crossfadeSeconds: Int): Long =
        (CrossfadeSettings.clamp(crossfadeSeconds) * 1_000L).coerceAtLeast(MIN_DURATION_MS)

    /**
     * The incoming cover sits on top of the outgoing one at this alpha, so the blend never dips
     * through the card's background the way two opposed fades would.
     */
    fun incomingAlpha(fraction: Float): Float = fraction.coerceIn(0f, 1f)

    fun outgoingWordAlpha(fraction: Float): Float =
        1f - ramp(fraction, WORDS_OUT_FROM, WORDS_SWAP_AT)

    fun incomingWordAlpha(fraction: Float): Float =
        ramp(fraction, WORDS_SWAP_AT, WORDS_IN_UNTIL)

    /**
     * How far the background darkens at the midpoint of the blend. The dim follows the dissolve as
     * an arch — down as the covers cross, back up under the new track's palette — so the boundary
     * reads as one breath rather than two stacked images.
     */
    const val DIM_PEAK = 0.45f

    /** The background dim at [fraction]: 0 at both ends, [DIM_PEAK] where the words trade. */
    fun dimAlpha(fraction: Float): Float =
        DIM_PEAK * sin(PI.toFloat() * fraction.coerceIn(0f, 1f))

    /**
     * How long a cover gets to load before the words stop waiting for it. The dissolve is what
     * hands the new title to the screen, so a load that never completes would otherwise leave the
     * outgoing track's words up indefinitely.
     */
    const val LOAD_GRACE_MS = 2_000L

    /** The deadline for the new track's words: a full dissolve plus the cover's grace. */
    fun wordsLandByMs(crossfadeSeconds: Int): Long =
        durationMs(crossfadeSeconds) + LOAD_GRACE_MS

    /** True once the title/artist text belongs to the incoming track. */
    fun wordsSwapped(fraction: Float): Boolean = fraction >= WORDS_SWAP_AT

    /**
     * A dissolve needs something to dissolve from: the first cover of a session — and one reloaded
     * into a recreated view — just appears, and an idle dashboard has no cover on screen at all.
     */
    fun shouldDissolve(hasOutgoingArt: Boolean, isActive: Boolean): Boolean =
        hasOutgoingArt && isActive

    /** 0 below [from], 1 from [to] on, linear between them. */
    private fun ramp(fraction: Float, from: Float, to: Float): Float =
        ((fraction - from) / (to - from)).coerceIn(0f, 1f)
}
