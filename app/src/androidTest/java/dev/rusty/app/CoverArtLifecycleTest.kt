package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented lifecycle checks for cover-art Palette callbacks in [SpotifyFragment].
 *
 * Device-pending: these tests compile (verified via assembleDebugAndroidTest) but have NOT been
 * executed on a physical device or emulator. Run with `connectedDebugAndroidTest` when a device
 * is available.
 *
 * What is asserted:
 *  1. Rapid configuration changes (recreate) while cover art might be loading in the background
 *     must not crash. onDestroyView disposes the Coil request and increments artworkRequestId so
 *     any in-flight Palette callback becomes a no-op.
 *  2. Finishing the activity immediately after launch (simulating rapid dismiss during art load)
 *     must not crash or produce a stale-apply against destroyed views.
 */
@RunWith(AndroidJUnit4::class)
class CoverArtLifecycleTest {

    /**
     * Launching and immediately recreating the activity must not crash.
     *
     * When the view is destroyed, [SpotifyFragment.onDestroyView] calls albumArtImage.dispose()
     * (cancelling the in-flight Coil request) and increments artworkRequestId (invalidating any
     * Palette callback that fires late). The Palette callback compares its captured request id
     * against the current id and returns early on a mismatch, preventing stale view access.
     */
    @Test
    fun rapidRecreate_doesNotCrash() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity must be non-null at launch", activity)
            }

            // Recreating tears down the current view (onDestroyView: dispose + id bump) and
            // creates a fresh one. Any Palette callback still in flight for the previous view
            // will find its request id stale and return early.
            scenario.recreate()

            scenario.onActivity { activity ->
                assertNotNull("Activity should be non-null after recreate", activity)
            }
        }
    }

    /**
     * Finishing the activity immediately after launch must not crash.
     *
     * Exercises the lifecycle-state guard: even if a Palette callback fires after finish(),
     * the check `viewLifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED`
     * prevents any view access on the destroyed fragment.
     */
    @Test
    fun finishDuringArtLoad_doesNotCrash() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertNotNull("Activity must be non-null at launch", activity)
                activity.finish()
            }
            // Reaching here without an uncaught exception means the lifecycle guards worked.
        }
    }
}
