package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Store-driven ordering tests for [LyricsPlaybackReaction].
 *
 * Each test wires a real [ReceiverStateStore] with a synchronous [MainPoster] (every dispatch
 * drains inline), collects the ORDERED list of snapshots a listener receives, then simulates
 * the lyrics screen by folding through consecutive snapshot pairs: the screen's `currentTrackId`
 * is updated after each [LyricsPlaybackReaction.decide] that would load a track, stopping at the
 * first close decision.
 *
 * This is distinct from [LyricsPlaybackReactionTest], which tests individual snapshot-delta
 * decisions in isolation. Here we verify that the SEQUENCE of decisions over a multi-step
 * store-driven stream is ordered correctly — i.e. reload fires before pause, pause fires before
 * stop, and teardown (OFF) closes even when a stale track still arrives last in sequence.
 */
class LyricsReactionSequenceTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private val synchronousPoster = MainPoster { it.run() }

    private fun store(): ReceiverStateStore = ReceiverStateStore(
        initial = ReceiverDashboardState.waiting("Rusty"),
        poster = synchronousPoster,
        clock = MonotonicClock { 0L },
    )

    private fun playbackEvent(
        trackId: String,
        playing: Boolean,
        title: String = "Song",
    ): ReceiverEvent.Playback = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "Rusty",
            playbackState = if (playing) {
                ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING
            } else {
                ReceiverDashboardPlaybackEvent.PlaybackState.PAUSED
            },
            trackId = trackId,
            trackTitle = title,
            trackArtist = "Artist",
            elapsedMs = 0L,
            durationMs = 240_000L,
        )
    )

    private fun stoppedEvent(): ReceiverEvent.Status = ReceiverEvent.Status(
        ReceiverDashboardStatusEvent(
            receiverName = "Rusty",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.STOPPED,
        )
    )

    private fun offEvent(): ReceiverEvent.Status = ReceiverEvent.Status(
        ReceiverDashboardStatusEvent(
            receiverName = "Rusty",
            lifecycle = ReceiverDashboardStatusEvent.Lifecycle.OFF,
        )
    )

    /**
     * Simulates the lyrics screen reacting to every snapshot in [snapshots] in order.
     * Returns the ordered list of [LyricsPlaybackReaction.Decision] produced, stopping as
     * soon as a close decision is reached (the screen would finish at that point).
     *
     * [initialTrackId] is the track id the lyrics screen was already showing before the
     * first snapshot in the sequence arrives (typically the track that triggered the screen
     * to open).
     */
    private fun fold(
        snapshots: List<ReceiverSnapshot>,
        initialTrackId: String?,
    ): List<LyricsPlaybackReaction.Decision> {
        val decisions = mutableListOf<LyricsPlaybackReaction.Decision>()
        var trackId = initialTrackId
        for (snap in snapshots) {
            val d = LyricsPlaybackReaction.decide(snap, trackId)
            decisions.add(d)
            if (d.close) break
            // Simulate the screen updating its "current track" after a reload.
            if (d.reloadTrack) trackId = snap.state.trackId
        }
        return decisions
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    /**
     * Realistic playback session sequence:
     *
     *   connected → playing trackA → playing trackB (track change) → paused → stopped
     *
     * Expected ordered decisions:
     *   1. playing trackA (screen opens showing A): reanchor+playing, no reload (already on A)
     *   2. playing trackB: reanchor+playing + reloadTrack=true  ← track changed
     *   3. paused:         reanchor, playing=false, no reload
     *   4. stopped:        close=true                            ← stop wins
     */
    @Test
    fun `store-driven sequence playing trackA then trackB then pause then stop produces ordered decisions`() {
        val store = store()
        val received = mutableListOf<ReceiverSnapshot>()
        store.addListener { received.add(it) }

        // Drive the store through a realistic session.
        store.dispatch(playbackEvent(trackId = "track-A", playing = true))
        store.dispatch(playbackEvent(trackId = "track-B", playing = true))
        store.dispatch(playbackEvent(trackId = "track-B", playing = false))
        store.dispatch(stoppedEvent())

        // The listener received the initial snapshot (rev 0) plus one per dispatch.
        // Skip rev-0 (pre-session); the lyrics screen is opened when trackA is playing.
        val sessionSnapshots = received.drop(1) // [trackA-playing, trackB-playing, trackB-paused, stopped]
        assertEquals("expected 4 session snapshots", 4, sessionSnapshots.size)

        // The lyrics screen opens already showing track-A (it was loaded when the screen launched).
        val decisions = fold(sessionSnapshots, initialTrackId = "track-A")

        // ── assert ordered decisions ──
        assertEquals("expected 4 decisions", 4, decisions.size)

        // 1. trackA still playing — re-anchor, no reload, playing
        with(decisions[0]) {
            assertEquals("d[0] close",       false, close)
            assertEquals("d[0] reanchor",    true,  reanchor)
            assertEquals("d[0] reloadTrack", false, reloadTrack) // already on track-A
            assertEquals("d[0] playing",     true,  playing)
        }

        // 2. track changed to B — reanchor + reload + still playing
        with(decisions[1]) {
            assertEquals("d[1] close",       false, close)
            assertEquals("d[1] reanchor",    true,  reanchor)
            assertEquals("d[1] reloadTrack", true,  reloadTrack) // B ≠ A → reload
            assertEquals("d[1] playing",     true,  playing)
        }

        // 3. paused on B — reanchor, playing=false, no reload (same track)
        with(decisions[2]) {
            assertEquals("d[2] close",       false, close)
            assertEquals("d[2] reanchor",    true,  reanchor)
            assertEquals("d[2] reloadTrack", false, reloadTrack)
            assertEquals("d[2] playing",     false, playing)
        }

        // 4. stopped — close
        with(decisions[3]) {
            assertEquals("d[3] close", true, close)
        }
    }

    /**
     * Teardown ordering: OFF arriving after a stale track-change still yields close as the
     * FINAL decision, not reload — even though the snapshot still carries the old track id.
     *
     * Sequence driven through the store:
     *   playing trackA → playing trackB (track change) → OFF  ← teardown overtakes
     *
     * The third decision must be close=true, reloadTrack=false, regardless of stale track id.
     */
    @Test
    fun `store-driven teardown OFF after stale track-change still closes last in order`() {
        val store = store()
        val received = mutableListOf<ReceiverSnapshot>()
        store.addListener { received.add(it) }

        store.dispatch(playbackEvent(trackId = "track-A", playing = true))
        store.dispatch(playbackEvent(trackId = "track-B", playing = true))
        // OFF arrives while the snapshot still carries track-B (stale during teardown)
        store.dispatch(offEvent())

        val sessionSnapshots = received.drop(1) // 3 snapshots: trackA, trackB, OFF
        assertEquals("expected 3 session snapshots", 3, sessionSnapshots.size)

        // Verify the OFF snapshot still carries the old track id (it does — OFF event
        // doesn't clear trackId from reducer state; proves the stale-id scenario).
        // Asserting trackId directly locks the stale-track invariant the close-wins
        // decision below relies on: if a future reducer change cleared trackId on OFF,
        // d[2].reloadTrack would still be false (null-track branch) and this test would
        // pass for the wrong reason — this assertion catches that false-green.
        assertEquals("Off", sessionSnapshots[2].state.status)
        assertEquals("track-B", sessionSnapshots[2].state.trackId)

        val decisions = fold(sessionSnapshots, initialTrackId = "track-A")

        // Should have produced all 3 decisions (fold stops at close = index 2)
        assertEquals("expected 3 decisions", 3, decisions.size)

        // 1. trackA → reanchor, playing, no reload (already on A)
        assertEquals("d[0] close",       false, decisions[0].close)
        assertEquals("d[0] reloadTrack", false, decisions[0].reloadTrack)

        // 2. trackB → reanchor, reload (B ≠ A)
        assertEquals("d[1] close",       false, decisions[1].close)
        assertEquals("d[1] reloadTrack", true,  decisions[1].reloadTrack)

        // 3. OFF → close wins, no reload, even though the state still carries a track id
        assertEquals("d[2] close",       true,  decisions[2].close)
        assertEquals("d[2] reloadTrack", false, decisions[2].reloadTrack)
        assertEquals("d[2] reanchor",    false, decisions[2].reanchor)
    }
}
