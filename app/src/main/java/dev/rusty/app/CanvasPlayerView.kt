package dev.rusty.app

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
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
    private val playerView = PlayerView(context).apply {
        useController = false
        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    init {
        addView(playerView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    private fun ensurePlayer(): ExoPlayer {
        return player ?: ExoPlayer.Builder(context).build().also {
            it.repeatMode = Player.REPEAT_MODE_ALL
            it.volume = 0f
            playerView.player = it
            player = it
        }
    }

    /** Load [url], loop it muted, and start. Replaces any currently-playing loop. */
    fun play(url: String) {
        val p = ensurePlayer()
        p.setMediaItem(MediaItem.fromUri(url))
        p.volume = 0f
        p.repeatMode = Player.REPEAT_MODE_ALL
        p.prepare()
        p.playWhenReady = true
    }

    /** Stop playback and detach media, keeping the player for reuse. */
    fun clear() {
        player?.apply {
            playWhenReady = false
            clearMediaItems()
        }
    }

    /** Release the ExoPlayer entirely (frees the codec). Safe to call repeatedly. */
    fun release() {
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
}
