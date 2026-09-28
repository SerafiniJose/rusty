package dev.rusty.app

import android.view.View

/**
 * Drives one [CanvasPlayerView] through track transitions: holds the outgoing loop while the next
 * URL resolves, swaps the media item at the bottom of the dip, and fades in and out. Owned by both
 * the now-playing screen and the lockscreen's Canvas theme, so the choreography lives in one place.
 *
 * [CanvasSwap] decides what to do; this does the Android part of it.
 *
 * Two different channels carry the two different jobs, and mixing them up is what made a
 * canvas → canvas transition read as canvas → cover → next cover → next canvas:
 *  - **arriving and leaving** move the view's own alpha, because a loop that starts or ends really
 *    is dissolving with the album art it sits on;
 *  - **the dip in the middle** moves the scrim inside [CanvasPlayerView], because the loop is not
 *    going anywhere. Fading the view there would have uncovered the very cover art the on-screen
 *    crossfade is dissolving underneath it, so the picture crossed twice.
 */
class CanvasLayer(private val player: CanvasPlayerView) {

    /** The loop currently loaded into the player, or null when the layer is empty. */
    private var currentUrl: String? = null

    /** The loop waiting for the dip to reach black. */
    private var pendingUrl: String? = null

    /** True while the scrim is on its way to black; a second dip must not restart that ramp. */
    private var dipping = false

    /** Posted once the layer is black with nothing to swap in. See [armHoldDeadline]. */
    private var holdDeadline: Runnable? = null

    /** True while a loop is on screen (visible at any dim, black included). */
    var isShowing: Boolean = false
        private set

    /**
     * React to [state]. [transitionMs] is the crossfade's own length — the same window the album-art
     * dissolve uses — so the video stays in step with the rest of the screen.
     */
    fun apply(state: CanvasState, transitionMs: Long) {
        when (val action = CanvasSwap.decide(isShowing, currentUrl, state)) {
            CanvasAction.Nothing -> Unit
            CanvasAction.Hold -> dipToBlack(CanvasSwap.rampMs(transitionMs))
            CanvasAction.Hide -> hide(CanvasSwap.rampMs(transitionMs))
            is CanvasAction.Start -> start(action.url)
            CanvasAction.Resume -> resume(CanvasSwap.rampMs(transitionMs))
            is CanvasAction.Swap -> swap(action.url, CanvasSwap.rampMs(transitionMs))
        }
    }

    /** Snaps the layer to empty without animating — view teardown, or the saver taking over. */
    fun reset() {
        cancelHoldDeadline()
        dipping = false
        player.cancelFirstFrame()
        player.opacity = 0f
        player.dim = 0f
        player.visibility = View.GONE
        currentUrl = null
        pendingUrl = null
        isShowing = false
    }

    private fun start(url: String) {
        pendingUrl = null
        cancelHoldDeadline()
        dipping = false
        currentUrl = url
        isShowing = true
        player.dim = 0f // a loop arriving over the art is never born dark, whatever the last one left
        player.play(url)
        player.visibility = View.VISIBLE
        player.animateOpacity(1f, CanvasSwap.ARRIVAL_FADE_MS)
    }

    /**
     * Ride the scrim up to black. If a URL is already waiting it is applied there; if it arrives
     * later, [swap] finds the layer parked in the dark and can swap immediately — either way the
     * media item is only ever replaced behind an opaque scrim, so neither media3's transparent
     * shutter nor the album art beneath the video is ever on screen during the change.
     */
    private fun dipToBlack(rampMs: Long) {
        // The ramp already in flight lands exactly where a second one would, and restarting it
        // would stretch the dip past the audio crossfade it is meant to move with.
        if (dipping) return
        dipping = true
        cancelHoldDeadline()
        // Any up-ramp still waiting on the previous loop's first frame belongs to a transition this
        // one supersedes; letting it fire would lift the scrim in the middle of this dip.
        player.cancelFirstFrame()
        player.animateDim(CanvasSwap.DIM_FULL, rampMs) {
            dipping = false
            val url = pendingUrl
            if (url != null) applyUnderDip(url, rampMs) else armHoldDeadline(rampMs)
        }
    }

    /**
     * Black is a waiting room, not a destination. If the next loop's URL never turns up — a slow
     * resolve, a dead network, a token refresh — bring the outgoing loop back rather than hold the
     * card dark; [swap] dips again if the URL lands later.
     */
    private fun armHoldDeadline(rampMs: Long) {
        cancelHoldDeadline()
        val lift = Runnable {
            holdDeadline = null
            if (pendingUrl == null) player.animateDim(0f, rampMs)
        }
        holdDeadline = lift
        player.postDelayed(lift, CanvasSwap.DIP_HOLD_MS)
    }

    private fun cancelHoldDeadline() {
        holdDeadline?.let { player.removeCallbacks(it) }
        holdDeadline = null
    }

    /** The loop on screen is the right one already: lift the dip, leave the player untouched. */
    private fun resume(rampMs: Long) {
        pendingUrl = null
        cancelHoldDeadline()
        dipping = false
        player.cancelFirstFrame()
        player.animateDim(0f, rampMs)
    }

    private fun swap(url: String, rampMs: Long) {
        pendingUrl = url
        // Already black: the dip has done its job, swap now.
        if (CanvasSwap.isUnderDip(player.dim)) {
            applyUnderDip(url, rampMs)
            return
        }
        dipToBlack(rampMs)
    }

    /**
     * Swap the media item behind the scrim and bring the loop back — but only once it has drawn.
     * The shutter is transparent, so lifting the scrim over a surface that has not rendered yet
     * would show the album art through the video and undo the whole dip.
     */
    private fun applyUnderDip(url: String, rampMs: Long) {
        pendingUrl = null
        cancelHoldDeadline()
        dipping = false
        currentUrl = url
        isShowing = true
        player.play(url)
        player.visibility = View.VISIBLE
        player.onFirstFrame(CanvasSwap.FIRST_FRAME_GRACE_MS) {
            player.animateDim(0f, rampMs)
        }
    }

    private fun hide(rampMs: Long) {
        pendingUrl = null
        currentUrl = null
        isShowing = false
        cancelHoldDeadline()
        dipping = false
        player.cancelFirstFrame()
        player.animateOpacity(0f, rampMs) {
            player.visibility = View.GONE
            player.clear()
            // Leave the scrim clear for the next loop: a Hold that turned out to be a track with no
            // canvas parks it dark, and the next Start would otherwise fade in on black.
            player.dim = 0f
        }
    }
}
