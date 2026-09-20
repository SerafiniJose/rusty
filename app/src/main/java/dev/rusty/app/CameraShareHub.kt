package dev.rusty.app

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The outcome of [CapturePipeline.start].
 *
 * [Stopped] is a distinct state rather than a [Failed] carrying an agreed reason string, because
 * the hub has to tell "the pipeline was torn down under me" from a real failure at two decisions —
 * whether to publish [CameraShareStatus.State.Unavailable], and whether a lens change failed — and
 * a string both files had to spell identically coupled them with nothing to enforce it: a reword on
 * either side would have turned every ordinary race into a user-visible "camera unavailable".
 */
sealed class PipelineStart {
    object Ok : PipelineStart()

    /** The start was abandoned because the pipeline was stopped while it ran: a deliberate
     *  teardown, so the hub stays quiet and lets whoever stopped it publish its own state. */
    object Stopped : PipelineStart()

    data class Failed(val reason: String) : PipelineStart()
}

interface PipelineListener {
    fun onCodecConfig(sps: ByteArray, pps: ByteArray)
    fun onAccessUnit(au: AccessUnit)
    fun onPipelineError(reason: String)

    /** The size the encoder was actually configured with, once per start; may be smaller than the
     *  requested resolution (pickSize). */
    fun onVideoSize(width: Int, height: Int) {}
}

/**
 * The camera + encoder, behind an interface so the hub is testable on the JVM: implemented by
 * `CameraCapturePipeline` and by fakes.
 *
 * Threading contract:
 * - NO method is called with the hub's state lock held, so an implementation is free to take its
 *   own locks and to call back into the hub. [start], [stop] and [requestKeyframe] are serialized
 *   against each other by the hub's camera mutex: two of them never overlap.
 * - [start] may block for as long as opening a camera takes (~4 s) and may deliver
 *   [PipelineListener.onCodecConfig] from inside itself.
 * - [stop] may be invoked RE-ENTRANTLY from the implementation's own error callback — the hub
 *   answers [PipelineListener.onPipelineError] by stopping the pipeline on whatever thread raised
 *   it — so it must not join or wait for its callback thread there, or it deadlocks against itself.
 *   It must also tolerate being called while a [start] on another thread is still opening (the hub
 *   shuts down that way) and being called twice; a stop on an already-stopped pipeline is a no-op.
 * - [grabJpeg] is deliberately called WITHOUT any hub lock, from an HTTP worker thread, while the
 *   camera keeps streaming to viewers. The hub serializes grabs so only one runs at a time (a
 *   single-listener ImageReader would otherwise have two callers clobbering each other), but it
 *   can overlap [start]/[stop] on another thread, so it must fail with null rather than throw when
 *   the camera is gone.
 *
 * [onAccessUnit][PipelineListener.onAccessUnit] must carry ONE complete access unit per call
 * (every NAL of a single encoded frame, all slices included): the RTP packetizer downstream sets
 * the marker bit on the last packet of the call, so a frame split across two calls would announce
 * two frame boundaries to the player.
 */
interface CapturePipeline {
    fun start(
        lens: CameraShareSettings.Lens,
        encoding: CameraShareSettings.Encoding,
        listener: PipelineListener,
    ): PipelineStart
    fun stop()
    fun requestKeyframe()
    fun grabJpeg(): ByteArray?      // blocking, <= 3 s, null on failure
}

/**
 * One viewer's frame receiver, implemented by the RTSP server's per-client handler.
 *
 * [onAccessUnit] MUST NOT block: it runs on the codec callback thread that feeds every other
 * viewer too, so a slow write here stalls the whole fan-out (the client handler queues instead).
 * It may detach from inside the callback, and anything it throws is caught and confined to that
 * one sink.
 *
 * The access unit's byte arrays are HANDED OVER, not lent. A sink may retain them for seconds —
 * RtspServer queues up to 4 s of video per viewer — and every sink is given the same arrays, so a
 * producer must not reuse or mutate a buffer it has passed here. Reusing one scratch array per NAL
 * to spare the codec thread some GC churn is the obvious optimisation and is exactly what this
 * forbids: it would corrupt frames only once a viewer's queue backed up, which is the hardest case
 * to reproduce. Copy out of MediaCodec's buffer, per access unit, every time.
 */
fun interface FrameSink { fun onAccessUnit(au: AccessUnit) }

/**
 * Returns a cancel function. Production: `Handler.postDelayed`; tests: manual.
 *
 * Called with the hub's state lock held, so it must only POST the task (and cancel a posted one) —
 * never run it inline in production.
 */
fun interface DelayScheduler { fun schedule(delayMs: Long, task: () -> Unit): () -> Unit }

/**
 * Fan-out between the capture pipeline and RTSP viewers, and the owner of the lazy camera rule:
 * the camera opens on the first DESCRIBE (or PLAY) and closes [lingerMs] after the last viewer
 * leaves, so a device that is merely advertising a share keeps its camera (and its indicator
 * light) off.
 *
 * ## Threading
 *
 * Four kinds of thread call in: one RTSP socket thread per connection ([describe], [attach],
 * [detach]), the Android main thread (the linger task and [shutdown]), an HTTP worker from a small
 * shared pool ([snapshotJpeg]), and the camera/codec callback thread (frames and errors).
 *
 * Three guards, always taken in this order and NEVER the other way round:
 *
 *     snapshotLock  ->  the camera mutex (cameraOp)  ->  lock
 *
 * - [lock] guards all mutable state, and nothing slow or re-entrant runs while it is held: no
 *   pipeline call, no sink delivery, no [publish], no [onError]. The only callout under it is
 *   [DelayScheduler.schedule] and the cancel function it returns, which merely post and un-post
 *   work. Every section under it is a handful of field assignments.
 * - The camera mutex is a one-slot flag (`cameraOp`) guarded by [lock] rather than a monitor of
 *   its own, precisely so it can be TAKEN under [lock] and WAITED ON outside it:
 *   `pipeline.start()` blocks for as long as the camera takes to open (~4 s) and must not be held
 *   over [lock], because the linger, [shutdown], [viewerCount] and every other viewer's socket
 *   thread would queue behind it. A caller that finds the mutex taken waits for the owner's result
 *   instead of opening a second camera; a stop requested while it is held is HANDED to the owner
 *   (`stopPending`) instead of racing it, and the owner re-checks state before releasing, so a
 *   [shutdown] or a failure arriving mid-start can neither be lost nor leave a camera running with
 *   nobody left to close it. Waiting for the mutex blocks the caller — fine for an RTSP socket or
 *   HTTP worker thread, which is why the main-thread paths ([shutdown], the linger) only ever
 *   take it when it is free and delegate otherwise.
 * - `snapshotLock` serializes [snapshotJpeg]: the pipeline holds ONE still slot, one image deep,
 *   and each grab empties it on the way in so the snapshot it returns is of NOW. Two overlapping
 *   grabs therefore discard and collect each other's stills, and each can time out waiting for a
 *   frame the other took. (The pipeline enforces this too rather than trusting us, but a grab that
 *   queues there spends its whole budget waiting, so the real fix is not to overlap them.)
 *
 * @param publish receives status changes in the order they were computed, one at a time, never
 *   under [lock]. It may re-enter the hub and it may throw: an exception is caught and confined to
 *   that one state (later states are still delivered), because it would otherwise land uncaught on
 *   the MediaCodec callback thread and take the process with it.
 */
class CameraShareHub(
    private val pipeline: CapturePipeline,
    private val lens: () -> CameraShareSettings.Lens,
    private val encoding: () -> CameraShareSettings.Encoding,
    private val scheduler: DelayScheduler,
    private val publish: (CameraShareStatus.State) -> Unit,
    private val configTimeoutMs: Long = 3_000,
    private val lingerMs: Long = 5_000,
    /** Injectable so the metering below is deterministic in tests. */
    private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() },
) : RtspStreamSource {

    /** Set by RtspServer: close every client socket. Called outside every lock. */
    @Volatile var onError: (String) -> Unit = {}

    private val lock = Any()
    private var running = false
    private var shut = false
    /** The camera mutex: non-null while some thread is inside a pipeline start/stop call. */
    private var cameraOp: CountDownLatch? = null
    /** A stop requested while the camera mutex was held; its owner runs it before releasing. */
    private var stopPending = false
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var configLatch = CountDownLatch(1)
    /** Bumped by every start attempt: a DESCRIBE that timed out may only tear down the generation
     *  it actually waited on. */
    private var configGeneration = 0
    private val sinks = ArrayList<FrameSink>()
    private var cancelLinger: (() -> Unit)? = null
    /** Bumped whenever a linger is armed or cancelled: a task that has already begun running
     *  cannot be un-posted, so it checks this before it stops anything. */
    private var lingerGeneration = 0
    /** Snapshots in flight; a grab may take seconds and holds the camera open across the linger. */
    private var snapshotsInFlight = 0
    private var lastStaged: CameraShareStatus.State? = null

    /** What actually leaves the encoder. Guarded by [lock] like every other field here, and NOT
     *  confined to the codec callback thread: `CameraCapturePipeline.stop()` only marks its rig
     *  detached and hands `codec.release()` to a worker, so a callback already past that check can
     *  still be inside [PipelineListener.onAccessUnit] while the starting thread resets this for
     *  the next camera. [StreamMeter] documents itself as not thread-safe, and its 64-bit window
     *  start is a plain field, so both the [StreamMeter.onFrame] and the [StreamMeter.reset] below
     *  run under the monitor. Neither is more than a few field writes. */
    private val meter = StreamMeter()
    private var videoWidth = 0
    private var videoHeight = 0
    private var lastFps = 0
    private var lastBps = 0

    private val snapshotLock = Any()

    private val pendingPublishes = ConcurrentLinkedQueue<CameraShareStatus.State>()
    private val publishing = AtomicBoolean(false)

    private val listener = object : PipelineListener {
        override fun onCodecConfig(sps: ByteArray, pps: ByteArray) {
            // MediaCodec's csd-0/csd-1 buffers carry an Annex-B start code. Both the SDP (a start
            // code makes profile-level-id read 000001, and media3 then mis-parses the SPS into a
            // black screen) and the parameter sets we prepend to keyframes need BARE NAL units.
            // The emptiness check has to be on the SPLIT RESULT: splitAnnexB returns an empty list
            // for a buffer that is only a start code, and falling back to the raw buffer would put
            // the start code itself in the SDP -- exactly the corruption this guards against. No
            // usable config means the latch stays down, so describe() times out and stops the
            // pipeline instead of publishing an SDP no client can decode.
            val bareSps = H264Nal.splitAnnexB(sps).firstOrNull() ?: return
            val barePps = H264Nal.splitAnnexB(pps).firstOrNull() ?: return
            if (bareSps.isEmpty() || barePps.isEmpty()) return
            synchronized(lock) {
                this@CameraShareHub.sps = bareSps
                this@CameraShareHub.pps = barePps
                configLatch.countDown()
            }
        }

        override fun onAccessUnit(au: AccessUnit) {
            val (targets, out) = synchronized(lock) {
                val s = sps; val p = pps
                // In-band parameter sets go in the SAME access unit as the IDR: one packetize()
                // call per frame is what keeps the RTP marker bit on the real frame boundary.
                val unit = if (au.keyframe && s != null && p != null) AccessUnit(listOf(s, p) + au.nals, au.ptsUs, true) else au
                sinks.toList() to unit
            }
            // One sink per try: a client handler that throws must not cost the later sinks this
            // frame, and must not escape onto the MediaCodec callback thread, where nothing would
            // catch it.
            for (t in targets) runCatching { t.onAccessUnit(out) }
            // What the ENCODER produced, not what the fan-out prepended: the in-band parameter
            // sets on a keyframe are the hub's doing, not the encoder's rate.
            val bytes = au.nals.sumOf { it.size }
            // Read outside the monitor: it is the one callout here, and nothing under [lock] needs
            // it to be the instant the lock was taken.
            val now = clock()
            val changed = synchronized(lock) {
                val sample = meter.onFrame(bytes, now)
                // The state gate comes BEFORE the rates are stored: a frame still in flight from a
                // camera that has just been stopped must not repopulate rates the stop cleared,
                // which the next viewer would then be shown as if they were its own.
                if (sample == null || sinks.isEmpty() || shut || !running) false
                else {
                    lastFps = dampFps(sample.fps)
                    lastBps = quantizeBps(sample.bps)
                    stagePublish(streamingLocked())
                    true
                }
            }
            if (changed) drainPublishes()
        }

        override fun onVideoSize(width: Int, height: Int) {
            synchronized(lock) { videoWidth = width; videoHeight = height }
        }

        override fun onPipelineError(reason: String) {
            var stop = false
            synchronized(lock) {
                if (shut) return
                stop = requestStopLocked()
                sinks.clear()
                stagePublish(CameraShareStatus.State.Unavailable(reason))
            }
            if (stop) runStopOwningCamera()
            drainPublishes()
            runCatching { onError(reason) }   // outside the lock: the server closes its clients
        }
    }

    override fun describe(): DescribeResult {
        // Opening the camera blocks this RTSP socket thread for seconds -- but never under [lock].
        ensureStarted()?.let { return DescribeResult.Unavailable(it) }
        val latch: CountDownLatch
        val gen: Int
        synchronized(lock) {
            if (shut) return DescribeResult.Unavailable(STOPPED)
            latch = configLatch
            gen = configGeneration
            if (sinks.isEmpty()) {
                stagePublish(CameraShareStatus.State.Ready)
                armLingerLocked()
            }
        }
        drainPublishes()
        if (!latch.await(configTimeoutMs, TimeUnit.MILLISECONDS)) return configFailed(gen)
        val ready = synchronized(lock) {
            val s = sps; val p = pps
            if (s == null || p == null || configGeneration != gen) null else DescribeResult.Ready(s, p)
        }
        return ready ?: configFailed(gen)
    }

    /** A started pipeline that never produced SPS/PPS is dead: stop it so the next DESCRIBE retries. */
    private fun configFailed(gen: Int): DescribeResult {
        var stop = false
        synchronized(lock) {
            // Only tear down the generation we actually waited on. A restartForLensChange swaps
            // the latch, so the new config counts down a latch this waiter never saw; stopping
            // here would kill the camera the lens change just reopened and publish an Unavailable
            // that nothing would ever clear.
            if (!shut && configGeneration == gen) {
                stop = requestStopLocked()
                stagePublish(CameraShareStatus.State.Unavailable(NO_OUTPUT))
            }
        }
        if (stop) runStopOwningCamera()
        drainPublishes()
        return DescribeResult.Unavailable(NO_OUTPUT)
    }

    fun attach(sink: FrameSink) {
        synchronized(lock) { if (shut) return }
        // Two attempts, same shape as snapshotJpeg(): the camera may have gone down in the gap
        // since ensureStarted() returned (a linger firing there, on another thread, races us) --
        // and skipping the attach outright would leave this sink attached to nothing until the
        // client reconnects. Retrying once reopens the camera instead.
        repeat(2) {
            // The camera may well be off here: nothing in RTSP requires a DESCRIBE before PLAY (a
            // client can replay a cached SDP), and a client that dawdles longer than the linger
            // between DESCRIBE and PLAY arrives after the camera has gone back to sleep. Attaching to
            // a stopped camera would publish "1 viewer" over a permanently black stream, so start it
            // -- blocking this socket thread, never [lock].
            if (ensureStarted() != null) return
            // The keyframe request is a pipeline callout like any other, so it goes under the camera
            // mutex (restartForLensChange does the same): MediaCodec.setParameters() overlapping the
            // codec.stop()/release() of a linger, a shutdown or a lens change is an
            // IllegalStateException at best and a native abort at worst -- which no runCatching sees.
            if (!acquireCamera()) return
            var attached = false
            try {
                attached = synchronized(lock) {
                    // The camera may have gone down in the gap since ensureStarted() returned: a
                    // linger firing there would leave this sink publishing "1 viewer" over a stopped
                    // camera, and the keyframe below would be the race above. Loop around instead of
                    // attaching to nothing -- the next ensureStarted() reopens the camera.
                    if (shut || !running) false
                    else {
                        cancelLingerLocked()
                        if (!sinks.contains(sink)) sinks.add(sink)
                        stagePublish(streamingLocked())
                        true
                    }
                }
                // The new viewer needs a keyframe to start decoding; the encoder is mid-GOP.
                if (attached) runCatching { pipeline.requestKeyframe() }
            } finally {
                releaseCamera()
            }
            if (attached) {
                drainPublishes()
                return
            }
        }
        drainPublishes()
    }

    fun detach(sink: FrameSink) {
        synchronized(lock) {
            if (!sinks.remove(sink)) return
            if (sinks.isEmpty()) {
                stagePublish(CameraShareStatus.State.Ready)
                armLingerLocked()
            } else {
                stagePublish(streamingLocked())
            }
        }
        drainPublishes()
    }

    fun viewerCount(): Int = synchronized(lock) { sinks.size }

    fun snapshotJpeg(): ByteArray? {
        // One grab at a time: the pipeline keeps ONE still slot, one image deep, and each grab
        // empties it on the way in so its snapshot is of NOW. Two overlapping grabs (two Home
        // Assistant polls, or HA and a browser tab) would throw away and collect each other's
        // stills, and each could time out waiting for the frame the other took.
        synchronized(snapshotLock) {
            // Two attempts: describe() leaves the camera on a linger, and a linger that fires in
            // the window between it and the in-flight guard below would stop the camera under the
            // grab.
            repeat(2) {
                if (describe() is DescribeResult.Unavailable) return null
                val held = synchronized(lock) {
                    if (running && !shut) { snapshotsInFlight++; cancelLingerLocked(); true } else false
                }
                if (held) {
                    try {
                        return pipeline.grabJpeg()
                    } finally {
                        synchronized(lock) {
                            snapshotsInFlight--
                            if (running && sinks.isEmpty() && snapshotsInFlight == 0) armLingerLocked()
                        }
                    }
                }
            }
        }
        return null
    }

    /**
     * Re-reads the lens, resolution, quality and frame rate from prefs and reopens the camera.
     *
     * BLOCKING: this is a synchronous camera close + open and takes SECONDS (the pipeline waits up
     * to 4 s for the device to open). It MUST NOT be called on the Android main thread -- the
     * prefs listener that reacts to a lens change has to hand it to a background thread.
     *
     * Deliberately does NOT wait for the new codec config: viewers pick the new parameter sets up
     * in-band from the keyframe that follows.
     */
    fun restartForLensChange() {
        synchronized(lock) { if (shut || !running) return }
        if (!acquireCamera()) return
        var failure: String? = null
        try {
            // Re-check under the mutex: the camera may have gone down while we queued for it.
            val hadSinks = synchronized(lock) {
                if (shut || !running) null
                else {
                    cancelLingerLocked()
                    running = false
                    clearMeasurementsLocked()
                    // The old lens's parameter sets die with the camera. Without this, a DESCRIBE
                    // that is already past ensureStarted() reads the retained sets and the old
                    // generation for the WHOLE length of the close, and answers with an SDP whose
                    // sprop-parameter-sets/profile-level-id describe the lens we are leaving.
                    resetConfigLocked()
                    sinks.isNotEmpty()
                }
            }
            if (hadSinks != null) {
                runCatching { pipeline.stop() }
                val outcome = runStartOwningCamera()
                when {
                    outcome == PipelineStart.Ok && hadSinks -> runCatching { pipeline.requestKeyframe() }
                    // The stop above dropped the linger; an idle camera must go straight back on
                    // one, and the linger generation makes the dropped task harmless if it was
                    // already running when we cancelled it.
                    outcome == PipelineStart.Ok -> synchronized(lock) { if (running && sinks.isEmpty()) armLingerLocked() }
                    // A shutdown landed mid-restart; it publishes Off.
                    outcome == PipelineStart.Stopped -> Unit
                    else -> {
                        val why = "camera failed to reopen"
                        failure = why
                        synchronized(lock) {
                            sinks.clear()
                            stagePublish(CameraShareStatus.State.Unavailable(why))
                        }
                    }
                }
            }
        } finally {
            releaseCamera()
        }
        drainPublishes()
        failure?.let { runCatching { onError(it) } }   // outside every lock: the server closes its clients
    }

    /** Terminal: the service is going away, so a straggler DESCRIBE must not reopen a camera that
     *  would then have no owner left to close it.
     *
     *  Never WAITS: it does not queue behind another thread that is four seconds deep in a camera
     *  open -- that thread is handed the stop and runs it before releasing. When the camera mutex
     *  IS free, this thread runs the stop itself, but that costs it nothing either:
     *  `CameraCapturePipeline.stop()` only detaches the rig and hands the actual session/device
     *  close and `codec.stop()`/`release()` to a release worker, so the main thread is never the
     *  one paying for a camera close. */
    fun shutdown() {
        var stop = false
        synchronized(lock) {
            if (shut) return
            shut = true
            stop = requestStopLocked()
            sinks.clear()
            stagePublish(CameraShareStatus.State.Off)
        }
        if (stop) runStopOwningCamera()
        drainPublishes()
    }

    // -- the camera mutex ------------------------------------------------------------------------

    /**
     * Makes sure the camera is up, WITHOUT holding [lock] across the multi-second
     * `pipeline.start()`. Returns null on success or the reason it could not start. Blocks the
     * caller (an RTSP socket or HTTP worker thread) -- never the main thread.
     */
    private fun ensureStarted(): String? {
        synchronized(lock) {
            if (shut) return STOPPED
            if (running) return null
        }
        // A concurrent caller waits here for the first one's result rather than opening a second
        // camera, and re-checks below in case that result was "already running".
        if (!acquireCamera()) return STOPPED
        var result: PipelineStart = PipelineStart.Ok
        try {
            val needsStart = synchronized(lock) {
                if (shut) result = PipelineStart.Stopped
                !shut && !running
            }
            if (needsStart) result = runStartOwningCamera()
        } finally {
            releaseCamera()
        }
        // Only a real failure is published: a deliberate teardown is [PipelineStart.Stopped], and
        // whoever asked for it publishes its own state (Off).
        val outcome = result
        if (outcome is PipelineStart.Failed) {
            synchronized(lock) { stagePublish(CameraShareStatus.State.Unavailable(outcome.reason)) }
        }
        drainPublishes()
        return when (outcome) {
            PipelineStart.Ok -> null
            PipelineStart.Stopped -> STOPPED
            is PipelineStart.Failed -> outcome.reason
        }
    }

    /** Blocks until this thread owns the camera mutex; false if the hub shut down while waiting. */
    private fun acquireCamera(): Boolean {
        while (true) {
            val busy = synchronized(lock) {
                if (shut) return false
                val op = cameraOp
                if (op == null) { cameraOp = CountDownLatch(1); return true }
                op
            }
            try {
                busy.await()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false   // treated as "camera share stopped": the caller is going away
            }
        }
    }

    /**
     * Retires the retained parameter sets and the latch that guards them: whatever the next start
     * produces belongs to a new generation, and a DESCRIBE holding the old latch must neither be
     * answered with the old sets nor mistake the new config for the one it asked for.
     *
     * Deliberately NOT called when a linger or an error merely stops the camera: the sets survive
     * that, so a snapshot that reopens the same camera does not have to wait for a config again.
     */
    private fun resetConfigLocked() {
        configGeneration++
        configLatch = CountDownLatch(1)
        sps = null; pps = null
    }

    /** Runs `pipeline.start()` with the camera mutex owned and [lock] released. [PipelineStart.Ok]
     *  means the camera is up; the other two are passed back for the caller to act on. */
    private fun runStartOwningCamera(): PipelineStart {
        // Both are prefs reads, i.e. callouts, so they happen outside the monitor.
        val target = lens()
        val enc = encoding()
        // A new camera means a new measurement. Under [lock], because a frame from the camera we
        // are replacing may still be inside onAccessUnit on the codec callback thread: the stop
        // that preceded us only detached its rig, it did not join that thread.
        clearMeasurements()
        val abandoned = synchronized(lock) {
            // A shutdown -- or a stop handed to us while a lens change was closing the camera --
            // that landed before we got here must not be answered by opening the camera again.
            // The check after the start would abandon it anyway, but only after four seconds with
            // the privacy light on.
            if (shut || stopPending) { stopPending = false; true }
            else { resetConfigLocked(); false }
        }
        if (abandoned) return PipelineStart.Stopped
        val result = runCatching { pipeline.start(target, enc, listener) }
            .getOrElse { PipelineStart.Failed(it.message ?: "camera failed to start") }
        var abandon = false
        val outcome = synchronized(lock) {
            when (result) {
                is PipelineStart.Failed -> result
                PipelineStart.Stopped -> PipelineStart.Stopped
                PipelineStart.Ok ->
                    if (shut || stopPending) { stopPending = false; abandon = true; PipelineStart.Stopped }
                    else { running = true; PipelineStart.Ok }
            }
        }
        // Still owning the mutex, so nothing else can be inside the pipeline: a shutdown or a stop
        // that arrived while we were opening is applied here rather than lost, and never leaves a
        // camera running with nobody to close it.
        if (abandon) runCatching { pipeline.stop() }
        return outcome
    }

    /** Runs `pipeline.stop()` outside [lock] and hands the mutex on. Caller must own the mutex. */
    private fun runStopOwningCamera() {
        try {
            runCatching { pipeline.stop() }
        } finally {
            releaseCamera()
        }
    }

    /** Releases the camera mutex, first draining any stop that was handed to us while we held it. */
    private fun releaseCamera() {
        while (true) {
            val again = synchronized(lock) {
                if (stopPending) { stopPending = false; true }
                else { cameraOp?.countDown(); cameraOp = null; false }
            }
            if (!again) return
            runCatching { pipeline.stop() }
        }
    }

    /**
     * Marks the camera stopped under [lock] and reports whether THIS caller must run the pipeline
     * stop (outside the monitor, via [runStopOwningCamera]). Never blocks, so the main thread's
     * linger and [shutdown] may call it: when another thread owns the camera mutex the stop is
     * handed to that owner instead.
     */
    private fun requestStopLocked(): Boolean {
        cancelLingerLocked()
        // Every stop path funnels through here (linger, shutdown, error, lens change): a stopped
        // camera has no measured size or rate, and a stale one would outlive the stream it
        // described.
        clearMeasurementsLocked()
        val wasRunning = running
        running = false
        if (cameraOp != null) { stopPending = true; return false }
        if (!wasRunning) return false
        cameraOp = CountDownLatch(1)
        return true
    }

    /** Zeroes the published measurements and retires the window they came from. Takes [lock];
     *  call it only where the monitor is free. */
    private fun clearMeasurements() = synchronized(lock) { clearMeasurementsLocked() }

    private fun clearMeasurementsLocked() {
        meter.reset()
        videoWidth = 0; videoHeight = 0; lastFps = 0; lastBps = 0
    }

    /**
     * Rounds a measured rate to the nearest 100 kbit/s.
     *
     * The byte-exact rate changes EVERY window, and [CameraShareStatus.publish] does no dedup of
     * its own, so an unrounded number would rebuild the foreground notification and re-run
     * `ControlService`'s advertisement refresh (a KeyStore/Tink construction and a file read) once
     * a second for the whole life of a share. Readers show one decimal of Mbit/s, so nothing
     * visible is lost. The frame rate is damped the same way, by [dampFps].
     */
    private fun quantizeBps(bps: Int): Int = Math.round(bps / 100_000.0).toInt() * 100_000

    /**
     * Holds the published frame rate still while the measured one only wobbles.
     *
     * A nominally 15 fps stream measures 14, 15 or 16 from window to window (frames straddle the
     * window edge), and republishing on every wobble would defeat [stagePublish]'s dedup exactly
     * as an unrounded bitrate would -- same cost, once a second. Anything further than 1 fps from
     * what is published is a REAL change (15 -> 30, or a stall down to 10) and goes through at
     * once. Call with [lock] held.
     */
    private fun dampFps(fps: Int): Int =
        // Nothing published yet (a fresh camera zeroes this): take the first sample as it is, or a
        // genuinely 1 fps stream would sit at zero forever.
        if (lastFps == 0 || Math.abs(fps - lastFps) > 1) fps else lastFps

    // -- linger ----------------------------------------------------------------------------------

    private fun armLingerLocked() {
        cancelLingerLocked()
        val gen = lingerGeneration
        cancelLinger = scheduler.schedule(lingerMs) { lingerFired(gen) }
    }

    private fun cancelLingerLocked() {
        lingerGeneration++
        cancelLinger?.invoke()
        cancelLinger = null
    }

    /** Runs on the main-thread Handler, so like [shutdown] it never WAITS for the camera mutex --
     *  a stop is handed to whoever holds it -- and even when the mutex IS free, the pipeline close
     *  that follows costs this thread nothing: `CameraCapturePipeline.stop()` hands the actual
     *  teardown to a release worker and returns immediately. */
    private fun lingerFired(gen: Int) {
        var stop = false
        synchronized(lock) {
            // A linger that has already begun running cannot be un-posted, so removeCallbacks is
            // not enough: only the current generation may stop anything, or a stale task would
            // stop a camera someone has just reopened (and cancel the fresh linger with it).
            if (gen != lingerGeneration || shut || !running) return
            // A snapshot in flight is holding the camera open; its own finally re-arms this.
            if (sinks.isNotEmpty() || snapshotsInFlight > 0) return
            stop = requestStopLocked()
        }
        if (stop) runStopOwningCamera()
    }

    // -- status ----------------------------------------------------------------------------------

    /** The current Streaming state, measurements included. Call with [lock] held. */
    private fun streamingLocked() =
        CameraShareStatus.State.Streaming(sinks.size, videoWidth, videoHeight, lastFps, lastBps)

    /** Queues a status change computed under [lock]; [drainPublishes] delivers it once the lock is
     *  released. Queue order is mutation order and only one thread drains at a time, so viewers
     *  never see two concurrent changes land out of order. Consecutive identical states are
     *  dropped (same shape as [CameraStatusRelay.publish]): Home Assistant polls the snapshot
     *  endpoint, and each poll would otherwise re-publish Ready and rebuild the foreground
     *  notification. */
    private fun stagePublish(state: CameraShareStatus.State) {
        if (state == lastStaged) return
        lastStaged = state
        pendingPublishes.add(state)
    }

    private fun drainPublishes() {
        while (true) {
            // A thread that loses the race leaves its state to the current drainer.
            if (!publishing.compareAndSet(false, true)) return
            try {
                while (true) {
                    val next = pendingPublishes.poll() ?: break
                    // A publish that throws must not wedge the queue for every later state, and
                    // must not escape onto the MediaCodec callback thread before onError has told
                    // the server to close its clients.
                    runCatching { publish(next) }
                }
            } finally {
                publishing.set(false)
            }
            // Re-check: a state staged between the poll and the release would otherwise sit there.
            if (pendingPublishes.isEmpty()) return
        }
    }

    private companion object {
        const val NO_OUTPUT = "no encoder output"
        const val STOPPED = "camera share stopped"
    }
}
