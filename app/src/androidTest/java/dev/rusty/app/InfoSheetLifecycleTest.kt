package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented lifecycle checks for the Services & status card and its About sheet.
 *
 * The card's update check is a blocking network call on `Dispatchers.IO` that can run to an 8 s
 * timeout. It is now scoped to the ACTIVITY (the card is shell-owned; there is no view lifecycle to
 * hang it on), so the guards that keep it from touching a dead window are the activity's own
 * `isFinishing`/`isDestroyed` flags plus `dialog.isShowing` — this exercises that path.
 *
 * Device-pending: compiles (verified via assembleDebugAndroidTest) but NOT executed on a physical
 * device or emulator. Run with `connectedDebugAndroidTest` when a device is available.
 */
@RunWith(AndroidJUnit4::class)
class InfoSheetLifecycleTest {

    /** Open the card and immediately destroy the Activity while the update check is in flight. */
    @Test
    fun openInfoSheet_thenRecreate_doesNotCrash() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity -> activity.openInfo() }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull("Activity should be non-null after recreate", activity)
            }
        }
    }

    /** The About sheet stacks on top of the card; destroying with both open must also be clean. */
    @Test
    fun openInfoThenAbout_thenRecreate_doesNotCrash() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.openInfo()
                AboutSheet.show(activity)
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertNotNull("Activity should be non-null after recreate", activity)
            }
        }
    }

    /** Recreating an Activity that never opened a card must also not crash. */
    @Test
    fun recreateWithoutSheet_doesNotCrash() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.recreate()
            scenario.onActivity { activity -> assertNotNull(activity) }
        }
    }
}
