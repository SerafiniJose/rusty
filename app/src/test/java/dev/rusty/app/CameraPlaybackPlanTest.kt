package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the live-playback watchdog/retry plan. No Robolectric, no mocking: the plan
 * knows nothing about ExoPlayer, it only turns events into a state and a list of effects.
 */
class CameraPlaybackPlanTest {

    private var nowMs = 1_000L
    private val plan = CameraPlaybackPlan { nowMs }

    private fun at(t: Long): Long {
        nowMs = t
        return t
    }

    /** Drives the plan from fresh to Playing, leaving the clock at [t]. */
    private fun reachPlaying(t: Long) {
        plan.onAttemptStarted(at(t))
        plan.onFirstFrame(at(t))
    }

    @Test
    fun `attempt start cancels stale timers and arms both watchdogs`() {
        val effects = plan.onAttemptStarted(at(10_000))

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.ArmTimer(Watchdog.HANDSHAKE, 18_000L),
                PlaybackEffect.ArmTimer(Watchdog.FIRST_FRAME, 22_000L),
            ),
            effects,
        )
        assertEquals(LiveState.Connecting, plan.state)
    }

    @Test
    fun `first frame cancels watchdogs and plays`() {
        plan.onAttemptStarted(at(10_000))

        val effects = plan.onFirstFrame(at(12_500))

        assertEquals(listOf(PlaybackEffect.CancelTimers), effects)
        assertEquals(LiveState.Playing, plan.state)
    }

    @Test
    fun `transient error ladder is 2s 4s 8s 15s 15s`() {
        val expectedDelays = listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L)
        var t = 0L
        expectedDelays.forEachIndexed { index, delay ->
            t += 100_000L
            plan.onAttemptStarted(at(t))
            val effects = plan.onError(2000, "connection reset", at(t))

            assertEquals(
                "attempt $index",
                listOf(
                    PlaybackEffect.CancelTimers,
                    PlaybackEffect.TearDownPlayer,
                    PlaybackEffect.ScheduleRetry(t + delay),
                ),
                effects,
            )
            assertEquals(LiveState.Reconnecting(index + 1, t + delay), plan.state)
        }
    }

    @Test
    fun `thirty seconds of playing before the next error resets the ladder to 2000`() {
        // Burn two attempts so the next delay would otherwise be 8s.
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "reset", at(2_000))
        plan.onAttemptStarted(at(5_000))
        plan.onError(2000, "reset", at(6_000))
        assertEquals(LiveState.Reconnecting(2, 10_000L), plan.state)

        plan.onAttemptStarted(at(10_000))
        plan.onFirstFrame(at(11_000))
        val effects = plan.onError(2000, "reset", at(41_000)) // 30s of healthy playback

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.TearDownPlayer,
                PlaybackEffect.ScheduleRetry(43_000L),
            ),
            effects,
        )
        assertEquals(LiveState.Reconnecting(1, 43_000L), plan.state)
    }

    @Test
    fun `short playing spell does not reset the ladder`() {
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "reset", at(2_000))

        plan.onAttemptStarted(at(5_000))
        plan.onFirstFrame(at(6_000))
        val effects = plan.onError(2000, "reset", at(20_000)) // only 14s healthy

        assertTrue(effects.contains(PlaybackEffect.ScheduleRetry(24_000L))) // 4s, not 2s
        assertEquals(LiveState.Reconnecting(2, 24_000L), plan.state)
    }

    @Test
    fun `fatal auth tears down and never schedules a retry`() {
        plan.onAttemptStarted(at(1_000))

        val effects = plan.onError(2000, "RTSP/1.0 401 Unauthorized", at(2_000))

        assertEquals(
            listOf(PlaybackEffect.CancelTimers, PlaybackEffect.TearDownPlayer),
            effects,
        )
        assertEquals(LiveState.Fatal(StreamErrorKind.FATAL_AUTH), plan.state)
        assertTrue(effects.none { it is PlaybackEffect.ScheduleRetry })
    }

    @Test
    fun `manual retry from fatal rebuilds and re-arms the watchdogs`() {
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "RTSP/1.0 404 Not Found", at(2_000))
        assertEquals(LiveState.Fatal(StreamErrorKind.FATAL_NOT_FOUND), plan.state)

        val effects = plan.onManualRetry(at(9_000))

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.BuildPlayer,
                PlaybackEffect.ArmTimer(Watchdog.HANDSHAKE, 17_000L),
                PlaybackEffect.ArmTimer(Watchdog.FIRST_FRAME, 21_000L),
            ),
            effects,
        )
        assertEquals(LiveState.Connecting, plan.state)
    }

    @Test
    fun `manual retry from reconnecting resets the attempt counter`() {
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "reset", at(2_000))
        plan.onAttemptStarted(at(5_000))
        plan.onError(2000, "reset", at(6_000))
        assertEquals(LiveState.Reconnecting(2, 10_000L), plan.state)

        plan.onManualRetry(at(7_000))
        assertEquals(LiveState.Connecting, plan.state)

        // Counter reset: the next transient failure waits 2s again, not 8s.
        val effects = plan.onError(2000, "reset", at(8_000))
        assertTrue(effects.contains(PlaybackEffect.ScheduleRetry(10_000L)))
        assertEquals(LiveState.Reconnecting(1, 10_000L), plan.state)
    }

    @Test
    fun `manual retry from playing is ignored`() {
        reachPlaying(1_000)

        assertEquals(emptyList<PlaybackEffect>(), plan.onManualRetry(at(2_000)))
        assertEquals(LiveState.Playing, plan.state)
    }

    @Test
    fun `handshake watchdog tears down and schedules like a transient`() {
        plan.onAttemptStarted(at(1_000))

        val effects = plan.onWatchdogFired(Watchdog.HANDSHAKE, at(9_000))

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.TearDownPlayer,
                PlaybackEffect.ScheduleRetry(11_000L),
            ),
            effects,
        )
        assertEquals(LiveState.Reconnecting(1, 11_000L), plan.state)
    }

    @Test
    fun `first frame watchdog tears down and schedules like a transient`() {
        plan.onAttemptStarted(at(1_000))

        val effects = plan.onWatchdogFired(Watchdog.FIRST_FRAME, at(13_000))

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.TearDownPlayer,
                PlaybackEffect.ScheduleRetry(15_000L),
            ),
            effects,
        )
        assertEquals(LiveState.Reconnecting(1, 15_000L), plan.state)
    }

    @Test
    fun `a stale watchdog firing after playback started is ignored`() {
        reachPlaying(1_000)

        assertEquals(emptyList<PlaybackEffect>(), plan.onWatchdogFired(Watchdog.HANDSHAKE, at(9_000)))
        assertEquals(LiveState.Playing, plan.state)
    }

    @Test
    fun `stream ended mid-stream reconnects like a transient error`() {
        // Device evidence: a camera that vanishes surfaces STATE_ENDED, not an error.
        reachPlaying(1_000)

        val effects = plan.onStreamEnded(at(4_000))

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.TearDownPlayer,
                PlaybackEffect.ScheduleRetry(6_000L),
            ),
            effects,
        )
        assertEquals(LiveState.Reconnecting(1, 6_000L), plan.state)
    }

    @Test
    fun `a second error while already reconnecting is ignored`() {
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "reset", at(2_000))
        val stateAfterFirst = plan.state

        assertEquals(emptyList<PlaybackEffect>(), plan.onError(2000, "reset again", at(2_500)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onStreamEnded(at(2_600)))
        assertEquals(stateAfterFirst, plan.state)
    }

    @Test
    fun `an error after a fatal failure is ignored`() {
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "RTSP/1.0 401 Unauthorized", at(2_000))

        assertEquals(emptyList<PlaybackEffect>(), plan.onError(2000, "reset", at(3_000)))
        assertEquals(LiveState.Fatal(StreamErrorKind.FATAL_AUTH), plan.state)
    }

    @Test
    fun `release cancels everything and freezes the plan`() {
        plan.onAttemptStarted(at(1_000))
        plan.onFirstFrame(at(2_000))

        val effects = plan.onReleased()
        assertEquals(
            listOf(PlaybackEffect.CancelTimers, PlaybackEffect.TearDownPlayer),
            effects,
        )
        assertEquals(LiveState.Playing, plan.state)

        // Every entry point is a no-op afterwards, state never moves again.
        assertEquals(emptyList<PlaybackEffect>(), plan.onAttemptStarted(at(3_000)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onFirstFrame(at(3_100)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onError(2000, "reset", at(3_200)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onStreamEnded(at(3_300)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onWatchdogFired(Watchdog.FIRST_FRAME, at(3_400)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onManualRetry(at(3_500)))
        assertEquals(emptyList<PlaybackEffect>(), plan.onReleased())
        assertEquals(LiveState.Playing, plan.state)
    }

    @Test
    fun `the injected clock supplies now when the caller omits it`() {
        at(50_000)
        val effects = plan.onAttemptStarted()

        assertEquals(
            listOf(
                PlaybackEffect.CancelTimers,
                PlaybackEffect.ArmTimer(Watchdog.HANDSHAKE, 58_000L),
                PlaybackEffect.ArmTimer(Watchdog.FIRST_FRAME, 62_000L),
            ),
            effects,
        )
    }

    @Test
    fun `state READY counts as connected even when no frame is ever rendered`() {
        // With no surface attached onRenderedFirstFrame never fires; READY must still cancel the
        // watchdogs, or a healthy stream is torn down every 12s forever.
        plan.onAttemptStarted(at(10_000))

        val effects = plan.onReady(at(11_000))

        assertEquals(listOf(PlaybackEffect.CancelTimers), effects)
        assertEquals(LiveState.Playing, plan.state)
    }

    @Test
    fun `a first frame after READY is a no-op`() {
        plan.onAttemptStarted(at(10_000))
        plan.onReady(at(11_000))

        assertEquals(emptyList<PlaybackEffect>(), plan.onFirstFrame(at(11_500)))
        assertEquals(LiveState.Playing, plan.state)
    }

    @Test
    fun `READY starts the healthy spell that resets the ladder`() {
        plan.onAttemptStarted(at(1_000))
        plan.onError(2000, "reset", at(2_000))
        plan.onAttemptStarted(at(5_000))
        plan.onReady(at(6_000))

        val effects = plan.onError(2000, "reset", at(36_000)) // 30s healthy after READY

        assertTrue(effects.contains(PlaybackEffect.ScheduleRetry(38_000L)))
        assertEquals(LiveState.Reconnecting(1, 38_000L), plan.state)
    }

    @Test
    fun `READY after release is a no-op`() {
        plan.onAttemptStarted(at(1_000))
        plan.onReleased()

        assertEquals(emptyList<PlaybackEffect>(), plan.onReady(at(2_000)))
        assertEquals(LiveState.Connecting, plan.state)
    }

    @Test
    fun `embedded credentials are stripped before classification`() {
        val raw = "Source error | rtsp://admin:p401ss@10.0.0.9:554/stream1 failed: read timed out"

        val cleaned = PlaybackErrorText.stripUserinfo(raw)

        assertEquals(
            "Source error | rtsp://•••@10.0.0.9:554/stream1 failed: read timed out",
            cleaned,
        )
        // The password's "401" must not read as an RTSP auth failure.
        assertEquals(StreamErrorKind.TRANSIENT, CameraRetryPolicy.classify(2000, cleaned))
        assertEquals(StreamErrorKind.FATAL_AUTH, CameraRetryPolicy.classify(2000, raw))
    }

    @Test
    fun `stripping leaves a credential-free message alone and keeps real status codes`() {
        val raw = "rtsp://10.0.0.9:554/stream1: RTSP/1.0 401 Unauthorized"

        val cleaned = PlaybackErrorText.stripUserinfo(raw)

        assertEquals(raw, cleaned)
        assertEquals(StreamErrorKind.FATAL_AUTH, CameraRetryPolicy.classify(2000, cleaned))
    }
}
