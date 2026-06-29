package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for [ReceiverController.shouldStart].
 *
 * No Android context required: the decision function is Android-free by design.
 */
class ReceiverControllerTest {

    // ---- shouldStart gating --------------------------------------------------

    @Test
    fun shouldStart_unknownState_noInFlight_returnsTrue() {
        assertTrue(ReceiverController.shouldStart(ReceiverServiceState.UNKNOWN, inFlight = false))
    }

    @Test
    fun shouldStart_stoppedState_noInFlight_returnsTrue() {
        assertTrue(ReceiverController.shouldStart(ReceiverServiceState.STOPPED, inFlight = false))
    }

    @Test
    fun shouldStart_failedState_noInFlight_returnsTrue() {
        assertTrue(ReceiverController.shouldStart(ReceiverServiceState.FAILED, inFlight = false))
    }

    @Test
    fun shouldStart_startingState_noInFlight_returnsFalse() {
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.STARTING, inFlight = false))
    }

    @Test
    fun shouldStart_runningState_noInFlight_returnsFalse() {
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.RUNNING, inFlight = false))
    }

    @Test
    fun shouldStart_stoppedState_inFlight_returnsFalse() {
        // Permission dialog already in flight: do not launch a second one even if STOPPED.
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.STOPPED, inFlight = true))
    }

    @Test
    fun shouldStart_unknownState_inFlight_returnsFalse() {
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.UNKNOWN, inFlight = true))
    }

    @Test
    fun shouldStart_failedState_inFlight_returnsFalse() {
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.FAILED, inFlight = true))
    }

    @Test
    fun shouldStart_startingState_inFlight_returnsFalse() {
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.STARTING, inFlight = true))
    }

    @Test
    fun shouldStart_runningState_inFlight_returnsFalse() {
        assertFalse(ReceiverController.shouldStart(ReceiverServiceState.RUNNING, inFlight = true))
    }
}
