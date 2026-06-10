package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockFaceStateTest {
    @Test fun startsOnNowPlayingFace() {
        assertFalse(ClockFaceState().showingClock)
    }

    @Test fun manualPeekTogglesClockOnAndOff() {
        val face = ClockFaceState()
        face.onTap()
        assertTrue(face.showingClock)    // tap -> clock
        face.onTap()
        assertFalse(face.showingClock)   // tap -> back to now playing
    }

    @Test fun manualPeekTapDoesNotRequestRearm() {
        val face = ClockFaceState()
        assertFalse(face.onTap())   // peeking the clock isn't a drift wake-up
    }

    @Test fun pauseTimeoutRaisesClock() {
        val face = ClockFaceState()
        face.onPauseTimeout()
        assertTrue(face.showingClock)
    }

    // The reported bug: after the 2-minute pause drift, a single tap on the clock must
    // return the user to the now-playing face. Previously the tap only toggled the manual
    // peek while the drift flag held the clock up forever.
    @Test fun tapAfterPauseTimeoutReturnsToNowPlaying() {
        val face = ClockFaceState()
        face.onPauseTimeout()
        face.onTap()
        assertFalse(face.showingClock)
    }

    @Test fun tapAfterPauseTimeoutRequestsRearm() {
        val face = ClockFaceState()
        face.onPauseTimeout()
        assertTrue(face.onTap())   // caller restarts the drift countdown
    }

    // A peek raised manually then overtaken by the drift still clears in one tap.
    @Test fun tapClearsBothManualPeekAndDrift() {
        val face = ClockFaceState()
        face.onTap()            // manual peek on
        face.onPauseTimeout()   // drift also on
        assertTrue(face.onTap())
        assertFalse(face.showingClock)
    }

    @Test fun clearPausedDriftLeavesManualPeekIntact() {
        val face = ClockFaceState()
        face.onTap()                // manual peek on
        face.onPauseTimeout()       // drift on
        face.clearPausedDrift()     // playback left "Paused"
        assertTrue(face.showingClock)   // manual peek still holds the clock
    }

    @Test fun resetClearsEverything() {
        val face = ClockFaceState()
        face.onTap()
        face.onPauseTimeout()
        face.reset()
        assertFalse(face.showingClock)
    }
}
