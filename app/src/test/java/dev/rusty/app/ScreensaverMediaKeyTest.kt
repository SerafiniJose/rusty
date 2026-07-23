package dev.rusty.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing decision — which keys reach a screensaver theme's transport surface versus falling
 * through to the immediate-exit path — now lives in [ShellKeyRouting] (covered by
 * [ShellKeyRoutingTest]); on a D-pad device the user has no focusable control over the full-screen
 * overlay, so a theme (or any other shell code) that consumed navigation keys directly would trap
 * the remote, the v2.0.0 Shield regression.
 *
 * This file pins the shared [TvRemote.dispatchTransportKey] primitive that routing decision is
 * built on: it remains correct/useful coverage of that object in its own right.
 */
class ScreensaverMediaKeyTest {

    private class Calls {
        var playPause = 0
        var next = 0
        var previous = 0
    }

    private fun route(keyCode: Int, calls: Calls, repeatCount: Int = 0): Boolean =
        TvRemote.dispatchTransportKey(
            keyCode, KeyEvent.ACTION_DOWN, repeatCount,
            onPlayPause = { calls.playPause++ },
            onNext = { calls.next++ },
            onPrevious = { calls.previous++ },
        )

    private val navigationAndOtherKeys = listOf(
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_MENU,
        KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_VOLUME_UP,
    )

    // ---- TvRemote.dispatchTransportKey: existing coverage of the shared primitive -----------

    @Test fun transportKeysAreRoutedToTheTheme() {
        val calls = Calls()
        assertTrue(route(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, calls))
        assertTrue(route(KeyEvent.KEYCODE_MEDIA_PLAY, calls))
        assertTrue(route(KeyEvent.KEYCODE_MEDIA_PAUSE, calls))
        assertTrue(route(KeyEvent.KEYCODE_MEDIA_NEXT, calls))
        assertTrue(route(KeyEvent.KEYCODE_MEDIA_PREVIOUS, calls))
        assertEquals(3, calls.playPause)
        assertEquals(1, calls.next)
        assertEquals(1, calls.previous)
    }

    @Test fun everyNavigationKeyStillFallsThroughToExit() {
        val calls = Calls()
        navigationAndOtherKeys.forEach { assertFalse("key $it must not be consumed", route(it, calls)) }
        assertEquals(0, calls.playPause + calls.next + calls.previous)
    }

    @Test fun autoRepeatDoesNotRetrigger() {
        val calls = Calls()
        assertTrue(route(KeyEvent.KEYCODE_MEDIA_NEXT, calls, repeatCount = 3))
        assertEquals(0, calls.next)
    }

    @Test fun theUpEventIsConsumedButTriggersNothing() {
        val calls = Calls()
        val consumed = TvRemote.dispatchTransportKey(
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.ACTION_UP, 0,
            onPlayPause = { calls.playPause++ },
            onNext = { calls.next++ },
            onPrevious = { calls.previous++ },
        )
        assertTrue(consumed) // so the up never leaks to a system default
        assertEquals(0, calls.next)
    }
}
