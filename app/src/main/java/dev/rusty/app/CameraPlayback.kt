package dev.rusty.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import androidx.media3.ui.PlayerView

/** What the live view is doing right now; the only thing the UI needs to render. */
sealed interface LiveState {
    /** A player is up and waiting for the first frame (both watchdogs armed). */
    object Connecting : LiveState

    /** Frames are arriving. */
    object Playing : LiveState

    /** The stream failed transiently; [attempt] failures so far, next rebuild at [nextAtMs]
     *  (absolute, on the plan's clock). */
    data class Reconnecting(val attempt: Int, val nextAtMs: Long) : LiveState

    /** The stream failed in a way retrying cannot fix; only a manual retry leaves this state. */
    data class Fatal(val kind: StreamErrorKind) : LiveState
}

/** The two connect-phase watchdogs. media3 1.4.1's RTSP stack has no connect timeout of its own. */
enum class Watchdog { HANDSHAKE, FIRST_FRAME }

/** Which of a camera's two RTSP streams a session is playing: the required sub stream, or the
 *  optional high-resolution main stream. */
enum class StreamChoice { SUB, MAIN }

/** What the Android owner must do after an event. Effects are executed in list order. */
sealed interface PlaybackEffect {
    /** Post a [w] timer to fire at [atMs] (absolute). */
    data class ArmTimer(val w: Watchdog, val atMs: Long) : PlaybackEffect

    /** Drop every pending timer and retry post. */
    object CancelTimers : PlaybackEffect

    /** Rebuild the player at [atMs] (absolute), then report [CameraPlaybackPlan.onAttemptStarted]. */
    data class ScheduleRetry(val atMs: Long) : PlaybackEffect

    /** Release the current player and detach it from the surface. */
    object TearDownPlayer : PlaybackEffect

    /** Build and prepare a player right now. */
    object BuildPlayer : PlaybackEffect
}

/**
 * The pure core of the live view: watchdogs, error classification and the reconnect ladder.
 *
 * Why the watchdogs are load-bearing (Task 1 de-risk run, on-device): media3 1.4.1's RTSP client
 * has **no connect timeout**. A TCP peer that accepts the socket and then never answers DESCRIBE
 * leaves the player buffering forever with no error and no state change — the
 * [HANDSHAKE][Watchdog.HANDSHAKE] (8s) and [FIRST_FRAME][Watchdog.FIRST_FRAME] (12s) timers here
 * are the only thing that ever notices. The same run showed a camera that vanishes mid-stream
 * surfaces `STATE_ENDED` rather than an error, which is why [onStreamEnded] exists and behaves
 * exactly like a transient failure.
 *
 * Every entry point takes an explicit `now` defaulting to [clock], so tests drive time directly
 * while the Android owner just calls the no-arg form.
 *
 * Not thread-safe: it is driven from the main looper by [CameraPlayback].
 */
class CameraPlaybackPlan(private val clock: () -> Long = { SystemClock.elapsedRealtime() }) {

    companion object {
        /** Deadline for the RTSP handshake (OPTIONS/DESCRIBE/SETUP/PLAY) after a player is built. */
        const val HANDSHAKE_TIMEOUT_MS = 8_000L

        /** Deadline for the first rendered frame after a player is built. */
        const val FIRST_FRAME_TIMEOUT_MS = 12_000L
    }

    /**
     * The current state. After [onReleased] it is frozen at whatever it was when release ran — the
     * plan is terminal, not reset.
     */
    var state: LiveState = LiveState.Connecting
        private set

    /** Consecutive failed attempts; drives [CameraRetryPolicy.nextDelayMs]. */
    private var attempts = 0

    /** When the current healthy spell started, or null when we are not playing. */
    private var playingSince: Long? = null

    private var released = false

    /** A player was just built: arm both connect watchdogs and go [LiveState.Connecting]. */
    fun onAttemptStarted(now: Long = clock()): List<PlaybackEffect> {
        if (released) return emptyList()
        state = LiveState.Connecting
        playingSince = null
        // Cancels first so a caller never has to guarantee no timer is still armed.
        return listOf(PlaybackEffect.CancelTimers) + armWatchdogs(now)
    }

    /** The first frame rendered: the connect phase is over. */
    fun onFirstFrame(now: Long = clock()): List<PlaybackEffect> = onConnected(now)

    /**
     * The player reached `STATE_READY`. Identical to [onFirstFrame], and needed as well as it: with
     * no rendering surface attached (a detached or not-yet-attached view) no frame is ever rendered,
     * so `onRenderedFirstFrame` never fires and the FIRST_FRAME watchdog would tear down a
     * perfectly healthy stream every 12s in an endless rebuild loop against the camera.
     */
    fun onReady(now: Long = clock()): List<PlaybackEffect> = onConnected(now)

    private fun onConnected(now: Long): List<PlaybackEffect> {
        if (released) return emptyList()
        if (state == LiveState.Playing) return emptyList()
        state = LiveState.Playing
        playingSince = now
        return listOf(PlaybackEffect.CancelTimers)
    }

    /**
     * A `PlaybackException` arrived. [msg] must be the FLATTENED message (the exception's own
     * message plus its `cause` chain) — on-device every RTSP failure is code 2000 with the RTSP
     * status text buried in the cause.
     */
    fun onError(code: Int, msg: String?, now: Long = clock()): List<PlaybackEffect> {
        if (released) return emptyList()
        return when (val kind = CameraRetryPolicy.classify(code, msg)) {
            StreamErrorKind.TRANSIENT -> transient(now)
            else -> fatal(kind)
        }
    }

    /** `STATE_ENDED` on a live stream means the camera went away: same handling as a transient. */
    fun onStreamEnded(now: Long = clock()): List<PlaybackEffect> {
        if (released) return emptyList()
        return transient(now)
    }

    /** A connect watchdog expired: the peer never got us to frames, so retry like a transient. */
    fun onWatchdogFired(which: Watchdog, now: Long = clock()): List<PlaybackEffect> {
        if (released) return emptyList()
        // Watchdogs are only meaningful during the connect phase; a timer that slipped past a
        // CancelTimers is stale and must not tear down a healthy stream.
        if (state !is LiveState.Connecting) return emptyList()
        return transient(now)
    }

    /** The user pressed Retry. Only meaningful while stopped ([LiveState.Fatal]) or waiting
     *  ([LiveState.Reconnecting]); it rebuilds immediately and resets the backoff ladder. */
    fun onManualRetry(now: Long = clock()): List<PlaybackEffect> {
        if (released) return emptyList()
        if (state !is LiveState.Fatal && state !is LiveState.Reconnecting) return emptyList()
        attempts = 0
        playingSince = null
        state = LiveState.Connecting
        return listOf(PlaybackEffect.CancelTimers, PlaybackEffect.BuildPlayer) + armWatchdogs(now)
    }

    /** Terminal: cancels everything; every later event is a no-op and [state] stops moving. */
    fun onReleased(): List<PlaybackEffect> {
        if (released) return emptyList()
        released = true
        playingSince = null
        return listOf(PlaybackEffect.CancelTimers, PlaybackEffect.TearDownPlayer)
    }

    private fun armWatchdogs(now: Long): List<PlaybackEffect> = listOf(
        PlaybackEffect.ArmTimer(Watchdog.HANDSHAKE, now + HANDSHAKE_TIMEOUT_MS),
        PlaybackEffect.ArmTimer(Watchdog.FIRST_FRAME, now + FIRST_FRAME_TIMEOUT_MS),
    )

    /**
     * Schedules the next rebuild. A stream that stayed healthy long enough
     * ([CameraRetryPolicy.shouldResetAttempts]) starts the ladder over, so a camera that drops once
     * an hour never crawls at the 15s cap. A failure that arrives while we are already reconnecting
     * or already fatal is ignored — the ladder must not be double-advanced by, say, an error
     * followed by `STATE_ENDED`.
     */
    private fun transient(now: Long): List<PlaybackEffect> {
        if (state is LiveState.Reconnecting || state is LiveState.Fatal) return emptyList()
        val since = playingSince
        if (since != null && CameraRetryPolicy.shouldResetAttempts(now - since)) attempts = 0
        val delay = CameraRetryPolicy.nextDelayMs(attempts)
        attempts += 1
        playingSince = null
        val at = now + delay
        state = LiveState.Reconnecting(attempts, at)
        return listOf(
            PlaybackEffect.CancelTimers,
            PlaybackEffect.TearDownPlayer,
            PlaybackEffect.ScheduleRetry(at),
        )
    }

    private fun fatal(kind: StreamErrorKind): List<PlaybackEffect> {
        if (state is LiveState.Fatal) return emptyList()
        playingSince = null
        state = LiveState.Fatal(kind)
        return listOf(PlaybackEffect.CancelTimers, PlaybackEffect.TearDownPlayer)
    }
}

/**
 * Pure text hygiene for exception messages before they are classified or logged.
 */
object PlaybackErrorText {

    /** `scheme://user:pass@host` userinfo anywhere in a message. */
    private val USERINFO = Regex("//[^/\\s@]*@")

    /**
     * Replaces every embedded URI userinfo in [text] with `//•••@`, so neither
     * [CameraRetryPolicy.classify] nor a log line ever sees a credential. Text with no userinfo is
     * returned unchanged.
     */
    fun stripUserinfo(text: String): String = USERINFO.replace(text, "//•••@")
}

/**
 * Thin Android owner of one live RTSP view: it builds ExoPlayer, feeds player callbacks into
 * [CameraPlaybackPlan] and executes the effects the plan returns. Every decision lives in the plan;
 * this class only interprets.
 *
 * Main-looper only. Credentials exist solely inside the ephemeral [MediaItem] URI built by
 * [CameraUri.withCredentials]; no log line ever carries them (URLs go through [CameraUri.redact],
 * and exception text — which can embed the URI — is never logged, only the error-code name and the
 * classification).
 *
 * Audio: when the camera has audio disabled the audio track is deselected outright. When it is
 * enabled the track stays, but the player starts at volume 0 and only [setAudioActive] (driven by
 * the arbitration commands, Task 11) raises it — camera audio must never be audible before audio
 * focus has actually been granted.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CameraPlayback(
    context: Context,
    /** State changes for the fragment; always delivered on the main looper. */
    private val onState: (LiveState) -> Unit = {},
    /** Fired exactly once, from [release]; the fragment maps it to `ArbEvent.LiveClosed`. */
    private val onClosed: () -> Unit = {},
    /** Keep-screen-on hook: true while a live view is open, false once released. The fragment owns
     *  the actual window flag. */
    private val onKeepScreenOn: (Boolean) -> Unit = {},
    /** The live view's info chip (resolution · measured fps · codec), on the main looper: once a
     *  second while a player is up, and null on teardown. */
    private val onVideoStats: (VideoStats?) -> Unit = {},
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /**
     * The stats ticker's own handler. [PlaybackEffect.CancelTimers] wipes [handler] wholesale on
     * every transient error and manual retry, which would silently kill the ticker; keeping it on
     * a second main-looper handler makes the chip independent of the watchdog timers.
     */
    private val statsHandler = Handler(Looper.getMainLooper())

    /** Frames per second measured from rendered frames. Written on the main looper only (the
     *  frame listener posts its sample here), read by the ticker. */
    private var measuredFps: Int? = null

    /** When the last frame-rate sample landed, on the same clock the meter uses. A stream that
     *  freezes stops closing windows, so without this the chip would keep showing the rate the
     *  video had before it stopped moving. */
    private var lastSampleAtMs = 0L

    private val statsTick = object : Runnable {
        override fun run() {
            if (released || player == null) return
            // Two ticks without a closed window means the video has stopped: drop the rate rather
            // than show a frozen picture's last one. The chip falls back to a placeholder.
            if (measuredFps != null && clock() - lastSampleAtMs > STALE_FPS_MS) {
                measuredFps = null
                lastSampleAtMs = 0L
            }
            onVideoStats(VideoStats(lastVideoFormat, measuredFps))
            statsHandler.postDelayed(this, STATS_TICK_MS)
        }
    }

    private var plan = CameraPlaybackPlan(clock)
    private var player: ExoPlayer? = null

    /** The current player's frame-rate probe, kept so [tearDownPlayer] can clear it again. */
    private var frameMetadataListener: VideoFrameMetadataListener? = null
    private var view: PlayerView? = null

    private var camera: CameraRecord? = null
    private var username: String? = null
    private var password: String? = null

    /** Whether arbitration has granted us audible audio. Survives player rebuilds. */
    private var audioActive = false

    /** Which stream the current session is playing. */
    private var stream = StreamChoice.SUB

    /** The stream the live view is on, for the chrome pill and the hint row. */
    val currentStream: StreamChoice get() = stream

    /** The last video format this session actually saw, for the fatal overlay's codec sentence.
     *  Cleared at the start of every session. */
    @Volatile
    var lastVideoFormat: VideoFormatInfo? = null
        private set

    private var released = false

    /** The plan's view of the world, for the fragment to render on attach. */
    val state: LiveState get() = plan.state

    private val listener = object : Player.Listener {
        override fun onRenderedFirstFrame() {
            if (released) return
            run(plan.onFirstFrame())
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (released) return
            when (playbackState) {
                // READY, not just the first rendered frame, is the liveness signal: with no surface
                // attached nothing is ever rendered, and the FIRST_FRAME watchdog would rebuild a
                // healthy stream forever.
                Player.STATE_READY -> run(plan.onReady())
                // Device evidence: a camera that disappears mid-stream ENDS the stream instead of
                // reporting an error, so ENDED is a disconnect, not a completion.
                Player.STATE_ENDED -> {
                    Log.w(TAG, "live stream ended (${redactedUrl()}) — treating as a disconnect")
                    run(plan.onStreamEnded())
                }
                else -> Unit
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            val f = player?.videoFormat ?: return
            val mime = f.sampleMimeType ?: return
            lastVideoFormat = VideoFormatInfo(mime, f.width, f.height, f.frameRate)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (released) return
            val flattened = flatten(error)
            val kind = CameraRetryPolicy.classify(error.errorCode, flattened)
            // A decoder-init failure often knows the format it choked on, and that is exactly the
            // case the fatal overlay wants to name — so refresh before the plan turns it terminal.
            player?.videoFormat?.let { f ->
                f.sampleMimeType?.let { mime ->
                    lastVideoFormat = VideoFormatInfo(mime, f.width, f.height, f.frameRate)
                }
            }
            // Never log `flattened`: media3 folds the (credentialed) URI into its messages.
            Log.w(TAG, "live error ${error.errorCodeName} -> $kind (${redactedUrl()})")
            run(plan.onError(error.errorCode, flattened))
        }
    }

    /** Attaches (or, with null, detaches) the surface the player renders into. */
    fun attachView(playerView: PlayerView?) {
        if (released) return
        if (view === playerView) return
        view?.player = null
        view = playerView
        playerView?.player = player
    }

    /**
     * Starts a fresh live session for [cam]; [user]/[pass] come from [SecretStore] and are only ever
     * put into the player's own URI.
     *
     * A new session always starts muted: arbitration has not granted focus for it yet, so audio
     * waits for [setAudioActive]. Use [switchTo] to change camera within an open session, which
     * keeps whatever audio state arbitration already granted.
     */
    fun open(cam: CameraRecord, user: String?, pass: String?, stream: StreamChoice = StreamChoice.SUB) {
        if (released) return
        startSession(cam, user, pass, audio = false, stream = stream)
    }

    /**
     * Switches to another camera inside the same live session: full teardown, fresh plan, fresh
     * backoff ladder — but the current audio state is **preserved**. `CameraArbitration` answers
     * `CameraSwitched` with `EnableCameraAudio` only when focus is already held, and it may emit
     * that before this call; resetting to muted here would strand the new stream silent while we
     * still hold audio focus.
     */
    fun switchTo(cam: CameraRecord, user: String?, pass: String?, stream: StreamChoice = StreamChoice.SUB) {
        if (released) return
        startSession(cam, user, pass, audio = audioActive, stream = stream)
    }

    /**
     * Same camera, the other stream: a full teardown and a fresh session, not a seek — the other
     * stream is a whole new RTSP negotiation, so the backoff ladder starts over. Audio state
     * carries over, exactly as in [switchTo]. Refuses MAIN for a camera that has no main URL.
     */
    fun switchStream(next: StreamChoice) {
        if (released) return
        val cam = camera ?: return
        if (next == StreamChoice.MAIN && cam.mainRtspUrl.isNullOrBlank()) return
        startSession(cam, username, password, audio = audioActive, stream = next)
    }

    private fun startSession(cam: CameraRecord, user: String?, pass: String?, audio: Boolean, stream: StreamChoice) {
        handler.removeCallbacksAndMessages(null)
        tearDownPlayer()
        camera = cam
        username = user
        password = pass
        audioActive = audio
        this.stream = stream
        lastVideoFormat = null
        plan = CameraPlaybackPlan(clock)
        buildPlayer()
        onKeepScreenOn(true)
        run(plan.onAttemptStarted())
    }

    /** Raises or drops the camera's audio. No-op for a video-only camera. */
    fun setAudioActive(active: Boolean) {
        if (released) return
        audioActive = active
        player?.volume = playbackVolume()
    }

    /** The user pressed Retry. */
    fun manualRetry() {
        if (released) return
        run(plan.onManualRetry())
    }

    /**
     * Idempotent teardown: cancels timers, releases the player, clears the surface, fires
     * [onClosed] once, drops keep-screen-on. Safe to call from `onDestroyView` and again from
     * `onDestroy`.
     */
    fun release() {
        if (released) return
        released = true
        plan.onReleased()
        handler.removeCallbacksAndMessages(null)
        statsHandler.removeCallbacksAndMessages(null)
        tearDownPlayer()
        onVideoStats(null)
        view?.player = null
        view = null
        camera = null
        username = null
        password = null
        onClosed()
        onKeepScreenOn(false)
    }

    /** Executes the plan's effects in order, then publishes the resulting state. */
    private fun run(effects: List<PlaybackEffect>) {
        if (effects.isEmpty()) return
        for (effect in effects) {
            when (effect) {
                is PlaybackEffect.CancelTimers -> handler.removeCallbacksAndMessages(null)
                is PlaybackEffect.ArmTimer -> armTimer(effect.w, effect.atMs)
                is PlaybackEffect.ScheduleRetry -> scheduleRetry(effect.atMs)
                is PlaybackEffect.TearDownPlayer -> tearDownPlayer()
                is PlaybackEffect.BuildPlayer -> buildPlayer()
            }
        }
        if (!released) onState(plan.state)
    }

    private fun armTimer(w: Watchdog, atMs: Long) {
        handler.postDelayed({ run(plan.onWatchdogFired(w)) }, (atMs - clock()).coerceAtLeast(0L))
    }

    private fun scheduleRetry(atMs: Long) {
        handler.postDelayed({
            buildPlayer()
            run(plan.onAttemptStarted())
        }, (atMs - clock()).coerceAtLeast(0L))
    }

    private fun buildPlayer() {
        tearDownPlayer()
        val cam = camera ?: return
        val rawUrl = rawStreamUrl(cam)
        val uri = CameraUri.withCredentials(rawUrl, username, password)
        val loadControl = DefaultLoadControl.Builder()
            // Live video: a small buffer keeps latency down and recovers fast after a rebuffer.
            .setBufferDurationsMs(1_000, 3_000, 500, 500)
            .build()
        val p = ExoPlayer.Builder(appContext).setLoadControl(loadControl).build()
        if (!cam.audioEnabled) {
            p.trackSelectionParameters = p.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                .build()
        }
        // Audible only once arbitration says so; a fresh player is always silent.
        p.volume = playbackVolume()
        p.addListener(listener)
        // One meter per PLAYER, not per session: retries and reconnects rebuild the player without
        // going through startSession, and a shared meter would carry a stale window across the
        // outage. The listener runs on media3's playback thread and touches only `meter`, which
        // that closure owns; the sample is applied on the main looper, and only while this player
        // is still the current one.
        val meter = StreamMeter()
        val frameListener = VideoFrameMetadataListener { _, _, _, _ ->
            val sample = meter.onFrame(0, clock())
            // statsHandler, not `handler`: PlaybackEffect.CancelTimers wipes the latter, and a
            // sample posted there could be dropped with the watchdog timers.
            if (sample != null) statsHandler.post {
                if (player === p) { measuredFps = sample.fps; lastSampleAtMs = clock() }
            }
        }
        p.setVideoFrameMetadataListener(frameListener)
        frameMetadataListener = frameListener
        p.setMediaSource(
            RtspMediaSource.Factory()
                .setForceUseRtpTcp(cam.forceTcp)
                .createMediaSource(MediaItem.fromUri(uri)),
        )
        player = p
        view?.player = p
        p.prepare()
        p.playWhenReady = true
        statsHandler.removeCallbacks(statsTick)
        statsHandler.postDelayed(statsTick, STATS_TICK_MS)
        Log.i(TAG, "live open ${CameraUri.redact(rawUrl)} tcp=${cam.forceTcp} stream=$stream")
    }

    private fun tearDownPlayer() {
        // Before the early return: an ordinary teardown must clear the chip even when there is no
        // player left to tear down.
        statsHandler.removeCallbacks(statsTick)
        measuredFps = null
        lastSampleAtMs = 0L
        onVideoStats(null)
        val p = player ?: return
        player = null
        view?.player = null
        p.removeListener(listener)
        // Symmetric with addListener/removeListener above: the frame listener holds this player,
        // and clearing it stops any late frame from posting a sample for a player being released.
        frameMetadataListener?.let { p.clearVideoFrameMetadataListener(it) }
        frameMetadataListener = null
        p.setVideoSurface(null)
        p.release()
    }

    private fun playbackVolume(): Float =
        if (audioActive && camera?.audioEnabled == true) 1f else 0f

    /**
     * The URL the current [stream] actually plays, still carrying whatever credentials the record
     * itself holds — never log this directly, only [CameraUri.redact] of it.
     *
     * The single source of truth for the choice: [buildPlayer] opens what this returns and
     * [redactedUrl] redacts what this returns, so the log can never drift into naming a stream
     * other than the one playing.
     */
    private fun rawStreamUrl(cam: CameraRecord): String =
        if (stream == StreamChoice.MAIN) cam.mainRtspUrl?.takeIf { it.isNotBlank() } ?: cam.rtspUrl else cam.rtspUrl

    /** The stream the session is actually on, credentials stripped. The only URL shape any log
     *  line here may ever carry. */
    private fun redactedUrl(): String = camera?.let { CameraUri.redact(rawStreamUrl(it)) } ?: "?"

    /**
     * Folds an exception's `cause` chain into one string, with any embedded URI credentials
     * stripped. media3 reports RTSP failures as code 2000 with the actual status line
     * ("RTSP/1.0 401 Unauthorized") living in a cause, so classification has to see the whole
     * chain — but those messages also quote the credentialed URI, and a password containing "401"
     * would otherwise turn a retryable blip into a permanent auth failure. Never logged.
     */
    private fun flatten(error: PlaybackException): String {
        val sb = StringBuilder(error.message ?: "")
        var cause: Throwable? = error.cause
        var depth = 0
        while (cause != null && depth < 8) {
            cause.message?.let { sb.append(" | ").append(it) }
            cause = cause.cause
            depth += 1
        }
        return PlaybackErrorText.stripUserinfo(sb.toString())
    }

    private companion object {
        const val TAG = "CameraPlayback"
        const val STATS_TICK_MS = 1_000L

        /** How long a rendered-frame drought is tolerated before the chip stops claiming a rate.
         *  Two ticks, so an ordinary late window never blanks it. */
        const val STALE_FPS_MS = 2_000L
    }
}
