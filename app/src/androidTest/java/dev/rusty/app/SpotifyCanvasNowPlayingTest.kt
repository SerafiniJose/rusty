package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * With the Canvas toggle OFF (the default), a playing track must NOT activate the video layer —
 * the now-playing screen stays album-art-only, identical to today.
 */
@RunWith(AndroidJUnit4::class)
class SpotifyCanvasNowPlayingTest {

    private fun playing() = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "Rusty",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "T", trackArtist = "A", coverArtUrl = null,
            trackId = "track-1", sessionUser = "u", elapsedMs = 0L, durationMs = 1L,
        ),
    )

    @Test fun toggleOff_noCanvasActivation() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences("spotify_receiver_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(CanvasSettings.KEY_CANVAS_ENABLED, false).apply()
        val store = RustyApp.from(ctx)
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.switchToForTest(FeatureId.SPOTIFY)
                store.dispatch(playing())
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                val frag = activity.currentFragmentForTest() as? SpotifyFragment
                assertFalse("Canvas must stay inactive when the toggle is OFF",
                    frag?.canvasIsActiveForTest() == true)
            }
        }
    }
}
