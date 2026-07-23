package dev.rusty.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shell's whole while-saver-showing key decision. The contract (see the 2026-07-22 spec):
 * media keys mean MUSIC everywhere; the D-pad means PHOTOS whenever a slideshow owns the remote;
 * BACK/UP are the only exits from an owning slideshow; system keys (assistant + volume) are never
 * consumed anywhere (the caller checks [ShellKeyRouting.isSystemKey] before routing); every key
 * still wakes a non-owning saver so a remote user is never trapped (the v2.0.0 Shield rule).
 */
class ShellKeyRoutingTest {

    private val transportKeys = listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
        KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND,
        KeyEvent.KEYCODE_HEADSETHOOK,
    )
    private val navKeys = listOf(
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )
    private val exitKeys = listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_UP)
    private val otherKeys = listOf(
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_SPACE,
        KeyEvent.KEYCODE_MENU,
    )
    private val systemKeys = listOf(
        KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_ASSIST, KeyEvent.KEYCODE_VOICE_ASSIST,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
    )

    @Test
    fun `transport keys go to Spotify whenever it has a track, owning slideshow or not`() {
        listOf(true, false).forEach { owns ->
            transportKeys.forEach { key ->
                assertEquals(
                    SaverKeyAction.SPOTIFY_TRANSPORT,
                    ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = owns, spotifyActive = true),
                )
            }
        }
    }

    @Test
    fun `transport keys with Spotify idle are dead keys on an owning slideshow`() {
        transportKeys.forEach { key ->
            assertEquals(
                SaverKeyAction.CONSUME,
                ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = true, spotifyActive = false),
            )
        }
    }

    @Test
    fun `transport keys with Spotify idle wake a non-owning saver`() {
        transportKeys.forEach { key ->
            assertEquals(
                SaverKeyAction.WAKE,
                ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = false, spotifyActive = false),
            )
        }
    }

    @Test
    fun `nav keys drive an owning slideshow regardless of music state`() {
        listOf(true, false).forEach { active ->
            navKeys.forEach { key ->
                assertEquals(
                    SaverKeyAction.SLIDESHOW_NAV,
                    ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = true, spotifyActive = active),
                )
            }
        }
    }

    @Test
    fun `BACK and UP exit an owning slideshow`() {
        exitKeys.forEach { key ->
            assertEquals(
                SaverKeyAction.WAKE,
                ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = true, spotifyActive = true),
            )
        }
    }

    @Test
    fun `all other keys are consumed no-ops on an owning slideshow`() {
        otherKeys.forEach { key ->
            assertEquals(
                SaverKeyAction.CONSUME,
                ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = true, spotifyActive = true),
            )
        }
    }

    @Test
    fun `every non-transport key wakes a non-owning saver — the no-trap rule`() {
        (navKeys + exitKeys + otherKeys).forEach { key ->
            listOf(true, false).forEach { active ->
                assertEquals(
                    "key $key must wake",
                    SaverKeyAction.WAKE,
                    ShellKeyRouting.routeWhileSaverShowing(key, slideshowOwnsRemote = false, spotifyActive = active),
                )
            }
        }
    }

    @Test
    fun `system keys are recognized and nothing else is`() {
        systemKeys.forEach { assertTrue(ShellKeyRouting.isSystemKey(it)) }
        (transportKeys + navKeys + exitKeys + otherKeys).forEach {
            assertFalse("key $it is not a system key", ShellKeyRouting.isSystemKey(it))
        }
    }

    @Test
    fun `nav key set is exactly LEFT RIGHT CENTER ENTER`() {
        navKeys.forEach { assertTrue(ShellKeyRouting.isNavKey(it)) }
        (transportKeys + exitKeys + otherKeys + systemKeys).forEach {
            assertFalse("key $it is not a nav key", ShellKeyRouting.isNavKey(it))
        }
    }

    @Test
    fun `play-pause toggles to pause only while status is Playing`() {
        assertTrue(ShellKeyRouting.togglesToPause("Playing"))
        listOf("Paused", "Loading", "Stopped", "Listening", "").forEach {
            assertFalse(ShellKeyRouting.togglesToPause(it))
        }
    }
}
