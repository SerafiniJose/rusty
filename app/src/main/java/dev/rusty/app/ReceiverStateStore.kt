package dev.rusty.app

import androidx.annotation.VisibleForTesting
import java.util.ArrayDeque

/**
 * Serialized, reduced-state store on top of the pure [reduceReceiverState].
 *
 * Pure JVM (no `android.*`): the main-thread [poster] and monotonic [clock] are injected, so the
 * full ordering/atomicity behaviour is unit-testable on the JVM.
 *
 * ## One dispatch = one revision
 * Every [dispatch] / [transitionService] / [addListener] applies its content event AND optional
 * service transition together, bumps a single [revision] counter, and builds one immutable
 * [ReceiverSnapshot]. Consumers therefore never observe a torn combination such as "display Off"
 * paired with "service RUNNING".
 *
 * ## Scheduled-drain ordered delivery (the crux)
 * The naive "enqueue then post" is lossy: two writer threads could post the rev-2 task before the
 * rev-1 task, and a revision guard would then drop rev 1. Instead, snapshots are appended to one
 * shared [pending] queue under [lock]; exactly ONE [drainRunnable] is ever in flight ([drainScheduled]),
 * and it pops snapshots in FIFO order, invoking listeners OUTSIDE the lock. This guarantees listeners
 * see every revision exactly once, strictly increasing, regardless of how many threads dispatch.
 *
 * Because listeners run on the poster thread and never under the lock, a listener may re-enter the
 * store (read [snapshot], even dispatch) without deadlocking.
 */
class ReceiverStateStore(
    initial: ReceiverDashboardState,
    private val poster: MainPoster,
    private val clock: MonotonicClock,
) {
    fun interface Listener { fun onSnapshot(snapshot: ReceiverSnapshot) }

    private val lock = Any()

    // --- mutable state, all guarded by `lock` ---
    private var currentState: ReceiverDashboardState = initial
    private var currentService: ReceiverServiceState = ReceiverServiceState.UNKNOWN
    private var currentAnchor: PlaybackAnchor = PlaybackAnchor.IDLE
    private var revision: Long = 0L
    private var currentSnapshot: ReceiverSnapshot =
        ReceiverSnapshot(initial, 0L, PlaybackAnchor.IDLE, ReceiverServiceState.UNKNOWN)

    /** FIFO of snapshots awaiting delivery; one [drainRunnable] drains it. Guarded by `lock`. */
    private val pending = ArrayDeque<ReceiverSnapshot>()
    private var drainScheduled = false

    /** Listener wrappers, each tracking its own last-delivered revision (defensive dedupe). */
    private val listeners = ArrayList<Wrapper>()

    /**
     * Atomic read of the latest immutable snapshot. Safe to call from any thread, including from
     * inside a [Listener.onSnapshot] callback.
     */
    val snapshot: ReceiverSnapshot
        get() = synchronized(lock) { currentSnapshot }

    /**
     * Applies [event] and an optional [service] transition as ONE revision. For [ReceiverEvent.Playback]
     * the playback anchor is derived from the event inside the same transaction (no separate anchor
     * call needed).
     */
    fun dispatch(event: ReceiverEvent, service: ReceiverServiceState? = null) {
        val shouldPost = synchronized(lock) {
            val nextState = reduceReceiverState(currentState, event)
            val nextAnchor = if (event is ReceiverEvent.Playback) anchorFor(event.event) else currentAnchor
            commitLocked(nextState, nextAnchor, service)
        }
        if (shouldPost) poster.post(drainRunnable)
    }

    /** Service-only change. Still one revision (display/anchor unchanged). */
    fun transitionService(service: ReceiverServiceState) {
        val shouldPost = synchronized(lock) {
            commitLocked(currentState, currentAnchor, service)
        }
        if (shouldPost) poster.post(drainRunnable)
    }

    /** The true current playback position, extrapolated from the last anchor while playing. */
    fun liveElapsedMs(): Long {
        val anchor = synchronized(lock) { currentAnchor }
        return if (anchor.playing) {
            anchor.elapsedMs + (clock.nowMs() - anchor.capturedRealtimeMs)
        } else {
            anchor.elapsedMs
        }
    }

    /**
     * Registers [l] and schedules an INITIAL-delivery snapshot through the SAME [pending] queue, so
     * its first delivery cannot overtake a concurrent dispatch.
     */
    fun addListener(l: Listener) {
        val shouldPost = synchronized(lock) {
            // Seed the wrapper's last-delivered revision to one BELOW the current snapshot, so any
            // snapshot committed BEFORE this registration that still lingers in `pending` is dropped
            // by the wrapper's revision guard. The new listener only ever sees the current snapshot
            // (enqueued just below) and future ones — never replayed pre-registration history.
            listeners.add(Wrapper(l, seedRevision = currentSnapshot.revision - 1))
            pending.addLast(currentSnapshot)
            val wasScheduled = drainScheduled
            drainScheduled = true
            !wasScheduled
        }
        if (shouldPost) poster.post(drainRunnable)
    }

    fun removeListener(l: Listener) {
        synchronized(lock) {
            val it = listeners.iterator()
            while (it.hasNext()) {
                if (it.next().delegate === l) {
                    it.remove()
                    return
                }
            }
        }
    }

    /**
     * Returns the current number of registered listeners. Test-only: used to assert that
     * no listener is leaked across bg/fg cycles. Does NOT change runtime behaviour — it only
     * reads [listeners].size under the existing [lock].
     */
    @VisibleForTesting
    fun listenerCount(): Int = synchronized(lock) { listeners.size }

    // --- internals ---

    /** Builds the anchor for a playback event exactly as the old `anchorPlayback` did. */
    private fun anchorFor(event: ReceiverDashboardPlaybackEvent): PlaybackAnchor = PlaybackAnchor(
        elapsedMs = event.elapsedMs.coerceAtLeast(0L),
        capturedRealtimeMs = clock.nowMs(),
        playing = event.playbackState == ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
    )

    /**
     * Must be called under [lock]. Mutates state/anchor/service, bumps [revision], builds the snapshot,
     * appends it to [pending], and returns whether THIS caller must post the (single) drain task.
     */
    private fun commitLocked(
        nextState: ReceiverDashboardState,
        nextAnchor: PlaybackAnchor,
        service: ReceiverServiceState?,
    ): Boolean {
        currentState = nextState
        currentAnchor = nextAnchor
        if (service != null) currentService = service
        revision += 1
        val snap = ReceiverSnapshot(currentState, revision, currentAnchor, currentService)
        currentSnapshot = snap
        pending.addLast(snap)
        val wasScheduled = drainScheduled
        drainScheduled = true
        return !wasScheduled
    }

    private val drainRunnable = Runnable {
        while (true) {
            // Snapshot the listener set together with the popped item under the lock; when the queue
            // is empty, clear the scheduled flag under the same lock and stop.
            val item: ReceiverSnapshot
            val targets: List<Wrapper>
            synchronized(lock) {
                if (pending.isEmpty()) {
                    drainScheduled = false
                    return@Runnable
                }
                item = pending.removeFirst()
                targets = ArrayList(listeners)
            }
            // Invoke listeners OUTSIDE the lock so they may re-enter the store without deadlock.
            for (w in targets) w.deliver(item)
        }
    }

    /** Per-listener wrapper that drops any snapshot whose revision <= its last-delivered (defensive). */
    private class Wrapper(val delegate: Listener, seedRevision: Long = -1L) {
        private var lastRevision: Long = seedRevision
        fun deliver(snapshot: ReceiverSnapshot) {
            if (snapshot.revision <= lastRevision) return
            lastRevision = snapshot.revision
            delegate.onSnapshot(snapshot)
        }
    }
}
