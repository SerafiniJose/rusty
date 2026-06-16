package dev.rusty.app

import android.view.KeyEvent

/**
 * Shared hardware transport-key handling for TV remotes (NVIDIA Shield), Bluetooth remotes,
 * and wired/BT headsets. Both the now-playing and lyrics screens route their `dispatchKeyEvent`
 * through [dispatchTransportKey] so the remote's dedicated PLAY/PAUSE/NEXT/PREVIOUS keys drive
 * playback even though the app registers no MediaSession — on a TV the receiver UI is always the
 * foreground activity, so a window-level key handler is sufficient and far simpler.
 *
 * DPAD_CENTER / ENTER are deliberately NOT handled here: they must keep their native
 * "activate the focused control" behaviour so D-pad navigation (settings, transport buttons,
 * sheet rows) keeps working. Center-as-play/pause is satisfied instead by giving the play/pause
 * button the default focus.
 */
object TvRemote {

    private fun isTransportKey(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_MEDIA_PLAY,
        KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_HEADSETHOOK,
        KeyEvent.KEYCODE_MEDIA_NEXT,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
        KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_REWIND -> true
        else -> false
    }

    /**
     * If [event] is a transport media key, runs the matching action once (on the initial
     * ACTION_DOWN, ignoring auto-repeat) and returns true so the caller consumes BOTH the down
     * and the up — preventing the key from falling through to any system default. Returns false
     * for every other key so normal navigation/typing is untouched.
     */
    fun dispatchTransportKey(
        event: KeyEvent,
        onPlayPause: () -> Unit,
        onNext: () -> Unit,
        onPrevious: () -> Unit,
    ): Boolean {
        if (!isTransportKey(event.keyCode)) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT,
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> onNext()
                KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                KeyEvent.KEYCODE_MEDIA_REWIND -> onPrevious()
                else -> onPlayPause() // PLAY / PAUSE / PLAY_PAUSE / HEADSETHOOK
            }
        }
        return true
    }
}
