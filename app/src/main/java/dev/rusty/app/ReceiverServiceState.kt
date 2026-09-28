package dev.rusty.app

/**
 * Pure (Android-free) service-lifecycle state, the immutable snapshot, the playback anchor,
 * and the injected poster/clock abstractions consumed by [ReceiverStateStore].
 *
 * Kept Android-free on purpose so the whole store is unit-testable on the JVM. The concrete
 * Handler/SystemClock adapters live in the app singleton (Task 11), e.g.
 * `ReceiverStateStore(waiting(name), Handler(mainLooper)::post, SystemClock::elapsedRealtime)`.
 */
enum class ReceiverServiceState { UNKNOWN, STOPPED, STARTING, RUNNING, FAILED }

/**
 * Ground-truth playback position captured at a realtime instant, so consumers can extrapolate the
 * true current position on any screen.
 */
data class PlaybackAnchor(
    val elapsedMs: Long,
    val capturedRealtimeMs: Long,
    val playing: Boolean,
    /**
     * Bumped every time a playback event re-anchors the position — a seek, a pause/resume, a drift
     * correction, a track change. Lets a renderer tell "the anchor moved" from "the 1 Hz clock
     * ticked", which the seek UI needs: it holds an optimistic position until the receiver
     * confirms the seek with a new anchor. Compare generations, never elapsed values (a seek back
     * to the current position is still a confirmation).
     */
    val generation: Long = 0L,
) {
    companion object {
        val IDLE = PlaybackAnchor(elapsedMs = 0L, capturedRealtimeMs = 0L, playing = false)
    }
}

/**
 * Immutable, atomically-published view of the store: the reduced display state, its monotonically
 * increasing [revision], the current playback [anchor], and the [service] lifecycle — all consistent
 * with one another because they are produced together under a single transaction.
 */
data class ReceiverSnapshot(
    val state: ReceiverDashboardState,
    val revision: Long,
    val anchor: PlaybackAnchor,
    val service: ReceiverServiceState,
)

/** Posts a [Runnable] onto the delivery thread (the main looper in production). */
fun interface MainPoster { fun post(r: Runnable) }

/** Monotonic millisecond clock (elapsedRealtime in production); injected for testability. */
fun interface MonotonicClock { fun nowMs(): Long }
