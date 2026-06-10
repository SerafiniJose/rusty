package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceiverDashboardPlaybackEventTest {
    @Test
    fun playingEventMapsMetadataProgressAndQueueFallback() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Phase Three Song",
            trackArtist = "Mario Band",
            elapsedMs = 65_000L,
            durationMs = 185_000L,
            queueUnavailable = true
        )

        val state = event.toDashboardState(ReceiverDashboardState.waiting("Old"))

        assertEquals("Salotto Echo Show", state.receiverName)
        assertEquals("Playing", state.status)
        assertEquals("Phase Three Song", state.trackTitle)
        assertEquals("Mario Band", state.trackArtist)
        assertEquals("1:05", state.elapsedLabel)
        assertEquals("3:05", state.durationLabel)
        assertEquals("Queue unavailable from receiver API", state.queueLine)
    }

    @Test
    fun activePlaybackEventMarksReceiverConnectedInHealthLines() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Connected Song",
            trackArtist = "Connected Artist"
        )

        val state = event.toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("Discovery: connected", state.discoveryLine)
        assertEquals("Service: connected to Spotify", state.serviceLine)
    }

    @Test
    fun pausedEventKeepsMetadataAndMapsStatus() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PAUSED,
            trackTitle = "Paused Song",
            trackArtist = "Paused Artist",
            elapsedMs = 10_000L,
            durationMs = 20_000L
        )

        val state = event.toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("Paused", state.status)
        assertEquals("Paused Song", state.trackTitle)
        assertEquals("Paused Artist", state.trackArtist)
    }

    @Test
    fun stoppedEventUsesWaitingPlaceholders() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED
        )

        val state = event.toDashboardState(
            ReceiverDashboardState.playing("Salotto Echo Show", "Old", "Artist", 1_000L, 2_000L)
        )

        assertEquals("Stopped", state.status)
        assertEquals("Waiting for Spotify", state.trackTitle)
        assertEquals("Select this receiver from Spotify", state.trackArtist)
        assertEquals("0:00", state.elapsedLabel)
        assertEquals("0:00", state.durationLabel)
    }

    @Test
    fun blankMetadataFallsBackDefensively() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = " ",
            trackArtist = "",
            elapsedMs = -1L,
            durationMs = -2L
        )

        val state = event.toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("Unknown track", state.trackTitle)
        assertEquals("Unknown artist", state.trackArtist)
        assertEquals("0:00", state.elapsedLabel)
        assertEquals("0:00", state.durationLabel)
    }

    @Test
    fun queueTitleAndArtistFormatComingNext() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            queueTitle = "Next Song",
            queueArtist = "Next Artist"
        )

        assertEquals("Next: Next Song — Next Artist", event.queueLine)
    }

    @Test
    fun queueTitleWithoutArtistFormatsCompactly() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            queueTitle = "Next Song"
        )

        assertEquals("Next: Next Song", event.queueLine)
    }

    @Test
    fun coverArtUrlPassesThroughAndStoppedClearsIt() {
        val playing = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Song",
            trackArtist = "Artist",
            coverArtUrl = "https://i.scdn.co/image/abc123"
        ).toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("https://i.scdn.co/image/abc123", playing.coverArtUrl)

        val stopped = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED
        ).toDashboardState(playing)

        assertNull(stopped.coverArtUrl)
    }

    @Test
    fun playbackEventCarriesSessionUserAndPreservesItWhenAbsent() {
        val withUser = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Song",
            trackArtist = "Artist",
            sessionUser = "jose.serafinig"
        ).toDashboardState(ReceiverDashboardState.waiting("Salotto Echo Show"))

        assertEquals("jose.serafinig", withUser.sessionUser)
        assertEquals("Session: jose.serafinig", withUser.sessionLine)

        // A later event that omits the user keeps the already-known account.
        val laterWithoutUser = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto Echo Show",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PAUSED,
            trackTitle = "Song",
            trackArtist = "Artist"
        ).toDashboardState(withUser)

        assertEquals("jose.serafinig", laterWithoutUser.sessionUser)
    }

    @Test
    fun unknownPlaybackWireNameReturnsNull() {
        assertNull(ReceiverDashboardPlaybackEvent.PlaybackState.fromWireName("BOGUS"))
    }

    @Test
    fun playbackEventCarriesTrackIdAndIdentity() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "Song",
            trackArtist = "Artist",
            trackId = "4uLU6hMCjMI75M1A2tKUQC",
            sessionDisplayName = "Jose",
            sessionAvatarUrl = "https://img",
            elapsedMs = 1000,
            durationMs = 200000
        )
        val state = event.toDashboardState(ReceiverDashboardState("Salotto"))
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", state.trackId)
        assertEquals("Jose", state.sessionDisplayName)
        assertEquals("https://img", state.sessionAvatarUrl)
    }

    @Test
    fun playbackEventKeepsPreviousIdentityWhenAbsent() {
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = "Salotto",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING
        )
        val previous = ReceiverDashboardState("Salotto", sessionDisplayName = "Jose", sessionAvatarUrl = "https://img")
        val state = event.toDashboardState(previous)
        assertEquals("Jose", state.sessionDisplayName)
        assertEquals("https://img", state.sessionAvatarUrl)
    }
}
