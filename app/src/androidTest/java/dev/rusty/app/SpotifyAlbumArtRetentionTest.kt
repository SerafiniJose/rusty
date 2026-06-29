package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression for "switching Home Assistant → Spotify loses the album art".
 *
 * When Spotify is hidden it is capped at `Lifecycle.State.CREATED`, which DESTROYS its view
 * (`onDestroyView` runs). The Coil-loaded bitmap dies with the ImageView, but the fragment-instance
 * render-dedupe guard `loadedCoverUrl` survives. On return the view is recreated, yet
 * `renderAlbumArt` early-returned on `url == loadedCoverUrl` and never reloaded the fresh ImageView —
 * so the art was gone. The fix resets the view-bound render caches on `onDestroyView`.
 *
 * Uses an `android.resource://` cover URI so Coil loads offline (no network) and the test is
 * deterministic on a device/emulator. Device-bound: run via `connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class SpotifyAlbumArtRetentionTest {

    private fun playingEvent(coverUri: String) = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "Rusty",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Test Track",
            trackArtist = "Test Artist",
            coverArtUrl = coverUri,
            trackId = "track-1",
            sessionUser = "tester",
            elapsedMs = 1_000L,
            durationMs = 200_000L,
        ),
    )

    /** Drains the main looper and polls until the Spotify fragment's album-art ImageView has a
     *  drawable, up to ~3s. Returns whether art is present. */
    private fun awaitArt(scenario: ActivityScenario<HomeActivity>): Boolean {
        val instr = InstrumentationRegistry.getInstrumentation()
        repeat(60) {
            instr.waitForIdleSync()
            var present = false
            scenario.onActivity { activity ->
                present = (activity.currentFragmentForTest() as? SpotifyFragment)
                    ?.albumArtHasImageForTest() == true
            }
            if (present) return true
            Thread.sleep(50)
        }
        return false
    }

    @Test
    fun albumArt_survivesHomeAssistantToSpotifySwitch() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        // HA must be enabled+configured so the ring contains it and we can switch to it.
        ctx.getSharedPreferences("spotify_receiver_prefs", android.content.Context.MODE_PRIVATE)
            .edit()
            .putBoolean(HomeAssistantFeature.KEY_ENABLED, true)
            .putString(HomeAssistantFeature.KEY_URL, "http://homeassistant.local:8123")
            .apply()

        val store = RustyApp.from(ctx)
        val coverUri = "android.resource://${ctx.packageName}/${R.drawable.ic_play}"

        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            // Spotify is the start feature. Push a playing track WITH cover art.
            scenario.onActivity { activity ->
                activity.switchToForTest(FeatureId.SPOTIFY)
                store.dispatch(playingEvent(coverUri))
            }
            assertTrue("Album art must load on the initially-shown Spotify face", awaitArt(scenario))

            // Switch to Home Assistant (destroys the Spotify view) and back (recreates it).
            scenario.onActivity { it.switchToForTest(FeatureId.HOME_ASSISTANT) }
            scenario.onActivity { it.switchToForTest(FeatureId.SPOTIFY) }

            assertTrue(
                "Album art must be restored after HA → Spotify switch (recreated view)",
                awaitArt(scenario),
            )
        }
    }
}
