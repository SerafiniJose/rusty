package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure lyrics reaction logic extracted from the old broadcast handler.
 * Reproduces every branch the activity used to take inline so the store-driven path is
 * provably behaviour-equivalent.
 */
class LyricsPlaybackReactionTest {

    private fun snapshot(
        status: String,
        trackId: String?,
        playing: Boolean = status == "Playing",
        elapsedMs: Long = 0L,
    ): ReceiverSnapshot = ReceiverSnapshot(
        state = ReceiverDashboardState(
            receiverName = "A",
            status = status,
            trackId = trackId,
            elapsedMs = elapsedMs,
        ),
        revision = 1L,
        anchor = PlaybackAnchor(elapsedMs = elapsedMs, capturedRealtimeMs = 0L, playing = playing),
        service = ReceiverServiceState.RUNNING,
    )

    @Test
    fun `HA-foreground playing update re-anchors and updates play-pause without reload`() {
        // Same track, still playing → re-anchor + mark playing, no reload.
        val decision = LyricsPlaybackReaction.decide(
            snapshot(status = "Playing", trackId = "track-1", playing = true),
            currentTrackId = "track-1",
        )
        assertFalse(decision.close)
        assertTrue(decision.reanchor)
        assertFalse(decision.reloadTrack)
        assertTrue(decision.playing)
    }

    @Test
    fun `pause update re-anchors and clears playing`() {
        val decision = LyricsPlaybackReaction.decide(
            snapshot(status = "Paused", trackId = "track-1", playing = false),
            currentTrackId = "track-1",
        )
        assertFalse(decision.close)
        assertTrue(decision.reanchor)
        assertFalse(decision.reloadTrack)
        assertFalse(decision.playing)
    }

    @Test
    fun `stopped status closes the screen`() {
        val decision = LyricsPlaybackReaction.decide(
            snapshot(status = "Stopped", trackId = "track-1"),
            currentTrackId = "track-1",
        )
        assertTrue(decision.close)
        assertFalse(decision.reanchor)
        assertFalse(decision.reloadTrack)
    }

    @Test
    fun `off status closes the screen`() {
        val decision = LyricsPlaybackReaction.decide(
            snapshot(status = "Off", trackId = "track-1"),
            currentTrackId = "track-1",
        )
        assertTrue(decision.close)
    }

    @Test
    fun `track change triggers reload`() {
        val decision = LyricsPlaybackReaction.decide(
            snapshot(status = "Playing", trackId = "track-2", playing = true),
            currentTrackId = "track-1",
        )
        assertFalse(decision.close)
        assertTrue(decision.reanchor)
        assertTrue(decision.reloadTrack)
        assertTrue(decision.playing)
    }

    @Test
    fun `blank or null track id does not trigger reload`() {
        val nullId = LyricsPlaybackReaction.decide(
            snapshot(status = "Playing", trackId = null, playing = true),
            currentTrackId = "track-1",
        )
        assertFalse(nullId.reloadTrack)
        val blankId = LyricsPlaybackReaction.decide(
            snapshot(status = "Playing", trackId = "  ", playing = true),
            currentTrackId = "track-1",
        )
        assertFalse(blankId.reloadTrack)
    }

    @Test
    fun `off wins during teardown even with a stale track id`() {
        // Snapshot carries the OLD track id (a torn teardown) but is OFF → must still close, never reload.
        val decision = LyricsPlaybackReaction.decide(
            snapshot(status = "Off", trackId = "track-99"),
            currentTrackId = "track-1",
        )
        assertTrue(decision.close)
        assertFalse(decision.reloadTrack)
        assertFalse(decision.reanchor)
    }
}
