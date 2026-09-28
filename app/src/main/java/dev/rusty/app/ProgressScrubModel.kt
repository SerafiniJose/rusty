package dev.rusty.app

import kotlin.math.roundToLong

/**
 * Pure state machine behind the now-playing progress bar's seek gestures — touch drag/tap and
 * D-pad step — plus the "optimistic position" rule that keeps the bar where the user put it until
 * the receiver confirms the seek.
 *
 * Android-free (like [TvRemote] and [ReceiverStateStore]) so the whole decision table is JVM
 * unit-tested: the clock is injected and every input returns an [Effect] the caller executes.
 *
 * ## Position sources, in priority order
 * 1. A finger on the bar ([onDragStart]/[onDragMove]) — [previewMs] follows the finger.
 * 2. A D-pad step sequence ([onStep]) — [previewMs] is the accumulated target; the caller arms a
 *    [STEP_COMMIT_DELAY_MS] timer (re-armed on every press) and calls [onCommitTimer] when it
 *    fires, so a burst of presses becomes ONE seek.
 * 3. An issued seek ([Effect.Seek]) — [previewMs] holds the target until the store publishes a
 *    newer anchor generation (the receiver's `Seeked` republish, see
 *    [PlaybackAnchor.generation]) or [HOLD_TIMEOUT_MS] passes, whichever is first. This is what
 *    stops the bar snapping back to the pre-seek position for the round trip through librespot.
 * 4. Otherwise the store's position is the truth and [previewMs] is null.
 *
 * [acceptSnapshot] is the renderer's gate: it says whether a store position may be drawn right
 * now, and it is also how the model learns the current anchor generation.
 */
class ProgressScrubModel(private val clock: () -> Long) {

    /** What the caller must do after an input. The model never touches Android itself. */
    sealed class Effect {
        /** Ask the receiver to seek to [positionMs] (already clamped to the track). */
        data class Seek(val positionMs: Long) : Effect()

        /** Call [onCommitTimer] after [delayMs], cancelling any earlier arming. */
        data class ArmCommit(val delayMs: Long) : Effect()

        /** Nothing to do besides redrawing from [previewMs]. */
        object None : Effect()
    }

    /** The current track's length. Zero means "nothing to seek in": every gesture is a no-op. */
    var durationMs: Long = 0L
        set(value) {
            field = value.coerceAtLeast(0L)
        }

    private var dragMs: Long? = null
    private var pendingStepMs: Long? = null
    private var heldMs: Long? = null
    private var holdUntil = 0L
    private var holdGeneration = -1L
    private var lastSeenGeneration = -1L

    /** The position to draw while a gesture or hold is in force; null when the store is the truth. */
    val previewMs: Long?
        get() = dragMs ?: pendingStepMs ?: activeHold()

    /** A finger is down or a D-pad step burst is waiting for its commit. */
    val isGestureInFlight: Boolean
        get() = dragMs != null || pendingStepMs != null

    fun ratioToMs(ratio: Float): Long = (ratio.coerceIn(0f, 1f) * durationMs).roundToLong()

    fun msToRatio(ms: Long): Float =
        if (durationMs <= 0L) 0f else (ms.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)

    // ---- touch ----------------------------------------------------------------------------

    fun onDragStart(ratio: Float): Effect {
        if (durationMs <= 0L) return Effect.None
        // A finger overrides a half-finished D-pad sequence: those steps never commit.
        pendingStepMs = null
        dragMs = ratioToMs(ratio)
        return Effect.None
    }

    fun onDragMove(ratio: Float): Effect {
        if (dragMs == null) return Effect.None
        dragMs = ratioToMs(ratio)
        return Effect.None
    }

    fun onDragEnd(ratio: Float): Effect {
        if (dragMs == null) return Effect.None
        dragMs = null
        return issueSeek(ratioToMs(ratio))
    }

    fun onDragCancel(): Effect {
        dragMs = null
        return Effect.None
    }

    // ---- D-pad ----------------------------------------------------------------------------

    /**
     * One LEFT (direction < 0) or RIGHT (> 0) press with the key's auto-repeat count. Steps start
     * from the pending target, else from the held (just-seeked) target, else from
     * [livePositionMs]; a held key accelerates after [FAST_STEP_AFTER_REPEATS] repeats.
     */
    fun onStep(direction: Int, repeatCount: Int, livePositionMs: Long): Effect {
        if (durationMs <= 0L || direction == 0 || dragMs != null) return Effect.None
        val step = if (repeatCount >= FAST_STEP_AFTER_REPEATS) FAST_STEP_MS else STEP_MS
        val from = pendingStepMs ?: activeHold() ?: livePositionMs
        val delta = if (direction < 0) -step else step
        pendingStepMs = (from + delta).coerceIn(0L, durationMs)
        return Effect.ArmCommit(STEP_COMMIT_DELAY_MS)
    }

    /** The debounce elapsed: seek to the accumulated target, if any. */
    fun onCommitTimer(): Effect {
        val target = pendingStepMs ?: return Effect.None
        pendingStepMs = null
        return issueSeek(target)
    }

    // ---- store feedback -------------------------------------------------------------------

    /**
     * A store snapshot (or a 1 Hz tick built from one) carrying [anchorGeneration] wants to be
     * drawn. True when the store's position is the truth right now; false while a gesture is in
     * flight or an issued seek is still unconfirmed and within [HOLD_TIMEOUT_MS].
     */
    fun acceptSnapshot(anchorGeneration: Long): Boolean {
        lastSeenGeneration = anchorGeneration
        if (isGestureInFlight) return false
        if (heldMs == null) return true
        if (anchorGeneration > holdGeneration || clock() >= holdUntil) {
            heldMs = null
            return true
        }
        return false
    }

    /** Drops every gesture and hold (view destroyed). */
    fun reset() {
        dragMs = null
        pendingStepMs = null
        heldMs = null
    }

    private fun activeHold(): Long? = heldMs?.takeIf { clock() < holdUntil }

    private fun issueSeek(targetMs: Long): Effect {
        val target = targetMs.coerceIn(0L, durationMs)
        heldMs = target
        holdUntil = clock() + HOLD_TIMEOUT_MS
        holdGeneration = lastSeenGeneration
        return Effect.Seek(target)
    }

    companion object {
        const val STEP_MS = 10_000L
        const val FAST_STEP_MS = 30_000L
        const val FAST_STEP_AFTER_REPEATS = 5
        const val STEP_COMMIT_DELAY_MS = 500L
        const val HOLD_TIMEOUT_MS = 1_500L
    }
}
