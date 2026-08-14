package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Services & status card must leave nothing behind: no showing window, and — the part that
 * actually matters — no registered runtime listener.
 *
 * This replaces the old SpotifyDialogLeakTest. When the Info sheet belonged to [SpotifyFragment] it
 * piggybacked on that fragment's store listener and the fragment's `onDestroyView` closed it. The
 * shell-owned card registers its OWN listeners on five publishers and removes them from its single
 * dismiss callback, so the leak that matters is now a listener leak, not just a window leak — and
 * [ReceiverStateStore.listenerCount] makes it exactly assertable.
 *
 * Device-pending: compiles (verified via assembleDebugAndroidTest) but NOT executed on a physical
 * device or emulator. Run with `connectedDebugAndroidTest` when a device is available.
 */
@RunWith(AndroidJUnit4::class)
class InfoSheetLeakTest {

    /**
     * Open the card, then destroy the Activity. [HomeActivity.onDestroy] dismisses tracked shell
     * dialogs, which is what runs the card's cleanup — so the store's listener count must come back
     * to exactly where it started, with no dialog left tracked.
     */
    @Test
    fun openInfoSheet_thenRecreate_noTrackedDialog_andNoLeakedStoreListener() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var baseline = -1
            scenario.onActivity { activity ->
                baseline = RustyApp.from(activity).listenerCount()
                activity.openInfo()
                assertEquals(
                    "the card must be tracked by the shell while it is showing",
                    1,
                    activity.shellDialogCount(),
                )
                assertEquals(
                    "the card registers exactly one store listener of its own",
                    baseline + 1,
                    RustyApp.from(activity).listenerCount(),
                )
            }

            // Destroys the old Activity (running onDestroy → dismissShellDialogs) and starts a new one.
            scenario.recreate()

            scenario.onActivity { activity ->
                assertEquals(
                    "no shell dialog may survive Activity destruction",
                    0,
                    activity.shellDialogCount(),
                )
                assertEquals(
                    "dismissing the card must remove the store listener it registered",
                    baseline,
                    RustyApp.from(activity).listenerCount(),
                )
            }
        }
    }

    /** Baseline: an Activity that never opened the card tracks no dialog. */
    @Test
    fun noSheet_thenRecreate_noTrackedDialog() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(
                    "nothing should be tracked when no card was opened",
                    0,
                    activity.shellDialogCount(),
                )
            }
        }
    }
}
