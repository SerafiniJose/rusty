package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented lifecycle checks for [LyricsActivity].
 *
 * Device-pending: these tests compile (verified via assembleDebugAndroidTest) but have NOT been
 * executed on a physical device or emulator. Run with `connectedDebugAndroidTest` when a device
 * is available.
 *
 * What is asserted:
 *  1. Open LyricsActivity and finish it immediately (simulating a finish() called while a lyrics
 *     fetch or Palette callback might still be in flight) → no crash.
 *     The lifecycleScope coroutine and Palette callback both guard on lifecycle >= STARTED, so
 *     neither touches the destroyed Activity's views.
 */
@RunWith(AndroidJUnit4::class)
class LyricsLifecycleTest {

    /**
     * Finishing LyricsActivity while a background fetch might be in flight must not crash.
     *
     * The coroutine launched in startFetch() checks `lifecycle.currentState.isAtLeast(STARTED)`
     * before touching views; the Palette callback checks the request id and lifecycle state.
     * Calling finish() immediately exercises both guards.
     */
    @Test
    fun finishDuringFetch_doesNotCrash() {
        ActivityScenario.launch(LyricsActivity::class.java).use { scenario ->
            // Synchronise on the main thread to let onCreate complete.
            scenario.onActivity { activity ->
                assertNotNull("Activity must be non-null at launch", activity)
                // Immediately finish — simulates the user dismissing during a fetch.
                activity.finish()
            }
            // Reaching here without an uncaught exception means the lifecycle guards worked.
        }
    }
}
