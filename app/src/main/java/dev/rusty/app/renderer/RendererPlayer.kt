package dev.rusty.app.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

/**
 * Thin Android glue around ExoPlayer: implements the player-side [RendererEffect]s and
 * turns [Player.Listener] callbacks into [RendererEvent] dispatches tagged with the
 * media generation active when they fired. All arbitration (what a callback means for
 * transport state, whether it's stale) lives in the tested reducer — this class only
 * translates.
 *
 * ExoPlayer instances must be built and driven from a single thread; every call here is
 * trampolined onto the main looper via [mainHandler] so callers (SOAP connection
 * threads, the GENA eventing executor, etc.) never touch the player directly.
 *
 * [positionMs] is the one exception: SOAP's GetPositionInfo handler runs on a per-
 * connection background thread and must not block waiting on a post to the main thread.
 * Instead of a synchronous round trip, position is cached in a `@Volatile` field that is
 * refreshed on the main thread — once from every relevant [Player.Listener] callback, and
 * continuously via a 500ms ticker while the player is actually playing (position doesn't
 * otherwise change while paused/stopped, so the ticker only needs to run then).
 */
class RendererPlayer(context: Context, private val store: RendererStore) {

    companion object {
        private const val TAG = "RendererPlayer"
        private const val POSITION_POLL_INTERVAL_MS = 500L

        /**
         * Playback gain for announcements, as a linear amplitude scalar (ExoPlayer's own scale).
         *
         * NB this is amplitude, not loudness: 0.8 is about -2 dB, an intentionally gentle trim that
         * takes the edge off a TTS voice without making it hard to hear over ducked music. If it
         * wants to be *noticeably* quieter, the number to reach for is nearer 0.5 (-6 dB).
         */
        private const val ANNOUNCEMENT_GAIN = 0.8f
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cachedPositionMs: Long = 0L
    @Volatile private var currentGeneration: Long = 0L

    private var player: ExoPlayer? = null

    /** Set once on teardown; guards every public method so a late-arriving effect can't
     *  lazily rebuild an ExoPlayer after [release] has run. */
    @Volatile private var released = false

    private val positionTicker = object : Runnable {
        override fun run() {
            val p = player ?: return
            cachedPositionMs = p.currentPosition
            if (p.isPlaying) {
                mainHandler.postDelayed(this, POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val gen = currentGeneration
            when (playbackState) {
                Player.STATE_READY -> {
                    val p = player ?: return
                    cachedPositionMs = p.currentPosition
                    val duration = p.duration.takeIf { it != C.TIME_UNSET }
                    store.dispatch(RendererEvent.PlayerReady(gen, duration, p.isCurrentMediaItemSeekable))
                }
                Player.STATE_ENDED -> {
                    store.dispatch(RendererEvent.PlayerEnded(gen))
                }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            val gen = currentGeneration
            val p = player
            cachedPositionMs = p?.currentPosition ?: cachedPositionMs
            if (isPlaying) {
                store.dispatch(RendererEvent.PlayerPlaying(gen))
                mainHandler.removeCallbacks(positionTicker)
                mainHandler.postDelayed(positionTicker, POSITION_POLL_INTERVAL_MS)
            } else {
                mainHandler.removeCallbacks(positionTicker)
                if (p != null && p.playbackState == Player.STATE_READY) {
                    store.dispatch(RendererEvent.PlayerPaused(gen))
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                store.dispatch(RendererEvent.FocusLost)
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            // Keeps the cached position honest across every jump (seek, auto-transition,
            // internal discontinuity) regardless of reason — the PLAYING-only ticker never
            // runs while paused, so a pause -> Seek -> GetPositionInfo sequence relies on this.
            cachedPositionMs = newPosition.positionMs
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "ExoPlayer error", error)
            store.dispatch(RendererEvent.PlayerError(currentGeneration, error.message ?: error.javaClass.simpleName))
        }
    }

    /** Must run on [mainHandler]'s looper — builds the player lazily on first use. */
    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val p = ExoPlayer.Builder(appContext).build().apply {
            setWakeMode(C.WAKE_MODE_NETWORK)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Announcements play at [ANNOUNCEMENT_GAIN], not full scale. This is the player's OWN
            // gain, deliberately NOT the UPnP RenderingControl volume: that one maps to the device's
            // STREAM_MUSIC level, which Spotify also plays through, so turning it down to soften an
            // announcement would drag the music down with it (and permanently — it outlives the
            // announcement). This gain touches nothing but this player.
            volume = ANNOUNCEMENT_GAIN
            addListener(playerListener)
        }
        player = p
        return p
    }

    fun prepare(uri: String, mime: String?, generation: Long) {
        if (released) return
        mainHandler.post {
            currentGeneration = generation
            cachedPositionMs = 0L
            val p = ensurePlayer()
            // Playback must only ever start via the PlayPlayer effect: playWhenReady survives
            // stop()/setMediaItem(), so without this a Stop -> SetURI replacement would
            // auto-play the new item before HA sends Play.
            p.playWhenReady = false
            val item = MediaItem.Builder().setUri(uri).apply { if (mime != null) setMimeType(mime) }.build()
            p.setMediaItem(item)
            p.prepare()
        }
    }

    fun play() {
        if (released) return
        mainHandler.post { player?.play() }
    }

    fun pause() {
        if (released) return
        mainHandler.post { player?.pause() }
    }

    fun stop() {
        if (released) return
        mainHandler.post {
            player?.stop()
            cachedPositionMs = 0L
        }
    }

    fun seekTo(positionMs: Long) {
        if (released) return
        // Optimistic cache update: while paused the position ticker is idle and the seek's
        // onPositionDiscontinuity lands asynchronously, so refresh the cache immediately to
        // keep a pause -> Seek -> GetPositionInfo sequence from reading the pre-seek position.
        cachedPositionMs = positionMs
        mainHandler.post { player?.seekTo(positionMs) }
    }

    /** Cached, non-blocking; safe to call from any thread (see class doc). */
    fun positionMs(): Long = cachedPositionMs

    /** Frees the ExoPlayer's codec. Call once, on teardown; safe to call more than once. */
    fun release() {
        released = true
        mainHandler.post {
            mainHandler.removeCallbacks(positionTicker)
            player?.removeListener(playerListener)
            player?.release()
            player = null
        }
    }
}
