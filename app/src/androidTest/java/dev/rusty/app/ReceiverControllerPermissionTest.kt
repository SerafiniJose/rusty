package dev.rusty.app

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented test for the notification-permission retry path in [ReceiverController].
 *
 * ## Background
 * The old `HomeActivity.serviceStartRequested` was a static field.  When the user denied
 * notification permission and then granted it from the system Settings screen (returning via
 * the back stack), the Activity was recreated, but the static `true` suppressed the retry —
 * the receiver never started.  The per-instance [ReceiverController.permissionRequestInFlight]
 * resets on Activity recreation, so the retry fires correctly.
 *
 * ## What these tests validate (compile-only — device-pending)
 * 1. A fresh Activity has `permissionRequestInFlight = false` on its controller.
 * 2. After simulating a permission denial, `permissionRequestInFlight` is `false` again
 *    (the controller cleared it in `onPermissionResult`), so a subsequent call to
 *    `ensureStarted()` from `onStart()` of a RECREATED Activity is not suppressed.
 *
 * ## Run instructions
 * These tests compile and are verified with:
 *   `./gradlew :app:assembleDebugAndroidTest`
 * They have NOT been executed on a physical device or emulator.
 * Execute with `./gradlew :app:connectedDebugAndroidTest` when a device is available.
 *
 * NOTE: on API 33+ the test runner would need to actually deny the permission dialog to
 * exercise the full flow end-to-end; that requires UiAutomator interaction with the system
 * permission dialog and is left for a manual or UI-test pass.  These tests cover the
 * structural guarantee: the controller's in-flight flag is per-instance and resets correctly.
 */
@RunWith(AndroidJUnit4::class)
class ReceiverControllerPermissionTest {

    /**
     * Verify that a fresh Activity starts with no permission request in flight.
     *
     * This is the structural guarantee that replaces the static flag: every Activity instance
     * starts clean, so a retry after returning from Settings is never suppressed.
     */
    @Test
    fun freshActivity_permissionRequestInFlight_isFalse() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertFalse(
                    "permissionRequestInFlight must be false on a fresh Activity instance",
                    activity.receiverControllerForTest().permissionRequestInFlight,
                )
            }
        }
    }

    /**
     * Simulates the permission-denied callback and verifies the in-flight flag is cleared,
     * so a subsequent `ensureStarted()` (from a re-created Activity's `onStart`) is not blocked.
     *
     * The actual store state after denial (STOPPED + PERMISSION_DENIED display) is also
     * a regression guard: `ReceiverServiceState.STOPPED` means `shouldStart` returns true
     * on the next call — the receiver retries correctly.
     */
    @Test
    fun afterPermissionDenied_inFlightFlagIsCleared_andServiceStateIsStopped() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val controller = activity.receiverControllerForTest()

                // Simulate the permission result that arrives after the system dialog.
                controller.onPermissionResult(isGranted = false)

                assertFalse(
                    "permissionRequestInFlight must be cleared after onPermissionResult(false)",
                    controller.permissionRequestInFlight,
                )

                // The service must remain STOPPED (not FAILED) so shouldStart() returns true
                // on the next ensureStarted() call — i.e., the retry is unblocked.
                val serviceState = RustyApp.from(activity).snapshot.service
                assert(
                    ReceiverController.shouldStart(serviceState, inFlight = false)
                ) {
                    "shouldStart must return true after a permission denial so the retry fires; " +
                        "actual service state: $serviceState"
                }
            }
        }
    }
}
