package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverWakeTest {

    // A remote/D-pad user has no focusable control while the full-screen saver covers the chrome,
    // and at idle the saver otherwise never dismisses — so on a non-touch UI any wake must exit.
    @Test fun nonTouchWakeExitsEvenOnReceiver() {
        assertTrue(ScreensaverWake.exitsImmediately(receiverForeground = true, isTouchMode = false))
    }

    // Touch on the receiver idle face keeps the ambient saver up (unchanged behaviour); the caller
    // then falls through to the per-theme / ACTIVE handling.
    @Test fun touchWakeOnReceiverDoesNotExitImmediately() {
        assertFalse(ScreensaverWake.exitsImmediately(receiverForeground = true, isTouchMode = true))
    }

    // Sleep layer over a non-receiver feature (e.g. Home Assistant): any input returns to it.
    @Test fun nonReceiverWakeAlwaysExits() {
        assertTrue(ScreensaverWake.exitsImmediately(receiverForeground = false, isTouchMode = true))
        assertTrue(ScreensaverWake.exitsImmediately(receiverForeground = false, isTouchMode = false))
    }
}
