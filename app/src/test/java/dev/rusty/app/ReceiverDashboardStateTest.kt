package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverDashboardStateTest {
    @Test
    fun defaultStateShowsReceiverWaitingForSpotify() {
        val state = ReceiverDashboardState(receiverName = "Salotto Echo Show")

        assertEquals("Salotto Echo Show", state.receiverName)
        assertEquals("Waiting", state.status)
        assertEquals("Discovery: active", state.discoveryLine)
        assertEquals("Service: listening for Spotify", state.serviceLine)
        assertEquals("Audio: AAudio • Cache: app cache", state.audioLine)
        assertEquals("Waiting for Spotify", state.trackTitle)
        assertEquals("Select this receiver from Spotify", state.trackArtist)
        assertEquals("Queue unavailable from receiver API", state.queueLine)
        assertEquals("0:00", state.elapsedLabel)
        assertEquals("0:00", state.durationLabel)
    }

    @Test
    fun statusFactoryFormatsStartingAndRestartingStates() {
        val starting = ReceiverDashboardState.starting("Kitchen")
        val restarting = ReceiverDashboardState.restarting("Kitchen")

        assertEquals("Starting", starting.status)
        assertEquals("Discovery: starting", starting.discoveryLine)
        assertEquals("Service: starting", starting.serviceLine)

        assertEquals("Restarting", restarting.status)
        assertEquals("Discovery: re-advertising", restarting.discoveryLine)
        assertEquals("Service: restarting", restarting.serviceLine)
    }

    @Test
    fun trackFactoryFormatsProgressLabels() {
        val state = ReceiverDashboardState.playing(
            receiverName = "Studio",
            title = "A Long Track",
            artist = "An Artist",
            elapsedMs = 65_000L,
            durationMs = 185_000L,
            queueLine = "Next: Another Song — Another Artist"
        )

        assertEquals("Playing", state.status)
        assertEquals("A Long Track", state.trackTitle)
        assertEquals("An Artist", state.trackArtist)
        assertEquals("1:05", state.elapsedLabel)
        assertEquals("3:05", state.durationLabel)
        assertEquals("Next: Another Song — Another Artist", state.queueLine)
    }

    @Test
    fun defaultStateUsesTruthfulQueueUnavailableFallback() {
        val state = ReceiverDashboardState.waiting("Salotto Echo Show")

        assertEquals("Queue unavailable from receiver API", ReceiverDashboardState.DEFAULT_QUEUE_LINE)
        assertEquals("Queue unavailable from receiver API", state.queueLine)
    }

    @Test
    fun sessionLineShowsAccountWhenPresentAndIdleOtherwise() {
        val idle = ReceiverDashboardState(receiverName = "Salotto Echo Show")
        assertEquals("No active session", idle.sessionLine)

        val connected = idle.copy(sessionUser = "jose.serafinig")
        assertEquals("Session: jose.serafinig", connected.sessionLine)

        val blank = idle.copy(sessionUser = "   ")
        assertEquals("No active session", blank.sessionLine)
    }

    @Test
    fun playingStateCanAdvanceElapsedTimeWithoutExceedingDuration() {
        val state = ReceiverDashboardState.playing(
            receiverName = "Salotto Echo Show",
            title = "Song",
            artist = "Artist",
            elapsedMs = 197_500L,
            durationMs = 198_000L
        )

        val advanced = state.advanceElapsedBy(1_000L)

        assertEquals("3:18", advanced.elapsedLabel)
        assertEquals(198_000L, advanced.elapsedMs)
    }

    @Test
    fun offFactoryProducesStoppedReceiverState() {
        val state = ReceiverDashboardState.off("Living Room")

        assertEquals("Living Room", state.receiverName)
        assertEquals("Off", state.status)
        assertEquals("Discovery: off", state.discoveryLine)
        assertEquals("Service: stopped", state.serviceLine)
        assertNull(state.sessionUser)
    }
}
