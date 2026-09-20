package dev.rusty.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.Buffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock

private const val TAG = "CameraCapturePipeline"

/** How long a use of a [Rig]'s native objects will wait for a concurrent teardown before giving
 *  up. Ordinary contention is microseconds; anything near this means the rig is going away, and
 *  every use has a correct answer for that — so the wait is bounded rather than unbounded. */
private const val RIG_GUARD_TIMEOUT_MS = 250L

/**
 * Camera2 -> MediaCodec H.264 Surface encoder -> Annex-B NAL units. One instance per service; the
 * hub drives start/stop. All camera and codec callbacks run on [thread]. Snapshots come from a
 * JPEG ImageReader added as a second session target: a single still capture, not a per-frame cost.
 *
 * This is the only file in the feature that touches `android.hardware.camera2` / `android.media`,
 * and the only one with no JVM test — Camera2 and MediaCodec have no JVM implementation. Every
 * invariant it relies on is therefore spelled out rather than asserted.
 *
 * ## Threading
 *
 * Three kinds of thread call in: the hub's caller (an RTSP socket thread for [start]/[stop]/
 * [requestKeyframe], an HTTP worker for [grabJpeg], the main thread for a linger or [close]),
 * [thread] itself (every camera and codec callback), and the release worker.
 *
 * Two guards, and neither is ever held across a call into the other:
 * - [lock] guards [rig], [listener] and [generation] and NOTHING slow: no MediaCodec call, no
 *   Camera2 call, no listener delivery. It is a leaf. Holding it across `codec.release()` would
 *   deadlock outright — release waits for the in-flight callback on [thread], and a callback that
 *   is failing wants this lock.
 * - the [Rig] monitor guards use-versus-release of the native objects, so a stop on one thread
 *   cannot free a codec another thread is mid-call on (that is a native abort, not an exception).
 *
 * [stop] never joins [thread]: the hub answers [PipelineListener.onPipelineError] by calling it
 * re-entrantly, on the very thread that raised the error. Every teardown is handed to a release
 * worker rather than run inline — `MediaCodec.release()` from inside a `MediaCodec.Callback` is a
 * documented deadlock, and the main thread must not pay a camera close either (the hub's linger and
 * its shutdown both run there). [start] awaits the outstanding one before it opens a camera, so
 * "the previous camera is shut before the next is opened" survives the asynchrony.
 */
class CameraCapturePipeline(context: Context) : CapturePipeline {

    /** The APPLICATION context: this object owns a HandlerThread and a release worker that outlive
     *  the Activity or Service that built it, and retaining either would leak it. */
    private val appContext: Context = context.applicationContext

    companion object {
        /**
         * 2 seconds, and never 0 or -1. When a viewer stalls, the server flushes that client's
         * queue and resynchronises it on the next IDR; nothing can ask the encoder for a keyframe
         * from the writer thread, so the I-frame interval is the ONLY recovery path. An
         * all-keyframe (0) or single-keyframe (-1) stream would either blow the bitrate or leave a
         * stalled viewer frozen until it reconnects.
         */
        const val IFRAME_INTERVAL_S = 2

        /** The Echo Show's hardware privacy gate does not fail an open, it never answers one. */
        private const val OPEN_TIMEOUT_MS = 4_000L
        private const val JPEG_TIMEOUT_MS = 3_000L
        private const val JPEG_BUFFERS = 2

        /** How long a [start] waits for the PREVIOUS rig's release before opening its camera. A
         *  close is tens to a few hundred milliseconds; this is an ordering guarantee, not a
         *  correctness one, so a device that never finishes closing must not wedge a DESCRIBE. */
        private const val RELEASE_WAIT_MS = 3_000L

        private val MIME: String = MediaFormat.MIMETYPE_VIDEO_AVC

        fun hasH264Encoder(): Boolean = runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) }
            }
        }.getOrDefault(false)

        /** Distinct facings (front/back) — two front-facing ids must not produce a "Back" option. */
        fun lensCount(context: Context): Int = runCatching {
            val manager = context.getSystemService(CameraManager::class.java) ?: return 0
            manager.cameraIdList
                .mapNotNull { id -> facingOf(manager, id) }
                .filter { it == CameraCharacteristics.LENS_FACING_FRONT || it == CameraCharacteristics.LENS_FACING_BACK }
                .distinct()
                .size
        }.getOrDefault(0)

        private fun facingOf(manager: CameraManager, id: String): Int? = runCatching {
            manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING)
        }.getOrNull()
    }

    /** DISPLAY, not VIDEO: this thread carries every camera callback, the per-frame copy and NAL
     *  split, the fan-out to all viewers and the JPEG copy, on a quad-A53 Echo Show that is also
     *  decoding audio. It has to beat the default background bucket — but this app's primary job
     *  is audio playback, so it must not be raised to the video/audio band and compete with it. */
    private val thread = HandlerThread("camera-share", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    /**
     * Every teardown runs here, whoever asked for it. `CameraDevice.close()` +
     * `MediaCodec.stop()`/`release()` cost tens to hundreds of milliseconds and both of the threads
     * that ask for one must not pay it: the main thread (the hub's linger and its shutdown both run
     * on the main Handler — an ANR on a slow device) and [thread] itself, where `release()` from
     * inside a MediaCodec callback is a documented deadlock and where the hub re-enters [stop] from
     * our own error report. The work is handed off and NOT waited on; [pendingRelease] is how the
     * next [start] still gets its ordering guarantee.
     */
    private val releaser = Executors.newSingleThreadExecutor { r ->
        Thread(r, "camera-share-release").apply { isDaemon = true }
    }

    /** The most recent teardown handed to [releaser], awaited by the next [start] before it opens a
     *  camera. Callers rely on "by the time a camera is opened, the previous one is shut": a lens
     *  change is `stop()` immediately followed by `start()`, and two live CameraDevices there mean
     *  ERROR_MAX_CAMERAS_IN_USE — or, on the same id, an eviction of whichever opened first. */
    private val pendingRelease = AtomicReference<Future<*>?>(null)

    private val lock = Any()
    private var rig: Rig? = null
    private var listener: PipelineListener? = null

    /** Bumped by every start, stop and failure. A camera callback from an older generation is
     *  ignored and its device closed, so a late onOpened after a timeout can never leak a camera. */
    private var generation = 0

    /** Set by [close]; refuses any further camera open, so the looper can be retired knowing
     *  nothing else will ever post a CameraDevice at it. */
    private var closed = false

    /** `openCamera` calls issued whose StateCallback has not fired yet. [close] must NOT quit
     *  [thread] while this is non-zero: the framework hands the CameraDevice over by posting
     *  onOpened to [handler], a quit looper DROPS that message, and the device is then open,
     *  powered, indicator lit, and unreachable by anything in this process until it dies. */
    private var opensInFlight = 0

    /** Serializes [grabJpeg] within the pipeline. The hub already does, but two overlapping still
     *  captures would trade each other's images and both time out, so this does not assume it. */
    private val grabs = Semaphore(1)

    @SuppressLint("MissingPermission")   // checked against PackageManager immediately below
    override fun start(
        lens: CameraShareSettings.Lens,
        encoding: CameraShareSettings.Encoding,
        listener: PipelineListener,
    ): PipelineStart {
        val gen = synchronized(lock) {
            // The hub never starts twice without a stop; this is belt and braces.
            if (rig != null) return PipelineStart.Ok
            // close() refuses every open from here on (see beginOpen); catch it here too so a
            // start arriving afterward does not build a whole rig only to have it discarded unopened.
            if (closed) return PipelineStart.Stopped
            this.listener = listener
            ++generation
        }

        // The service checks CAMERA before constructing us, but a permission revoked while the
        // share is on must fail with a reason the settings panel can show, not a SecurityException.
        if (appContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return fatal(gen, "camera permission not granted")
        }
        val manager = appContext.getSystemService(CameraManager::class.java)
            ?: return fatal(gen, "no camera service on this device")
        val id = pickCamera(manager, lens) ?: return fatal(gen, "no camera on this device")

        // Teardown is asynchronous (it must never run on the main thread or on [thread]), so the
        // previous generation's encoder and CameraDevice may still be releasing. Wait for it here,
        // before [buildRig] allocates a new encoder: on hardware whose AVC encoder allows only one
        // concurrent instance, `MediaCodec.createEncoderByType` throws while the old one is still
        // going, so this has to precede that call, not merely the camera open. With that, the
        // invariant every caller relies on holds: by the time a new encoder is created and a camera
        // opened, the previous rig's encoder and CameraDevice are both shut.
        awaitPendingRelease()

        val built = try {
            buildRig(manager, id, encoding, listener)
        } catch (e: Exception) {
            Log.w(TAG, "encoder setup failed", e)
            return fatal(gen, "encoder: ${e.message}")
        }
        // Published BEFORE the open so a stop arriving mid-open has something to tear down.
        val ours = synchronized(lock) {
            if (generation != gen) false else { rig = built; true }
        }
        if (!ours) {
            releaseRig(built)
            return PipelineStart.Stopped
        }

        // The camera open is asynchronous. Wait for it OUTSIDE [lock] so a stop, a linger or
        // another viewer's socket thread is never stuck behind a four-second open.
        val opened = CountDownLatch(1)
        val failure = AtomicReference<PipelineStart?>(null)
        // Exactly one of onOpened/onDisconnected/onError ENDS this open (a later onError is the
        // mid-stream death of a device we already own). Counted before the call, so [close] can see
        // that a CameraDevice is on its way and keep the looper alive to receive it.
        val openDone = AtomicBoolean(false)
        if (!beginOpen()) return abandon(gen, PipelineStart.Stopped)
        try {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    endOpen(openDone)
                    if (!isCurrent(gen, built) || !built.attachCamera(device)) {
                        runCatching { device.close() }
                        failure.compareAndSet(null, PipelineStart.Stopped)
                        opened.countDown()
                        return
                    }
                    startSession(built, gen, device) { outcome ->
                        if (outcome != null) failure.compareAndSet(null, outcome)
                        opened.countDown()
                    }
                }

                override fun onDisconnected(device: CameraDevice) = cameraGone(device, "camera disconnected")

                override fun onError(device: CameraDevice, error: Int) = cameraGone(device, openErrorReason(error))

                /** Before [start] returns the STARTING thread owns failure reporting (it is
                 *  waiting on [opened]); afterwards these callbacks do. [Rig.live] draws that line
                 *  so the hub is never told twice about one death — and staging the reason and
                 *  reading that line happen in ONE critical section, the same one [start] uses to
                 *  cross it, so a death landing exactly at the handover is reported by whichever
                 *  side wins the lock and never dropped by both. */
                private fun cameraGone(device: CameraDevice, reason: String) {
                    endOpen(openDone)
                    runCatching { device.close() }
                    val report = synchronized(lock) {
                        failure.compareAndSet(null, PipelineStart.Failed(reason))
                        built.live.get()
                    }
                    opened.countDown()
                    if (report) fail(built, reason)
                }
            }, handler)
        } catch (e: SecurityException) {
            endOpen(openDone)
            return fatal(gen, "camera permission not granted")
        } catch (e: CameraAccessException) {
            endOpen(openDone)
            return fatal(gen, accessErrorReason(e.reason))
        } catch (e: IllegalArgumentException) {
            endOpen(openDone)
            return fatal(gen, "camera id invalid")
        } catch (e: Exception) {
            endOpen(openDone)
            return fatal(gen, "camera open: ${e.message}")
        }

        val inTime = try {
            opened.await(OPEN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        // A privacy-gated camera never answers openCamera at all, so silence is a failure with a
        // reason that points at the physical button rather than a hang.
        if (!inTime) return fatal(gen, "camera did not open (check the camera/mic switch)")

        // The handover, in ONE critical section: [failure] is re-read HERE, because a death staged
        // between the await and this line would otherwise be seen by nobody — the callback reads
        // live == false and stays quiet, and start() would answer Ok for a rig whose CameraDevice
        // is already closed, costing the hub the real reason and a three-second DESCRIBE timeout.
        val handover: PipelineStart? = synchronized(lock) {
            val late = failure.get()
            when {
                late != null -> late
                generation != gen || rig !== built -> PipelineStart.Stopped
                else -> { built.live.set(true); null }
            }
        }
        if (handover != null) return abandon(gen, handover)
        Log.i(
            TAG,
            "camera share streaming ${built.videoSize.width}x${built.videoSize.height} " +
                "@ ${encoding.frameRate.fps} fps",
        )
        // The rig is live and the listener is known: tell the hub what the encoder actually got,
        // which pickSize may have shrunk below the requested resolution. Never under a lock.
        runCatching { listener.onVideoSize(built.videoSize.width, built.videoSize.height) }
        return PipelineStart.Ok
    }

    override fun stop() {
        detach()?.let { releaseRig(it) }
    }

    /**
     * Service teardown only: retires the HandlerThread and the release worker. The pipeline is
     * unusable afterwards. Returns PROMPTLY and never blocks its caller — it runs on the main
     * thread, and the camera close it triggers happens on [releaser].
     *
     * The looper is NOT quit while an open is outstanding. `CameraManager.openCamera` hands the
     * CameraDevice over by posting onOpened to [handler]; quit the looper first and that message is
     * dropped, leaving a camera open with the privacy indicator lit that no reference in this
     * process can close — reachable through the planned teardown, because the hub's `shutdown()`
     * never waits for a thread that is inside `start()` and Task 8's onDestroy calls that and this
     * back to back. The onOpened / cameraGone branches already close a device they no longer own;
     * they just need a live looper to run on.
     */
    fun close() {
        val outstanding = synchronized(lock) {
            if (closed) return
            closed = true
            opensInFlight
        }
        stop()
        releaser.shutdown()
        if (outstanding == 0) {
            // [beginOpen] refuses from here on, so nothing can post a device at us any more.
            thread.quitSafely()
        } else {
            // The open resolves and [endOpen] quits then; this is only the backstop for an open
            // that never answers at all (the hardware privacy gate), and it is bounded by the same
            // budget [start] gives one.
            handler.postDelayed({ thread.quitSafely() }, OPEN_TIMEOUT_MS)
        }
    }

    /** Registers a camera open about to be issued. False once [close] has run: no open may be made
     *  after that, because there would be no looper left to receive the device. */
    private fun beginOpen(): Boolean = synchronized(lock) {
        if (closed) false else { opensInFlight++; true }
    }

    /** Balances one [beginOpen]; [once] makes that exact, since three callbacks can each end the
     *  same open and only the first of them does. */
    private fun endOpen(once: AtomicBoolean) {
        if (!once.compareAndSet(false, true)) return
        val quit = synchronized(lock) {
            opensInFlight--
            closed && opensInFlight == 0
        }
        // Through the looper, so whatever the framework has already posted to it still runs.
        if (quit) handler.post { thread.quitSafely() }
    }

    override fun requestKeyframe() {
        val r = synchronized(lock) { rig } ?: return
        // Under the rig monitor: setParameters racing a stop's codec.release() is a native abort,
        // which no runCatching would see. The hub serializes this against start/stop anyway, but
        // the deferred teardown below means the codec can still be alive after stop() returned.
        r.withCodec("keyframe request") {
            it.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    /**
     * One still JPEG, or null. Returns within [JPEG_TIMEOUT_MS] whatever happens: the budget covers
     * waiting for a concurrent grab as well as the capture itself, so a caller can never be stuck
     * for two timeouts in a row.
     */
    override fun grabJpeg(): ByteArray? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(JPEG_TIMEOUT_MS)
        val acquired = try {
            grabs.tryAcquire(remainingMs(deadline), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!acquired) return null
        try {
            val r = synchronized(lock) { rig } ?: return null
            // Drop anything a previous capture left behind: a snapshot must be of NOW.
            r.jpegSlot.clear()
            // Only the JPEG reader is targeted. Adding the encoder surface would push one frame
            // with still-capture tuning (and on some devices a longer exposure) into the live
            // stream on every Home Assistant poll.
            val issued = r.withSession("still capture") { device, session ->
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                request.addTarget(r.jpegReader.surface)
                session.capture(request.build(), null, handler)
            }
            if (!issued) return null
            return try {
                r.jpegSlot.poll(remainingMs(deadline), TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
        } finally {
            grabs.release()
        }
    }

    // -- teardown ------------------------------------------------------------------------------

    /** Detaches the current rig under [lock] and returns it for release OUTSIDE the lock. Bumps
     *  the generation even when there is nothing to detach, so a start still opening is abandoned. */
    private fun detach(): Rig? = synchronized(lock) {
        val doomed = rig
        doomed?.drop()
        rig = null
        listener = null
        generation++
        doomed
    }

    /**
     * Releases [r] outside every lock and off the CALLER's thread, unconditionally — see [releaser]
     * for why neither the main thread nor [thread] may run a teardown inline. The handoff is
     * remembered so the next [start] can await it before opening a camera.
     */
    private fun releaseRig(r: Rig) {
        // Rejection can only happen once close() has shut the worker down. Run it inline rather
        // than posting to a looper that is quitting too: a rig must never be left allocated.
        val queued = runCatching { releaser.submit(Runnable { r.release() }) }.getOrNull()
        if (queued == null) { r.release(); return }
        pendingRelease.set(queued)
    }

    /** Waits, BOUNDED, for the previous teardown to finish. Taken rather than peeked: a release
     *  only has to be awaited once. [releaser] is single-threaded and FIFO, so waiting for the
     *  LATEST handoff implies every earlier one has already run — which is what makes overwriting
     *  [pendingRelease] rather than accumulating the futures correct. */
    private fun awaitPendingRelease() {
        val pending = pendingRelease.getAndSet(null) ?: return
        try {
            pending.get(RELEASE_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            Log.w(TAG, "previous camera release has not finished: ${e.message}")
        }
    }

    /** Abandons a start attempt with [outcome]: releases whatever this generation had built, if it
     *  is still ours. [PipelineStart.Stopped] is a deliberate teardown, not a failure — the hub
     *  answers it with a clean Off rather than an Unavailable, so it is not logged as one. */
    private fun abandon(gen: Int, outcome: PipelineStart): PipelineStart {
        val doomed = synchronized(lock) {
            if (generation != gen) null
            else {
                val r = rig
                r?.drop()
                rig = null
                listener = null
                generation++
                r
            }
        }
        doomed?.let { releaseRig(it) }
        when (outcome) {
            is PipelineStart.Failed -> Log.w(TAG, "camera share start failed: ${outcome.reason}")
            else -> Log.i(TAG, "camera share start abandoned: the pipeline was stopped")
        }
        return outcome
    }

    private fun fatal(gen: Int, reason: String): PipelineStart = abandon(gen, PipelineStart.Failed(reason))

    /**
     * A mid-stream failure of [r]: release it, then tell the hub ONCE, outside every lock. The hub
     * answers by calling [stop] re-entrantly on this thread, which then finds nothing left to do.
     *
     * Takes the rig that died rather than "the current one": a camera or codec callback can be a
     * generation behind (a lens change closes one rig and opens another), and failing whatever
     * happens to be current would kill the camera the change had just reopened. Detaching and
     * reading the listener in ONE critical section also makes exactly one caller the reporter.
     */
    private fun fail(r: Rig, reason: String) {
        val sink = synchronized(lock) {
            if (rig !== r) return             // a newer generation owns the pipeline now
            val current = listener
            r.drop()
            rig = null
            listener = null
            generation++
            current
        }
        Log.w(TAG, "camera share pipeline failed: $reason")
        releaseRig(r)
        sink?.let { runCatching { it.onPipelineError(reason) } }
    }

    private fun isCurrent(gen: Int, r: Rig): Boolean = synchronized(lock) { generation == gen && rig === r }

    // -- camera --------------------------------------------------------------------------------

    private fun pickCamera(manager: CameraManager, lens: CameraShareSettings.Lens): String? = runCatching {
        val wanted = if (lens == CameraShareSettings.Lens.BACK) {
            CameraCharacteristics.LENS_FACING_BACK
        } else {
            CameraCharacteristics.LENS_FACING_FRONT
        }
        val ids = manager.cameraIdList
        ids.firstOrNull { facingOf(manager, it) == wanted } ?: ids.firstOrNull()
    }.getOrNull()

    @Suppress("DEPRECATION")   // createCaptureSession(List, ...) is the only form on minSdk 26
    private fun startSession(r: Rig, gen: Int, device: CameraDevice, done: (PipelineStart?) -> Unit) {
        try {
            device.createCaptureSession(
                listOf(r.inputSurface, r.jpegReader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        if (!isCurrent(gen, r) || !r.attachSession(session)) {
                            runCatching { session.close() }
                            done(PipelineStart.Stopped)
                            return
                        }
                        val started = r.withSession("repeating request") { camera, s ->
                            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            request.addTarget(r.inputSurface)
                            r.fpsRange?.let { request.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                            s.setRepeatingRequest(request.build(), null, handler)
                        }
                        done(if (started) null else PipelineStart.Failed("camera would not start capturing"))
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        runCatching { session.close() }
                        done(PipelineStart.Failed("capture session failed"))
                    }
                },
                handler,
            )
        } catch (e: Exception) {
            done(PipelineStart.Failed("session: ${e.message}"))
        }
    }

    // -- encoder -------------------------------------------------------------------------------

    private fun buildRig(
        manager: CameraManager,
        id: String,
        encoding: CameraShareSettings.Encoding,
        sink: PipelineListener,
    ): Rig {
        val characteristics = runCatching { manager.getCameraCharacteristics(id) }.getOrNull()
        val map = runCatching {
            characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        }.getOrNull()
        val video = pickSize(map, encoding.resolution)
        val jpeg = pickJpegSize(map, video)
        val self = AtomicReference<Rig?>(null)
        val slot = ArrayBlockingQueue<ByteArray>(1)
        var reader: ImageReader? = null
        var codec: MediaCodec? = null
        var surface: Surface? = null
        try {
            reader = ImageReader.newInstance(jpeg.width, jpeg.height, ImageFormat.JPEG, JPEG_BUFFERS)
            // Installed ONCE, for the life of the rig, and it always acquires AND closes: an image
            // left open starves the reader's two buffers and every later grab times out. A frame
            // nobody is waiting for is simply dropped.
            reader.setOnImageAvailableListener({ from -> collectJpeg(from, slot) }, handler)
            codec = createEncoder(video, encoding, self, sink)
            surface = codec.createInputSurface()
            val built = Rig(
                codec, surface, reader, slot, video,
                pickFpsRange(characteristics, encoding.frameRate.fps),
                BitrateGovernor(encoding.bitrate),
            )
            // Published to the callbacks before the codec runs, and the codec started before the
            // capture session exists: the input surface must already be consuming when the camera
            // starts pushing frames at it, and no output may arrive with no rig to attribute it to.
            self.set(built)
            codec.start()
            return built
        } catch (e: Exception) {
            val partial = self.get()
            if (partial != null) {
                partial.release()
            } else {
                runCatching { surface?.release() }
                runCatching { codec?.release() }
                runCatching { reader?.close() }
            }
            throw e
        }
    }

    private fun createEncoder(
        size: Size,
        encoding: CameraShareSettings.Encoding,
        self: AtomicReference<Rig?>,
        sink: PipelineListener,
    ): MediaCodec {
        var last: Exception? = null
        // Baseline keeps B-frames — and the reordered timestamps they imply — out of the stream,
        // but a few encoders reject an explicit profile/level outright. Losing the hint is better
        // than losing the feature, so fall back to the plain format rather than failing the start.
        for (withProfile in listOf(true, false)) {
            val codec = MediaCodec.createEncoderByType(MIME)
            try {
                codec.setCallback(encoderCallback(self, sink), handler)
                codec.configure(encoderFormat(size, encoding, withProfile), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                return codec
            } catch (e: Exception) {
                last = e
                Log.w(TAG, "encoder configure failed (profile=$withProfile): ${e.message}")
                runCatching { codec.release() }
            }
        }
        throw last ?: IllegalStateException("no H.264 encoder")
    }

    private fun encoderFormat(
        size: Size,
        encoding: CameraShareSettings.Encoding,
        withProfile: Boolean,
    ): MediaFormat =
        MediaFormat.createVideoFormat(MIME, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, encoding.bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, encoding.frameRate.fps)
            // Enforced by the codec framework on the input surface (Android 10+): frames above this
            // rate are dropped BEFORE the encoder, whatever the camera delivers. The AE range below
            // is only a request, and the Echo Show ignores it (see BitrateGovernor).
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                setFloat(MediaFormat.KEY_MAX_FPS_TO_ENCODER, encoding.frameRate.fps.toFloat())
            }
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL_S)
            if (withProfile) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                // Level 3.1 tops out at 1280x720; 1080p needs 4.0 or the configure is rejected and
                // the profile hint is dropped altogether by the fallback below.
                setInteger(MediaFormat.KEY_LEVEL, levelFor(size))
            }
        }

    /** The lowest AVC level that admits [size], so the hint never becomes the reason a configure
     *  fails. Baseline 3.1 covers 720p30; 1080p needs 4.0. */
    private fun levelFor(size: Size): Int =
        if (size.width.toLong() * size.height > 1280L * 720) {
            MediaCodecInfo.CodecProfileLevel.AVCLevel4
        } else {
            MediaCodecInfo.CodecProfileLevel.AVCLevel31
        }

    /** The rig a callback belongs to, or null once it has been dropped: a callback from a
     *  superseded generation must reach neither the hub nor [fail]. */
    private fun liveRig(self: AtomicReference<Rig?>): Rig? = self.get()?.takeIf { !it.detached.get() }

    private fun encoderCallback(self: AtomicReference<Rig?>, sink: PipelineListener) = object : MediaCodec.Callback() {

        /**
         * NEVER takes the rig monitor: [Rig.release] holds it across `codec.release()`, which
         * waits for this callback to return. The framework guarantees no callback runs after
         * release() has returned, and the [codec] handed in here is valid for this call, so
         * [liveRig] — a lock-free flag read — is all the gating this needs.
         */
        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            // Copied out, per access unit, every time. The arrays are HANDED OVER: the server
            // queues seconds of video per viewer, so a reused scratch buffer would corrupt frames
            // exactly when a viewer's queue backs up — the hardest case to reproduce there is.
            val bytes = try {
                val buffer = codec.getOutputBuffer(index)
                if (buffer == null || info.size <= 0) {
                    null
                } else {
                    // Through Buffer, not ByteBuffer: the covariant position()/limit() overrides
                    // are newer than minSdk 26, and resolving them against the compileSdk gives a
                    // NoSuchMethodError on the older devices this ships to. Limit before position,
                    // the conventional order: it can never transiently sit below the position.
                    val window: Buffer = buffer
                    window.limit(info.offset + info.size)
                    window.position(info.offset)
                    ByteArray(info.size).also { buffer.get(it) }
                }
            } catch (e: Exception) {
                // Once per rig: this path runs 15 times a second. Without it, an encoder whose
                // buffers cannot be read systematically produces no access unit and NOTHING in
                // logcat, and "no encoder output" is indistinguishable from a camera that never
                // delivered a frame — exactly the difference a device test has to make.
                if (self.get()?.bufferWarned?.compareAndSet(false, true) != false) {
                    Log.w(TAG, "encoder output buffer unreadable; further ones on this camera are silent", e)
                }
                null
            } finally {
                runCatching { codec.releaseOutputBuffer(index, false) }
            }
            if (bytes == null || liveRig(self) == null) return

            // MediaCodec emits Annex-B; both the SDP and the parameter sets the hub prepends to
            // keyframes need BARE NAL units. A start code left in the SPS makes profile-level-id
            // read 000001 and media3 renders a black screen with no other symptom.
            val nals = H264Nal.splitAnnexB(bytes).filter { it.isNotEmpty() }
            if (nals.isEmpty()) return
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                emitConfig(nals, sink)
                return
            }
            // Some encoders repeat the parameter sets in-band on every keyframe. Feed them to the
            // hub (a device that never sends a CODEC_CONFIG buffer would otherwise never describe)
            // but keep them out of the payload, because the hub prepends its own copies.
            emitConfig(nals, sink)
            val payload = nals.filter { H264Nal.type(it) != H264Nal.TYPE_SPS && H264Nal.type(it) != H264Nal.TYPE_PPS }
            if (payload.isEmpty()) return
            // ONE access unit per output buffer, carrying every NAL split from it: the packetizer
            // sets the RTP marker bit on the last packet of a packetize() call, so a frame handed
            // over in two calls would announce two frame boundaries.
            runCatching { sink.onAccessUnit(AccessUnit(payload, info.presentationTimeUs, H264Nal.isKeyframe(payload))) }
            regulateBitrate(codec, self, info.size)
        }

        /**
         * Keeps the wire rate at the configured budget when the camera ignores the frame rate it
         * was asked for.
         *
         * A camera delivering twice the frame rate the encoder was configured for gets twice the
         * bitrate, because MediaCodec sizes each frame from the CONFIGURED rate — measured at
         * 2.97 Mbps against a 1.5 Mbps budget on the Echo Show. That doubles how fast a viewer's
         * queue fills during a Wi-Fi stall, which is what turns a stall into a flush and a
         * two-second freeze. See [BitrateGovernor] for the full reasoning and the numbers.
         *
         * Runs on the codec callback thread, which is [handler] — the same thread [requestKeyframe]
         * posts its `setParameters` to — so this needs no hop of its own, and at most one call per
         * [BitrateGovernor.WINDOW_MS] actually reaches the codec.
         */
        private fun regulateBitrate(codec: MediaCodec, self: AtomicReference<Rig?>, bytes: Int) {
            val rig = liveRig(self) ?: return
            val next = rig.governor.onAccessUnit(bytes, SystemClock.elapsedRealtime()) ?: return
            // Never fatal: an encoder that refuses a runtime bitrate change simply keeps the one it
            // was configured with, which is exactly today's behaviour.
            val applied = runCatching {
                codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, next) })
            }.isSuccess
            Log.i(TAG, "camera share bitrate -> $next bps" + if (applied) "" else " (encoder refused)")
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            if (liveRig(self) == null) return
            // csd-0/csd-1 also carry the parameter sets, with start codes, and some encoders
            // deliver them ONLY here and never as a CODEC_CONFIG buffer.
            val nals = listOf("csd-0", "csd-1").flatMap { key -> csdNals(format, key) }
            if (nals.isNotEmpty()) emitConfig(nals, sink)
        }

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            val reason = "encoder error: ${runCatching { e.diagnosticInfo }.getOrNull() ?: e.message}"
            val r = liveRig(self)
            if (r != null) fail(r, reason) else Log.w(TAG, "encoder error after stop: $reason")
        }
    }

    private fun csdNals(format: MediaFormat, key: String): List<ByteArray> = runCatching {
        if (!format.containsKey(key)) return emptyList()
        val buffer = format.getByteBuffer(key)?.duplicate() ?: return emptyList()
        val raw = ByteArray(buffer.remaining())
        buffer.get(raw)
        H264Nal.splitAnnexB(raw).filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** Hands over BARE SPS and PPS NAL units — never the Annex-B buffer they arrived in. */
    private fun emitConfig(nals: List<ByteArray>, sink: PipelineListener) {
        val sps = nals.firstOrNull { H264Nal.type(it) == H264Nal.TYPE_SPS } ?: return
        val pps = nals.firstOrNull { H264Nal.type(it) == H264Nal.TYPE_PPS } ?: return
        runCatching { sink.onCodecConfig(sps, pps) }
    }

    // -- sizes ---------------------------------------------------------------------------------

    /**
     * The chosen [resolution]'s size when the camera lists it for both the encoder surface and JPEG,
     * else the largest size at or below it common to both — never a size the camera does not list,
     * because Camera2 rejects an unlisted output when the session is configured and the only
     * symptom is "capture session failed". A camera that lists nothing at or below the chosen
     * height gets its SMALLEST listed size; only an unreadable map falls back to the nominal one.
     *
     * The cap is a CEILING, so picking 1080p on a camera that only offers 720p quietly gets 720p
     * rather than failing — the setting is a preference, not a promise the hardware has made.
     */
    private fun pickSize(map: StreamConfigurationMap?, resolution: CameraShareSettings.Resolution): Size {
        val caps = avcEncoderCaps()
        val listed = runCatching { map?.getOutputSizes(MediaCodec::class.java)?.toList() }.getOrNull().orEmpty()
            .filter { it.width > 0 && it.height > 0 }
        val encoderSizes = listed.filter { size ->
            size.height <= resolution.height &&
                runCatching { caps?.isSizeSupported(size.width, size.height) }.getOrNull() != false
        }
        // Keeping the promise above: an unlisted size would fail the session outright, whereas a
        // larger-than-asked-for one merely costs bitrate.
        if (encoderSizes.isEmpty()) {
            return listed.minByOrNull { it.width.toLong() * it.height } ?: Size(resolution.width, resolution.height)
        }
        val jpegSizes = runCatching { map?.getOutputSizes(ImageFormat.JPEG)?.toSet() }.getOrNull().orEmpty()
        // One size for both outputs keeps the session inside Camera2's guaranteed (PRIV + JPEG)
        // combination on LIMITED devices; a camera that shares none still gets two listed sizes.
        val shared = encoderSizes.filter { it in jpegSizes }
        val pool = if (shared.isNotEmpty()) shared else encoderSizes
        return pool.firstOrNull { it.width == resolution.width && it.height == resolution.height }
            ?: pool.maxByOrNull { it.width.toLong() * it.height }
            ?: Size(resolution.width, resolution.height)
    }

    private fun pickJpegSize(map: StreamConfigurationMap?, video: Size): Size {
        val sizes = runCatching { map?.getOutputSizes(ImageFormat.JPEG)?.toList() }.getOrNull().orEmpty()
        if (sizes.isEmpty()) return video
        return sizes.firstOrNull { it == video }
            ?: sizes.filter { it.height <= video.height }.maxByOrNull { it.width.toLong() * it.height }
            ?: sizes.minByOrNull { it.width.toLong() * it.height }
            ?: video
    }

    /** An AE range the camera does not advertise fails the session on some devices, so ask only
     *  for a listed one: an exact [fps,fps] if there is one, else the narrowest containing it,
     *  else nothing at all and the template's own default stands. A request either way: the Echo
     *  Show's HAL echoes [15,15] back and still delivers 30 fps, which is why the encoder format
     *  also caps the input surface (KEY_MAX_FPS_TO_ENCODER). */
    private fun pickFpsRange(characteristics: CameraCharacteristics?, fps: Int): Range<Int>? {
        val ranges = runCatching {
            characteristics?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
        }.getOrNull().orEmpty()
        return ranges.firstOrNull { it.lower == fps && it.upper == fps }
            ?: ranges.filter { it.lower <= fps && it.upper >= fps }.minByOrNull { it.upper - it.lower }
    }

    private fun avcEncoderCaps(): MediaCodecInfo.VideoCapabilities? = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .firstOrNull { info -> info.isEncoder && info.supportedTypes.any { it.equals(MIME, ignoreCase = true) } }
            ?.getCapabilitiesForType(MIME)
            ?.videoCapabilities
    }.getOrNull()

    // -- misc ----------------------------------------------------------------------------------

    private fun collectJpeg(from: ImageReader, slot: ArrayBlockingQueue<ByteArray>) {
        var image: Image? = null
        try {
            image = from.acquireLatestImage() ?: return
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)
            slot.poll()          // a still nobody collected is stale; the newest one wins
            slot.offer(bytes)
        } catch (e: Exception) {
            Log.w(TAG, "snapshot read failed: ${e.message}")
        } finally {
            runCatching { image?.close() }
        }
    }

    private fun remainingMs(deadlineNanos: Long): Long =
        (deadlineNanos - System.nanoTime()).coerceAtLeast(0L) / 1_000_000L

    private fun openErrorReason(error: Int): String = when (error) {
        CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "the camera is in use by another app"
        CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "too many cameras are in use"
        CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "the camera is disabled on this device"
        CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera device error"
        CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera service error"
        else -> "camera error $error"
    }

    /**
     * `CameraAccessException.getReason()` is an int, and it reaches the settings panel: without
     * this it renders as "camera unavailable (4)" among prose.
     *
     * Deliberately NOT [openErrorReason]. The two constant sets overlap with DIFFERENT meanings —
     * `CameraAccessException.CAMERA_IN_USE` and `StateCallback.ERROR_CAMERA_DEVICE` are both 4 —
     * so one shared table would print confidently wrong text.
     */
    private fun accessErrorReason(reason: Int): String = when (reason) {
        CameraAccessException.CAMERA_DISABLED -> "the camera is disabled by a device policy"
        CameraAccessException.CAMERA_DISCONNECTED -> "camera disconnected"
        CameraAccessException.CAMERA_ERROR -> "the camera is in a bad state"
        CameraAccessException.CAMERA_IN_USE -> "the camera is in use by another app"
        CameraAccessException.MAX_CAMERAS_IN_USE -> "too many cameras are in use"
        else -> "camera unavailable"
    }
}

/**
 * The native objects of ONE capture generation, released together and exactly once.
 *
 * [guard] covers use-versus-release: every call into the MediaCodec, the CameraDevice or the
 * CaptureSession from outside a framework callback runs under it, so a stop on another thread
 * cannot free a native object from under an in-flight call — that is a process-killing native
 * abort, not a catchable exception.
 *
 * It is a [ReentrantLock] rather than a monitor for one reason: every USE takes it with a BOUNDED
 * `tryLock` and gives up rather than queue. [release] is the only long holder (a camera close and
 * a `codec.stop()`/`release()`), and some of the users are the camera callback thread itself — so
 * an unbounded wait would park that thread behind a teardown that may be waiting on it. A use that
 * loses the race has the right answer anyway: the rig is going away.
 *
 * The MediaCodec callbacks never take it at all. They gate on the lock-free `detached` flag, so
 * `codec.release()` can never be waiting for a callback that is waiting for this lock.
 */
private class Rig(
    private val codec: MediaCodec,
    val inputSurface: Surface,
    val jpegReader: ImageReader,
    val jpegSlot: ArrayBlockingQueue<ByteArray>,
    val videoSize: Size,
    val fpsRange: Range<Int>?,
    /** Touched only from the codec callback thread, which is single and serialised, so it needs
     *  no synchronisation of its own. */
    val governor: BitrateGovernor,
) {
    /** Set the moment the pipeline drops this rig, so the camera and codec callbacks stop
     *  forwarding to the hub immediately rather than when the (possibly deferred) release runs. */
    val detached = AtomicBoolean(false)

    /** True once `start()` has returned Ok. Before that the starting thread owns failure
     *  reporting; after it, the camera callbacks do. */
    val live = AtomicBoolean(false)

    /** Rate-limits the encoder-output-buffer warning to one per camera: the path it guards runs
     *  at the configured frame rate, every second, and would otherwise flood logcat. */
    val bufferWarned = AtomicBoolean(false)

    /** Retires the rig for the callbacks without freeing anything: the release itself may have to
     *  be deferred off the callback thread, and nothing should keep reaching the hub until then. */
    fun drop() {
        detached.set(true)
        live.set(false)
    }

    private val guard = ReentrantLock()
    private var alive = true
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null

    fun attachCamera(device: CameraDevice): Boolean = guarded("camera attach") {
        camera = device
        true
    }

    fun attachSession(s: CameraCaptureSession): Boolean = guarded("session attach") {
        session = s
        true
    }

    fun withCodec(what: String, body: (MediaCodec) -> Unit): Boolean = guarded(what) {
        runCatching { body(codec) }.onFailure { Log.w(TAG, "$what failed: ${it.message}") }.isSuccess
    }

    fun withSession(what: String, body: (CameraDevice, CameraCaptureSession) -> Unit): Boolean = guarded(what) {
        val device = camera
        val s = session
        if (device == null || s == null) false
        else runCatching { body(device, s) }.onFailure { Log.w(TAG, "$what failed: ${it.message}") }.isSuccess
    }

    /** Runs [body] only while the native objects are still allocated, and only if no teardown has
     *  the guard. False means "the rig is gone or going" — never "it failed silently". */
    private fun guarded(what: String, body: () -> Boolean): Boolean {
        val held = try {
            guard.tryLock(RIG_GUARD_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!held) {
            Log.w(TAG, "$what skipped: the camera is being torn down")
            return false
        }
        return try {
            if (alive) body() else false
        } finally {
            guard.unlock()
        }
    }

    fun release() {
        guard.lock()
        try {
            if (!alive) return
            alive = false
            drop()
            // Camera first: nothing may still be pushing frames at the encoder's input surface
            // when it goes away.
            runCatching { session?.close() }
            session = null
            runCatching { camera?.close() }
            camera = null
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { inputSurface.release() }
            runCatching { jpegReader.setOnImageAvailableListener(null, null) }
            runCatching { jpegReader.close() }
            jpegSlot.clear()
        } finally {
            guard.unlock()
        }
    }
}
