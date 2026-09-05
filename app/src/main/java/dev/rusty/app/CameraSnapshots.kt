package dev.rusty.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Pure, JVM-testable limits for snapshot fetching: how big a response may be, and how far a decoded
 * still has to be shrunk before it becomes a grid thumbnail.
 */
object SnapshotGuards {

    /** Hard ceiling on a single snapshot response. A grid tile never needs more than this, and a
     *  camera that answers a snapshot URL with a video stream must not be read into memory. 8 MiB
     *  leaves room for the full-resolution still a 4K camera serves (a 4096x1248 Reolink sends
     *  ~5.5 MB) while still refusing anything stream-shaped. */
    const val MAX_BYTES: Long = 8L * 1024 * 1024

    /**
     * Whether a response advertising [len] bytes may be read. A null length (chunked transfer, or
     * no `Content-Length` header) is accepted — the read itself is capped at [MAX_BYTES] — and a
     * length of exactly [MAX_BYTES] is still fine; only a larger one is rejected outright.
     */
    fun acceptContentLength(len: Long?): Boolean {
        if (len == null) return true
        return len <= MAX_BYTES
    }

    /**
     * `BitmapFactory.Options.inSampleSize` for a source of [srcW]x[srcH]: the smallest power of two
     * that brings *both* dimensions to [maxW] or under, so a 12 MP camera still becomes a small
     * thumbnail bitmap. Bounds that are unknown (0 or negative, as `BitmapFactory` reports for an
     * undecodable buffer) sample by 1.
     */
    fun targetSampleSize(srcW: Int, srcH: Int, maxW: Int = 1280): Int {
        val longest = maxOf(srcW, srcH)
        if (longest <= 0 || maxW <= 0) return 1
        var sample = 1
        while (longest / sample > maxW) sample *= 2
        return sample
    }
}

/**
 * Pure, JVM-testable HTTP authentication decisions for snapshot fetching.
 *
 * Some cameras — Reolink's ONVIF-advertised `cgi-bin/api.cgi?cmd=onvifSnapPic` among them — answer
 * an anonymous *or* Basic-authenticated snapshot request with `401` and a `WWW-Authenticate: Digest`
 * challenge, and hand over the JPEG only once the request is repeated with a digest response.
 */
object SnapshotAuth {

    /**
     * The `Authorization` value for the single retry of a request that came back `401`, or null
     * when no retry is worth making: no credentials to answer with, no challenge, or a challenge
     * naming a scheme other than Digest (Basic was already tried on the first attempt).
     *
     * [uri] must be the request-target the retry will use — see [requestTarget] — because the
     * digest hash is bound to it.
     */
    fun digestRetry(
        user: String?,
        pass: String?,
        method: String,
        uri: String,
        challenge: String?,
        cnonce: String,
    ): String? {
        if (user.isNullOrEmpty()) return null
        val trimmed = challenge?.trim().orEmpty()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        return OnvifSoap.httpDigestAuthorization(
            username = user,
            password = pass ?: "",
            method = method,
            uri = uri,
            challenge = trimmed,
            cnonce = cnonce,
        )
    }

    /** Request-target for the digest hash: [url]'s path plus query, defaulting to `/`. */
    fun requestTarget(url: String): String {
        val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return url
        val slash = afterScheme.indexOf('/')
        return if (slash < 0) "/" else afterScheme.substring(slash)
    }

    /** A fresh client nonce: 8 random bytes, lowercase hex. */
    fun cnonce(): String {
        val bytes = ByteArray(8)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

/** Platform side of a snapshot fetch. Split out so the loop holds no I/O of its own. */
interface SnapshotIo {
    /** GETs [url], sending HTTP Basic auth when [user] is present and retrying once with HTTP
     *  Digest if the camera answers `401` with a Digest challenge, and returns at most
     *  [SnapshotGuards.MAX_BYTES] bytes — or null on any failure or an oversized body. */
    suspend fun fetchHttp(url: String, user: String?, pass: String?): ByteArray?

    /** Opens [rtspUriWithCreds] briefly and decodes a single video frame, giving up after
     *  [deadlineMs]. Returns null if no frame arrived in time. */
    suspend fun grabFrame(rtspUriWithCreds: String, forceTcp: Boolean, deadlineMs: Long): Bitmap?
}

/**
 * Drives [SnapshotScheduler]: a 500 ms ticker that expires overdue jobs, pulls at most one new job
 * per tick, runs it through [SnapshotIo], and publishes the resulting thumbnail (and its JPEG
 * encoding, for the remote-control API proxy).
 *
 * **A job's result can never outlive its deadline.** The scheduler tracks one in-flight job per
 * camera and cannot tell one generation from the next, so a late reply from an already-expired job
 * would corrupt its bookkeeping (clearing the slot of the *next* job for that camera). Every job
 * therefore carries a single-use ticket: the tick that expires it flips the ticket and cancels the
 * coroutine, and the coroutine flips the same ticket before it reports or publishes anything.
 * Exactly one of the two wins, so `onJobFinished` is structurally impossible for a job already
 * reported through `onJobDeadline`.
 *
 * The loop and every publish run on [main]; only the fetching, decoding and JPEG encoding leave it.
 */
class CameraSnapshots(
    private val scope: CoroutineScope,
    private val scheduler: SnapshotScheduler,
    private val io: SnapshotIo,
    private val cameras: () -> List<CameraRecord>,
    private val secrets: (String) -> Pair<String?, String?>,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickMs: Long = TICK_MS,
    private val main: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
) {

    /** One dispatched job plus the single-use ticket that decides who gets to report it. */
    private class InFlight(val cameraId: String, val ticket: AtomicBoolean) {
        var coroutine: Job? = null

        /** True for the first (and only) caller — the deadline tick or the job itself. */
        fun claim(): Boolean = ticket.compareAndSet(false, true)
    }

    /** What one finished job produced: the decoded tile, its JPEG encoding, or both. A camera whose
     *  still-image bytes this device cannot decode still yields a JPEG (the camera's own), so the
     *  API proxy keeps working even when the tile cannot be drawn. */
    private class SnapshotFrame(val bitmap: Bitmap?, val jpeg: ByteArray?)

    private val tileMap = ConcurrentHashMap<String, Bitmap>()
    private val jpegMap = ConcurrentHashMap<String, ByteArray>()

    /** Latest thumbnail per camera id. Written on [main]. */
    val tiles: Map<String, Bitmap> get() = tileMap

    /** Latest JPEG-encoded thumbnail per camera id (quality 80). Written on [main]. */
    val jpegs: Map<String, ByteArray> get() = jpegMap

    private var listener: ((String) -> Unit)? = null

    private var loop: Job? = null

    /** Touched only from [main]. */
    private var current: InFlight? = null

    /** Called on [main] with a camera id whenever that camera's tile was replaced. */
    fun setTileListener(l: (String) -> Unit) {
        listener = l
    }

    /** Starts the ticker. A second call while running is a no-op. */
    fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch(main) {
            while (isActive) {
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A throwing tile listener (or any other caller-supplied callback) must not
                    // take the ticker — and the enclosing scope — down with it.
                    Log.w(TAG, "snapshot tick failed: ${e.javaClass.simpleName}")
                }
                delay(tickMs)
            }
        }
    }

    /**
     * Stops the ticker and cancels any in-flight job. Because [stop] claims that job's ticket it
     * owns the outcome, so it also releases the scheduler's in-flight slot (as a failure) — leaving
     * the slot held would stall every camera until the job's deadline elapsed after a restart.
     */
    fun stop() {
        loop?.cancel()
        loop = null
        current?.let {
            if (it.claim()) {
                it.coroutine?.cancel()
                scheduler.onJobFinished(it.cameraId, clock(), false)
            }
        }
        current = null
    }

    /** One 500 ms step: prune, expire, then dispatch at most one job. Runs on [main]. */
    private fun tick() {
        prune()
        val expired = scheduler.onJobDeadline(clock())
        if (expired != null) {
            val flight = current
            if (flight != null && flight.cameraId == expired) {
                // Claim the ticket before cancelling: the job can no longer report or publish.
                flight.claim()
                flight.coroutine?.cancel()
                current = null
            }
        }
        if (current != null) return
        val job = scheduler.onTick(clock()) ?: return
        dispatch(job)
    }

    private fun dispatch(job: SnapshotJob) {
        val flight = InFlight(job.cameraId, AtomicBoolean(false))
        current = flight
        flight.coroutine = scope.launch(main) {
            val frame = runCatching { run(job) }.getOrElse { e ->
                if (e is CancellationException) throw e
                Log.w(TAG, "snapshot job failed for ${job.cameraId}: ${e.javaClass.simpleName}")
                null
            }
            // Whoever claims the ticket owns the outcome. If the deadline tick already claimed it
            // this job is gone as far as the scheduler is concerned, so it publishes nothing.
            if (!flight.claim()) return@launch
            if (frame != null) {
                frame.bitmap?.let { tileMap[job.cameraId] = it }
                frame.jpeg?.let { jpegMap[job.cameraId] = it }
                try {
                    listener?.invoke(job.cameraId)
                } catch (e: Exception) {
                    Log.w(TAG, "tile listener threw: ${e.javaClass.simpleName}")
                }
            }
            scheduler.onJobFinished(job.cameraId, clock(), frame != null)
            if (current === flight) current = null
        }
    }

    /** Drops tiles and JPEGs for cameras that no longer exist, so a deleted camera's last frame
     *  neither stays resident nor keeps being served by the remote-control API proxy. */
    private fun prune() {
        if (tileMap.isEmpty() && jpegMap.isEmpty()) return
        val known = cameras().mapTo(HashSet()) { it.id }
        tileMap.keys.retainAll(known)
        jpegMap.keys.retainAll(known)
    }

    private suspend fun run(job: SnapshotJob): SnapshotFrame? {
        val cam = cameras().firstOrNull { it.id == job.cameraId } ?: return null
        val (user, pass) = secrets(cam.id)
        // Both kinds are bound by the scheduler's own deadline, so a wedged socket cannot outlive
        // the job that owns it even if the ticker's cancellation were to arrive late.
        val remaining = (job.deadlineAt - clock()).coerceAtLeast(0L)
        if (remaining <= 0L) return null
        return when (job.kind) {
            JobKind.HTTP_THEN_FRAME -> {
                // A failing snapshot URL says nothing about the RTSP stream, so a camera whose
                // still-image endpoint redirects, oversizes or errors still gets a tile. The
                // fallback runs on what is left of the *same* job deadline.
                // A throwing fetch is as good as a failed one here (the I/O layer logs its own
                // failures); only cancellation still ends the job.
                val http = runCatching { httpFrame(cam, user, pass, remaining) }
                    .getOrElse { if (it is CancellationException) throw it else null }
                http ?: grabbedFrame(cam, user, pass, (job.deadlineAt - clock()).coerceAtLeast(0L))
            }
            JobKind.FRAME_GRAB -> grabbedFrame(cam, user, pass, remaining)
        }
    }

    /** Pulls [CameraRecord.snapshotUrl] within [budgetMs]. Null when there is no URL, the fetch
     *  failed, or it outran the budget. */
    private suspend fun httpFrame(
        cam: CameraRecord,
        user: String?,
        pass: String?,
        budgetMs: Long,
    ): SnapshotFrame? {
        if (budgetMs <= 0L) return null
        val url = cam.snapshotUrl?.takeIf { it.isNotBlank() } ?: return null
        val bytes = withTimeoutOrNull(budgetMs) { io.fetchHttp(url, user, pass) } ?: return null
        val bitmap = decodeSampled(bytes)
        return SnapshotFrame(bitmap, bitmap?.let { encodeJpeg(it) } ?: bytes)
    }

    /** Decodes a single frame off the RTSP stream within [budgetMs]. */
    private suspend fun grabbedFrame(
        cam: CameraRecord,
        user: String?,
        pass: String?,
        budgetMs: Long,
    ): SnapshotFrame? {
        if (budgetMs <= 0L) return null
        val uri = CameraUri.withCredentials(cam.rtspUrl, user, pass)
        val bitmap = io.grabFrame(uri, cam.forceTcp, budgetMs) ?: return null
        return SnapshotFrame(bitmap, encodeJpeg(bitmap))
    }

    private suspend fun decodeSampled(bytes: ByteArray): Bitmap? = withContext(worker) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = SnapshotGuards.targetSampleSize(bounds.outWidth, bounds.outHeight, MAX_TILE_WIDTH)
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        }.getOrNull()
    }

    private suspend fun encodeJpeg(bitmap: Bitmap): ByteArray? = withContext(worker) {
        runCatching {
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }.getOrNull()
    }

    companion object {
        private const val TAG = "CameraSnapshots"
        const val TICK_MS = 500L
        const val JPEG_QUALITY = 80
        const val MAX_TILE_WIDTH = 1280
    }
}

/**
 * The real [SnapshotIo]: `HttpURLConnection` for still-image URLs, and a short-lived main-looper
 * `ExoPlayer` + `PixelCopy` for cameras that only speak RTSP.
 *
 * Credentials are never logged — a URL that reaches a log line goes through [CameraUri.redact].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidSnapshotIo(context: Context) : SnapshotIo {

    private val appContext = context.applicationContext

    /**
     * Cancellation-cooperative by three separate means, because `HttpURLConnection` blocks in
     * native code: [runInterruptible] interrupts the worker thread, a completion handler
     * `disconnect()`s the connection from whatever thread cancels us (which is what actually
     * unblocks a stuck socket read), and the read loop re-checks the job between chunks — so a
     * camera that trickles bytes forever, never tripping the per-read timeout, still dies when its
     * job's deadline does.
     */
    override suspend fun fetchHttp(url: String, user: String?, pass: String?): ByteArray? {
        val ctx = currentCoroutineContext()
        val holder = AtomicReference<HttpURLConnection?>(null)
        val onCancel = ctx.job.invokeOnCompletion { holder.get()?.disconnect() }
        return try {
            withContext(Dispatchers.IO) {
                runInterruptible {
                    val first = open(url)
                    holder.set(first)
                    if (!user.isNullOrEmpty()) {
                        val token = Base64.encodeToString(
                            "$user:${pass ?: ""}".toByteArray(Charsets.UTF_8),
                            Base64.NO_WRAP,
                        )
                        first.setRequestProperty("Authorization", "Basic $token")
                    }
                    if (first.responseCode != HttpURLConnection.HTTP_UNAUTHORIZED) {
                        return@runInterruptible readImage(first, ctx)
                    }
                    // Some cameras refuse both an anonymous and a Basic request with a Digest
                    // challenge. Answer it once; a second 401 is a genuine auth failure.
                    val authorization = SnapshotAuth.digestRetry(
                        user = user,
                        pass = pass,
                        method = "GET",
                        uri = SnapshotAuth.requestTarget(url),
                        challenge = first.getHeaderField("WWW-Authenticate"),
                        cnonce = SnapshotAuth.cnonce(),
                    ) ?: return@runInterruptible null
                    first.disconnect()
                    val retry = open(url)
                    // The holder always points at the live connection, so cancelling the job still
                    // unblocks whichever socket is actually being read.
                    holder.set(retry)
                    retry.setRequestProperty("Authorization", authorization)
                    readImage(retry, ctx)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "snapshot fetch failed (${CameraUri.redact(url)}): ${e.javaClass.simpleName}")
            null
        } finally {
            onCancel.dispose()
            holder.get()?.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = HTTP_TIMEOUT_MS
        c.readTimeout = HTTP_TIMEOUT_MS
        // A redirect could carry the credentials to another host; a snapshot URL that redirects is
        // not worth that risk.
        c.instanceFollowRedirects = false
        return c
    }

    /** The response side shared by both attempts: only a successful, image-typed, small-enough
     *  body is read, and the read itself stays capped and cancellable. */
    private fun readImage(c: HttpURLConnection, ctx: CoroutineContext): ByteArray? {
        if (c.responseCode !in 200..299) return null
        val type = c.contentType?.substringBefore(';')?.trim()?.lowercase()
        if (type != null && !type.startsWith("image/")) return null
        val advertised = c.contentLengthLong.takeIf { it >= 0L }
        if (!SnapshotGuards.acceptContentLength(advertised)) return null
        return c.inputStream.use { readCapped(it, ctx) }
    }

    /** Reads at most [SnapshotGuards.MAX_BYTES]; a body that keeps going past the cap is rejected
     *  rather than truncated (a truncated JPEG is not worth decoding). Aborts as soon as [ctx] is
     *  cancelled. */
    private fun readCapped(input: InputStream, ctx: CoroutineContext): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            ctx.ensureActive()
            val read = input.read(buf)
            if (read < 0) break
            total += read
            if (total > SnapshotGuards.MAX_BYTES) return null
            out.write(buf, 0, read)
        }
        return if (out.size() == 0) null else out.toByteArray()
    }

    override suspend fun grabFrame(
        rtspUriWithCreds: String,
        forceTcp: Boolean,
        deadlineMs: Long,
    ): Bitmap? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(deadlineMs) { grabOnMainLooper(rtspUriWithCreds, forceTcp) }
    }

    /**
     * Opens the stream with audio disabled (a thumbnail must never make a sound or take audio
     * focus), renders into an off-screen [SurfaceTexture], and copies the first rendered frame out
     * with [PixelCopy]. The player, surface and texture are always released.
     */
    private suspend fun grabOnMainLooper(uri: String, forceTcp: Boolean): Bitmap? {
        val handler = Handler(Looper.getMainLooper())
        val texture = SurfaceTexture(false)
        texture.setDefaultBufferSize(MAX_TILE_WIDTH, DEFAULT_TILE_HEIGHT)
        val surface = Surface(texture)
        val player = ExoPlayer.Builder(appContext).build()
        try {
            return suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val requested = AtomicBoolean(false)
                fun finish(bitmap: Bitmap?) {
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resumeWith(Result.success(bitmap))
                }

                fun requestCopy() {
                    if (!requested.compareAndSet(false, true)) return
                    val size = player.videoSize
                    val bitmap = try {
                        Bitmap.createBitmap(
                            tileWidth(size),
                            tileHeight(size),
                            Bitmap.Config.ARGB_8888,
                        )
                    } catch (e: Exception) {
                        finish(null)
                        return
                    }
                    try {
                        PixelCopy.request(surface, bitmap, { result ->
                            finish(if (result == PixelCopy.SUCCESS) bitmap else null)
                        }, handler)
                    } catch (e: Exception) {
                        finish(null)
                    }
                }

                texture.setOnFrameAvailableListener({ requestCopy() }, handler)
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            texture.setDefaultBufferSize(videoSize.width, videoSize.height)
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        requestCopy()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.w(TAG, "frame grab failed (${CameraUri.redact(uri)}): ${error.errorCode}")
                        finish(null)
                    }
                })
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                val source = RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                    .createMediaSource(MediaItem.fromUri(uri))
                player.setMediaSource(source)
                player.setVideoSurface(surface)
                player.prepare()
                player.playWhenReady = true
            }
        } finally {
            texture.setOnFrameAvailableListener(null)
            player.release()
            surface.release()
            texture.release()
        }
    }

    /** Thumbnail width for a video of [size], capped at [MAX_TILE_WIDTH]. */
    private fun tileWidth(size: VideoSize): Int {
        val w = size.width.takeIf { it > 0 } ?: MAX_TILE_WIDTH
        return minOf(w, MAX_TILE_WIDTH)
    }

    /** Thumbnail height for a video of [size], preserving its aspect ratio. */
    private fun tileHeight(size: VideoSize): Int {
        val w = size.width.takeIf { it > 0 } ?: MAX_TILE_WIDTH
        val h = size.height.takeIf { it > 0 } ?: DEFAULT_TILE_HEIGHT
        if (w <= MAX_TILE_WIDTH) return h
        return ((h.toLong() * MAX_TILE_WIDTH) / w).toInt().coerceAtLeast(1)
    }

    private companion object {
        const val TAG = "CameraSnapshots"
        const val HTTP_TIMEOUT_MS = 5_000
        const val MAX_TILE_WIDTH = CameraSnapshots.MAX_TILE_WIDTH
        const val DEFAULT_TILE_HEIGHT = 720
    }
}
