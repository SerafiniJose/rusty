package dev.rusty.app

import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * A muted, looping, controls-less video surface for Spotify Canvas loops. The real audio is the
 * librespot stream, so this view is always silenced. Release [release] on detach/stop to free the
 * single video codec (important on low-end always-on devices).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CanvasPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    private var player: ExoPlayer? = null

    /** The pending [onFirstFrame] watch: the media3 listener and its deadline, or null for none. */
    private var firstFrameListener: Player.Listener? = null
    private var firstFrameDeadline: Runnable? = null

    private val playerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        // Transparent, so a loop arriving over the album art fades in rather than punching a black
        // hole in the card while it loads. The dip's scrim is what covers the shutter at a swap.
        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    /**
     * The dip's black scrim, over the video and under nothing. A Canvas layer always sits on top of
     * the picture it replaces, so a track transition has to darken the loop in place; fading the
     * view itself would dissolve into the album art behind it instead. See [CanvasSwap].
     */
    private val scrim = View(context).apply {
        setBackgroundColor(Color.BLACK)
        alpha = 0f
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    // ---- The two channels a transition moves (see CanvasLayer) --------------------------------
    //
    // `opacity` is the loop against the picture behind it: it is what a loop arriving over the album
    // art fades up, and what a loop leaving fades down. `dim` is the loop against black, inside the
    // view: it is what a track change rides. They are kept apart deliberately — dipping the opacity
    // mid-transition uncovers the cover art the on-screen crossfade is dissolving at that instant.

    /** How much of the loop shows against whatever is behind it: 0 = gone, 1 = fully covering. */
    var opacity: Float
        get() = alpha
        set(value) {
            animate().cancel()
            val v = value.coerceIn(0f, 1f)
            alpha = v
            opacityTargetForTest = v
        }

    /** How black the loop is right now: 0 = untouched, 1 = nothing of it (or of the card) shows. */
    var dim: Float
        get() = scrim.alpha
        set(value) {
            scrim.animate().cancel()
            val v = value.coerceIn(0f, CanvasSwap.DIM_FULL)
            scrim.alpha = v
            dimTargetForTest = v
        }

    /** Fade the loop to [target] over [durationMs], then run [onEnd]. Replaces any fade in flight. */
    fun animateOpacity(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        val v = target.coerceIn(0f, 1f)
        opacityTargetForTest = v
        animate().cancel()
        animate().alpha(v).setDuration(durationMs).withEndAction { onEnd?.invoke() }.start()
    }

    /** Ride the scrim to [target] over [durationMs], then run [onEnd]. Replaces any dim in flight. */
    fun animateDim(target: Float, durationMs: Long, onEnd: (() -> Unit)? = null) {
        val v = target.coerceIn(0f, CanvasSwap.DIM_FULL)
        dimTargetForTest = v
        scrim.animate().cancel()
        scrim.animate().alpha(v).setDuration(durationMs).withEndAction { onEnd?.invoke() }.start()
    }

    /** Stop a fade in flight, leaving the loop wherever it is. */
    fun cancelOpacity() {
        animate().cancel()
    }

    /** Stop a dim in flight, leaving the scrim wherever it is. */
    fun cancelDim() {
        scrim.animate().cancel()
    }

    /**
     * Instrumentation: where the last move on each channel was aimed. A ViewPropertyAnimator on a
     * view that is not attached to a window does not run, so a test asserts the intent rather than
     * waiting out a frame callback that may never come.
     */
    var opacityTargetForTest: Float = 0f
        private set
    var dimTargetForTest: Float = 0f
        private set

    private fun ensurePlayer(): ExoPlayer {
        return player ?: buildPlayer().also {
            it.repeatMode = Player.REPEAT_MODE_ALL
            it.volume = 0f
            playerView.player = it
            player = it
        }
    }

    /**
     * Builds the player with a forward buffer sized for what this actually is: a silent ~8 s clip on
     * REPEAT_MODE_ALL. [DefaultLoadControl] defaults to 50 s min/max, so the stock player buffers
     * many repetitions of the loop ahead — a burst of loader threads at Canvas start, on a low-end
     * always-on device whose librespot stream is the thing that must not be starved. Nothing depends
     * on this video, so a stall may simply resume.
     */
    private fun buildPlayer(): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS,
            )
            // Keep the small time window authoritative instead of the large default video byte
            // target, which a high-bitrate clip would otherwise hit first.
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        return ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            // A muted screen-bound loop never needs to keep the CPU awake: with the screen off
            // nobody sees it, and media3 >= 1.9 would otherwise hold a partial wake lock for
            // every Canvas on an always-on device.
            .setWakeMode(C.WAKE_MODE_NONE)
            .build()
    }

    /** Load [url], loop it muted, and start. Replaces any currently-playing loop. */
    fun play(url: String) {
        cancelFirstFrame()
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(url))
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
    }

    /**
     * Run [onReady] once the loop loaded by the most recent [play] has actually drawn a frame, or
     * at [deadlineMs] from now if it never does. Exactly one of the two fires, on the main thread.
     * A second call replaces the first; [cancelFirstFrame] drops it.
     */
    fun onFirstFrame(deadlineMs: Long, onReady: () -> Unit) {
        cancelFirstFrame()
        val p = player ?: run { onReady(); return }
        var settled = false
        val settle = {
            if (!settled) {
                settled = true
                cancelFirstFrame()
                onReady()
            }
        }
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() = settle()
        }
        firstFrameListener = listener
        p.addListener(listener)
        val deadline = Runnable { settle() }
        firstFrameDeadline = deadline
        postDelayed(deadline, deadlineMs)
    }

    /** Drop a pending [onFirstFrame] watch without running it. */
    fun cancelFirstFrame() {
        firstFrameListener?.let { player?.removeListener(it) }
        firstFrameListener = null
        firstFrameDeadline?.let { removeCallbacks(it) }
        firstFrameDeadline = null
    }

    /** Stop playback and detach media, keeping the player for reuse. */
    fun clear() {
        cancelFirstFrame()
        player?.apply {
            playWhenReady = false
            clearMediaItems()
        }
    }

    /** Release the ExoPlayer entirely (frees the codec). Safe to call repeatedly. */
    fun release() {
        cancelFirstFrame()
        playerView.player = null
        player?.release()
        player = null
    }

    /** true = center-crop full-bleed; false = fit inside the card. */
    fun setFill(fill: Boolean) {
        playerView.resizeMode =
            if (fill) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
    }

    val isMutedForTest: Boolean get() = (player?.volume ?: 0f) == 0f
    val isLoopingForTest: Boolean get() = player?.repeatMode == Player.REPEAT_MODE_ALL

    /** Instrumentation: true while this view owns an ExoPlayer, and so a video codec. */
    val hasPlayerForTest: Boolean get() = player != null

    private companion object {
        // A Canvas loop is ~3-8 s; one second ahead is plenty and keeps the loader burst small.
        const val MIN_BUFFER_MS = 1_000
        const val MAX_BUFFER_MS = 3_000
        const val BUFFER_FOR_PLAYBACK_MS = 250
        // Nothing depends on this video, so never hold playback back to re-buffer it.
        const val BUFFER_AFTER_REBUFFER_MS = 0
    }
}
