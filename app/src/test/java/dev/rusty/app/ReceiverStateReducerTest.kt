package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverStateReducerTest {
    private val base = ReceiverDashboardState.waiting("Spk")

    /**
     * Routing test: a CONNECTED status event followed by an OFF status event must clear
     * the session and produce status == "Off". Verifies the Status arm routes to
     * ReceiverDashboardStatusEvent.toDashboardState and that the OFF mapper output is correct.
     */
    @Test
    fun offStatusAfterConnectedClearsSessionAndProducesOffStatus() {
        val connectedEvent = ReceiverDashboardStatusEvent(
            receiverName = "Spk",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
            sessionUser = "jose.serafinig",
            sessionDisplayName = "Ada",
            sessionAvatarUrl = "https://img"
        )
        val connected = reduceReceiverState(base, ReceiverEvent.Status(connectedEvent))
        assertEquals("Connected", connected.status)
        assertEquals("jose.serafinig", connected.sessionUser)

        val offEvent = ReceiverDashboardStatusEvent(
            receiverName = "Spk",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.OFF
        )
        val off = reduceReceiverState(connected, ReceiverEvent.Status(offEvent))

        assertEquals("Off", off.status)
        assertNull(off.sessionUser)
        assertNull(off.sessionDisplayName)
        assertEquals("Discovery: off", off.discoveryLine)
        assertEquals("Service: stopped", off.serviceLine)
    }

    /**
     * Routing test: a Playback event is routed through ReceiverDashboardPlaybackEvent.toDashboardState,
     * which preserves profile fields (sessionDisplayName) from the previous state while applying track data.
     * If this were incorrectly routed to the status mapper, sessionDisplayName would be wiped.
     */
    @Test
    fun playbackEventRoutedToPlaybackMapperAndPreservesProfile() {
        val connectedEvent = ReceiverDashboardStatusEvent(
            receiverName = "Spk",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
            sessionUser = "jose.serafinig",
            sessionDisplayName = "Ada",
            sessionAvatarUrl = "https://img"
        )
        val withProfile = reduceReceiverState(base, ReceiverEvent.Status(connectedEvent))
        assertEquals("Ada", withProfile.sessionDisplayName)

        // Playback event carries no profile fields — the mapper must preserve them from previous state.
        val playbackEvent = ReceiverDashboardPlaybackEvent(
            receiverName = "Spk",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Song",
            trackArtist = "Artist",
            elapsedMs = 30_000L,
            durationMs = 180_000L
        )
        val playing = reduceReceiverState(withProfile, ReceiverEvent.Playback(playbackEvent))

        assertEquals("Song", playing.trackTitle)          // playback applied
        assertEquals("Ada", playing.sessionDisplayName)   // profile survives a playback event
        assertEquals("Playing", playing.status)
    }

    /**
     * Rename changes only receiverName; all other fields remain identical.
     */
    @Test
    fun renameChangesOnlyReceiverName() {
        val playbackEvent = ReceiverDashboardPlaybackEvent(
            receiverName = "Spk",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Song",
            trackArtist = "Artist"
        )
        val playing = reduceReceiverState(base, ReceiverEvent.Playback(playbackEvent))

        val renamed = reduceReceiverState(playing, ReceiverEvent.Rename("Kitchen"))

        assertEquals("Kitchen", renamed.receiverName)
        // Everything else unchanged — compare via copy
        assertEquals(playing.copy(receiverName = "Kitchen"), renamed)
    }
}
