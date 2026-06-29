package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverDashboardStatusEventTest {
    @Test
    fun foregroundEventMapsToStartingDashboardState() {
        val event = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.FOREGROUND
        )

        val state = event.toDashboardState(previous = ReceiverDashboardState.waiting("Old Name"))

        assertEquals("Salotto Echo Show", state.receiverName)
        assertEquals("Starting", state.status)
        assertEquals("Discovery: starting", state.discoveryLine)
        assertEquals("Service: foreground active", state.serviceLine)
    }

    @Test
    fun nativeStartingEventMapsToListeningDashboardState() {
        val event = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.NATIVE_STARTING
        )

        val state = event.toDashboardState(previous = ReceiverDashboardState.starting("Salotto Echo Show"))

        assertEquals("Waiting", state.status)
        assertEquals("Discovery: active", state.discoveryLine)
        assertEquals("Service: listening for Spotify", state.serviceLine)
    }

    @Test
    fun connectedEventMapsToConnectedHealthWithoutClearingTrack() {
        val previous = ReceiverDashboardState.waiting("Salotto Echo Show").copy(
            trackTitle = "Waiting for Spotify"
        )
        val event = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED
        )

        val state = event.toDashboardState(previous = previous)

        assertEquals("Connected", state.status)
        assertEquals("Discovery: connected", state.discoveryLine)
        assertEquals("Service: connected to Spotify", state.serviceLine)
        assertEquals("Waiting for Spotify", state.trackTitle)
    }

    @Test
    fun stoppedEventPreservesPlaybackFieldsAndShowsStoppedHealth() {
        val previous = ReceiverDashboardState.playing(
            receiverName = "Salotto Echo Show",
            title = "Track",
            artist = "Artist",
            elapsedMs = 42_000L,
            durationMs = 120_000L
        )
        val event = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.STOPPED
        )

        val state = event.toDashboardState(previous = previous)

        assertEquals("Stopped", state.status)
        assertEquals("Discovery: stopped", state.discoveryLine)
        assertEquals("Service: stopped", state.serviceLine)
        assertEquals("Track", state.trackTitle)
        assertEquals("Artist", state.trackArtist)
    }

    @Test
    fun connectedEventCarriesSessionUserAndStoppedClearsIt() {
        val connected = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
            sessionUser = "jose.serafinig",
            sessionDisplayName = "Jose",
            sessionAvatarUrl = "https://img"
        ).toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("jose.serafinig", connected.sessionUser)
        assertEquals("Session: Jose", connected.sessionLine)

        val stopped = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.STOPPED
        ).toDashboardState(connected)

        assertNull(stopped.sessionUser)
        assertNull(stopped.sessionDisplayName)
        assertNull(stopped.sessionAvatarUrl)
        assertEquals("No active session", stopped.sessionLine)
    }

    @Test
    fun disconnectSequenceClearsSessionAndKeepsReceiverListening() {
        // Reproduces SpotifyService.onNativeReceiverDisconnected(): from a CONNECTED
        // session, the status STOPPED broadcast clears the account, then the playback
        // STOPPED broadcast resets the track and restores the listening health lines.
        // Verifies the info sheet ends on "No active session" while still showing the
        // receiver as discoverable/listening (not "stopped").
        val connected = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
            sessionUser = "jose.serafinig",
            sessionDisplayName = "Jose",
            sessionAvatarUrl = "https://img"
        ).toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))
        assertEquals(1, connected.listeners.size)

        val afterStatus = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.STOPPED
        ).toDashboardState(connected)

        val afterPlayback = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED,
            queueUnavailable = true
        ).toDashboardState(afterStatus)

        // Info sheet session section is empty -> renders "No active session".
        assertEquals(emptyList<ReceiverDashboardState.SessionListener>(), afterPlayback.listeners)
        assertEquals("No active session", afterPlayback.sessionLine)
        // Receiver is still up and discoverable -> NOT "stopped".
        assertEquals("Discovery: active", afterPlayback.discoveryLine)
        assertEquals("Service: listening for Spotify", afterPlayback.serviceLine)
        // Now-playing reset to the idle prompt.
        assertEquals("Waiting for Spotify", afterPlayback.trackTitle)
    }

    @Test
    fun errorEventIncludesMessageInServiceLine() {
        val event = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.ERROR,
            message = "native crash"
        )

        val state = event.toDashboardState(previous = ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("Error", state.status)
        assertEquals("Discovery: error", state.discoveryLine)
        assertEquals("Service: native crash", state.serviceLine)
    }

    @Test
    fun permissionDeniedEventMapsToPermissionNeededStatus() {
        val event = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.PERMISSION_DENIED
        )

        val state = event.toDashboardState(previous = ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("Permission needed", state.status)
        assertEquals("Discovery: stopped", state.discoveryLine)
        assertEquals("Service: notification permission denied", state.serviceLine)
        assertNull(state.sessionUser)
    }

    @Test
    fun playbackStoppedThenOffStatusEndsOnOffNotListening() {
        // Mirrors SpotifyService.onDestroy(): the playback-STOPPED reset is applied first
        // (clearing the track), then the OFF status is applied LAST and must win — a stopped
        // service shows "Off", never the "listening" health that a STOPPED playback maps to.
        val playing = ReceiverDashboardState.playing(
            receiverName = "Salotto Echo Show",
            title = "Track",
            artist = "Artist",
            elapsedMs = 42_000L,
            durationMs = 120_000L
        )
        val afterPlayback = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED,
            queueUnavailable = true
        ).toDashboardState(playing)

        val afterOff = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.OFF
        ).toDashboardState(afterPlayback)

        assertEquals("Off", afterOff.status)
        assertEquals("Discovery: off", afterOff.discoveryLine)
        assertEquals("Service: stopped", afterOff.serviceLine)
        // Track was reset by the playback-STOPPED event and stays cleared under OFF.
        assertEquals("Waiting for Spotify", afterOff.trackTitle)
    }

    @Test
    fun offEventMapsToOffHealthAndClearsSession() {
        val connected = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
            sessionUser = "jose.serafinig",
            sessionDisplayName = "Jose",
            sessionAvatarUrl = "https://img"
        ).toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        val off = ReceiverDashboardStatusEvent(
            receiverName = "Salotto Echo Show",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.OFF
        ).toDashboardState(connected)

        assertEquals("Off", off.status)
        assertEquals("Discovery: off", off.discoveryLine)
        assertEquals("Service: stopped", off.serviceLine)
        assertNull(off.sessionUser)
        assertNull(off.sessionDisplayName)
        assertNull(off.sessionAvatarUrl)
    }
}
