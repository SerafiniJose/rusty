package dev.rusty.app

/** How a snapshot for a tile is obtained. */
enum class JobKind {
    /** Pull the camera's still-image (`snapshotUrl`) over HTTP. */
    HTTP,

    /**
     * Pull the still-image over HTTP and, if that fails, fall back to a frame grab within whatever
     * is left of the job's deadline. A snapshot URL can be broken in ways the camera itself is not
     * — a redirect the fetcher deliberately refuses to follow, a still too large for
     * [SnapshotGuards.MAX_BYTES], a `cgi-bin` endpoint that answers `500` — and a working RTSP
     * stream should still fill the tile.
     */
    HTTP_THEN_FRAME,

    /** Open the RTSP stream briefly and decode a single frame. */
    FRAME_GRAB,
}

/**
 * One snapshot fetch the runtime (Task 9) should perform. [deadlineAt] is the wall-clock instant
 * after which the scheduler gives up on it — see [SnapshotScheduler.onJobDeadline].
 */
data class SnapshotJob(val cameraId: String, val kind: JobKind, val deadlineAt: Long)

/**
 * What the camera grid should draw for a tile.
 *
 * - [OK] — a recent frame exists.
 * - [STALE] — an attempt has been made but the newest frame (if any) is older than three refresh
 *   intervals.
 * - [UNREACHABLE] — the last two attempts in a row failed.
 * - [NONE] — nothing has ever been fetched and none is pending, or the camera has no snapshot
 *   mechanism at all (no `snapshotUrl` and frame grabbing disallowed), or the id is unknown.
 */
enum class TileState { OK, STALE, UNREACHABLE, NONE }

/**
 * Pure, virtual-time planner for the camera grid's thumbnail refresh.
 *
 * It holds no threads, no I/O and no clock: the caller ticks it with an explicit `now` and reports
 * results back. The policy it enforces:
 *
 * - **Serialized** — at most one job is in flight at a time, so N cameras never open N sockets at
 *   once on a memory-constrained Echo Show.
 * - **Round-robin** — a rotating cursor over the position-ordered camera list, so a camera whose
 *   fetch always returns first cannot starve the others.
 * - **Coalescing** — a camera that missed several refresh intervals gets one job, not a backlog;
 *   its next due time is measured from the completion, never backfilled.
 * - **Deadline-aware** — a job that never reports back is written off after [jobDeadlineMs] and
 *   counted as a failure, so a wedged frame grab cannot block the rotation forever.
 * - **Suspendable** — live view or DLNA video suspends everything (they need the decoder and the
 *   bandwidth); a camera being connection-tested suspends just itself. While fully suspended
 *   deadlines are frozen: an in-flight job is not written off for time spent suspended.
 */
class SnapshotScheduler(
    private val refreshIntervalMs: Long,
    private val jobDeadlineMs: Long = 15_000L,
) {

    private class CameraState(var record: CameraRecord) {
        /** When the newest successful fetch landed, or null if none ever did. */
        var lastOk: Long? = null

        /** Failures (bad result or expired deadline) since the last success. */
        var consecutiveFailures: Int = 0

        /** Earliest `now` at which this camera may be scheduled again. */
        var dueAt: Long = DUE_NOW

        /** When the current run of failures began, or null while there is none. */
        var offlineSince: Long? = null
    }

    private val states = LinkedHashMap<String, CameraState>()

    /** Camera ids in scheduling order (by position, then id for a stable tiebreak). */
    private var order: List<String> = emptyList()

    /** Id the next scan starts at; null (or an unknown id) starts at the head of [order]. */
    private var cursorId: String? = null

    private var frameGrabAllowed: Boolean = false

    private var inFlight: SnapshotJob? = null

    private var liveViewOpen: Boolean = false
    private var dlnaVideo: Boolean = false
    private var testRunning: Set<String> = emptySet()

    /** True while something else owns the decoder/bandwidth and nothing at all may be scheduled. */
    private val fullySuspended: Boolean
        get() = liveViewOpen || dlnaVideo

    /**
     * Reconciles the camera set. Bookkeeping (last success, failure streak, next due time) is kept
     * for ids that survive and dropped for ids that disappear; a newly added camera is due at once.
     * If the in-flight job's camera was removed its slot is freed.
     */
    fun setCameras(cameras: List<CameraRecord>, frameGrabAllowed: Boolean) {
        this.frameGrabAllowed = frameGrabAllowed
        val keep = cameras.map { it.id }.toSet()
        states.keys.retainAll(keep)
        for (cam in cameras) {
            val existing = states[cam.id]
            if (existing == null) {
                states[cam.id] = CameraState(cam)
            } else {
                existing.record = cam
            }
        }
        order = cameras.sortedWith(compareBy({ it.position }, { it.id })).map { it.id }
        if (cursorId !in keep) cursorId = null
        val flight = inFlight
        if (flight != null && flight.cameraId !in keep) inFlight = null
    }

    /**
     * Updates what is currently blocking refreshes. [liveViewOpen] and [dlnaVideo] suspend the whole
     * scheduler; ids in [testRunning] (cameras whose connection is being tested from settings) are
     * skipped individually while the rest keep their turns.
     */
    fun setSuspended(liveViewOpen: Boolean, dlnaVideo: Boolean, testRunning: Set<String>) {
        this.liveViewOpen = liveViewOpen
        this.dlnaVideo = dlnaVideo
        this.testRunning = testRunning.toSet()
    }

    /**
     * Returns the next job to run at [now], or null when the scheduler is suspended, a job is
     * already in flight, or nothing is due. The returned job is marked in flight; the caller must
     * report back through [onJobFinished] or let [onJobDeadline] write it off.
     */
    fun onTick(now: Long): SnapshotJob? {
        if (fullySuspended) return null
        if (inFlight != null) return null
        if (order.isEmpty()) return null

        val start = cursorId?.let { order.indexOf(it) }.takeIf { it != null && it >= 0 } ?: 0
        for (offset in order.indices) {
            val index = (start + offset) % order.size
            val id = order[index]
            if (id in testRunning) continue
            val state = states[id] ?: continue
            val kind = kindFor(state.record) ?: continue
            if (now < state.dueAt) continue

            val job = SnapshotJob(cameraId = id, kind = kind, deadlineAt = now + jobDeadlineMs)
            inFlight = job
            cursorId = order[(index + 1) % order.size]
            return job
        }
        return null
    }

    /**
     * Reports the outcome of the in-flight job. A report for a camera that is not in flight (a late
     * reply for a job already written off, say) is ignored. Either way the camera becomes due again
     * one refresh interval from [now] — a missed backlog is never replayed.
     */
    fun onJobFinished(cameraId: String, now: Long, ok: Boolean) {
        if (inFlight?.cameraId != cameraId) return
        inFlight = null
        val state = states[cameraId] ?: return
        if (ok) {
            state.lastOk = now
            state.consecutiveFailures = 0
            state.offlineSince = null
        } else {
            recordFailure(state, now)
        }
        state.dueAt = now + refreshIntervalMs
    }

    /**
     * Writes off an in-flight job whose deadline has passed, returning its camera id (and counting
     * a failure), or null if nothing expired. Returns null while fully suspended: deadlines do not
     * run down during a suspension, so resuming does not immediately kill the pending job.
     */
    fun onJobDeadline(now: Long): String? {
        if (fullySuspended) return null
        val job = inFlight ?: return null
        if (job.deadlineAt > now) return null
        inFlight = null
        val state = states[job.cameraId]
        if (state != null) {
            recordFailure(state, now)
            state.dueAt = now + refreshIntervalMs
        }
        return job.cameraId
    }

    private fun recordFailure(state: CameraState, now: Long) {
        if (state.consecutiveFailures == 0) state.offlineSince = state.lastOk ?: now
        state.consecutiveFailures++
    }

    /** What the grid should draw for [cameraId] at [now]. See [TileState]. */
    fun tileState(cameraId: String, now: Long): TileState {
        val state = states[cameraId] ?: return TileState.NONE
        if (state.consecutiveFailures >= 2) return TileState.UNREACHABLE
        val lastOk = state.lastOk
        if (lastOk == null) {
            // Never got a frame: STALE once we have actually tried and failed, otherwise there is
            // simply nothing to show yet.
            return if (state.consecutiveFailures > 0) TileState.STALE else TileState.NONE
        }
        return if (now - lastOk > 3 * refreshIntervalMs) TileState.STALE else TileState.OK
    }

    /** Failures since [cameraId]'s last success; 0 for an unknown id. */
    fun consecutiveFailures(cameraId: String): Int = states[cameraId]?.consecutiveFailures ?: 0

    /** When the current run of failures began (the last good frame's time, else the first
     *  failure's), or null if the camera is fine or unknown. */
    fun offlineSince(cameraId: String): Long? = states[cameraId]?.offlineSince

    /** The job currently out, or null. */
    fun inFlightJob(): SnapshotJob? = inFlight

    private fun kindFor(record: CameraRecord): JobKind? = when {
        !record.snapshotUrl.isNullOrBlank() ->
            if (frameGrabAllowed) JobKind.HTTP_THEN_FRAME else JobKind.HTTP
        frameGrabAllowed -> JobKind.FRAME_GRAB
        else -> null
    }

    private companion object {
        /** A [CameraState.dueAt] that is in the past for any plausible `now`. */
        const val DUE_NOW = Long.MIN_VALUE
    }
}
