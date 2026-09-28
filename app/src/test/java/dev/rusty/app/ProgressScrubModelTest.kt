package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressScrubModelTest {

    private var now = 10_000L
    private fun model(durationMs: Long = 200_000L) = ProgressScrubModel { now }.also { it.durationMs = durationMs }

    // ---- mapping ----

    @Test
    fun `ratio and ms map both ways and clamp`() {
        val m = model()
        assertEquals(100_000L, m.ratioToMs(0.5f))
        assertEquals(0L, m.ratioToMs(-0.3f))
        assertEquals(200_000L, m.ratioToMs(1.7f))
        assertEquals(0.25f, m.msToRatio(50_000L), 0.0001f)
        assertEquals(1f, m.msToRatio(999_999L), 0.0001f)
        assertEquals(0f, m.msToRatio(-5L), 0.0001f)
    }

    @Test
    fun `without a duration everything is a no-op`() {
        val m = model(durationMs = 0L)
        assertEquals(0f, m.msToRatio(5_000L), 0.0001f)
        assertEquals(ProgressScrubModel.Effect.None, m.onDragStart(0.5f))
        assertEquals(ProgressScrubModel.Effect.None, m.onDragEnd(0.5f))
        assertEquals(ProgressScrubModel.Effect.None, m.onStep(1, 0, 0L))
        assertEquals(ProgressScrubModel.Effect.None, m.onCommitTimer())
        assertNull(m.previewMs)
        assertTrue(m.acceptSnapshot(1L))
    }

    // ---- touch ----

    @Test
    fun `drag previews the finger and seeks once on release`() {
        val m = model()
        assertEquals(ProgressScrubModel.Effect.None, m.onDragStart(0.10f))
        assertEquals(20_000L, m.previewMs)
        assertTrue(m.isGestureInFlight)
        assertEquals(ProgressScrubModel.Effect.None, m.onDragMove(0.40f))
        assertEquals(80_000L, m.previewMs)
        assertEquals(ProgressScrubModel.Effect.Seek(90_000L), m.onDragEnd(0.45f))
        assertFalse(m.isGestureInFlight)
        // After release the target is HELD (optimistic position) until the receiver confirms.
        assertEquals(90_000L, m.previewMs)
    }

    @Test
    fun `a tap is a drag with no movement`() {
        val m = model()
        m.onDragStart(0.75f)
        assertEquals(ProgressScrubModel.Effect.Seek(150_000L), m.onDragEnd(0.75f))
    }

    @Test
    fun `drag cancel seeks nothing and restores the store position`() {
        val m = model()
        m.onDragStart(0.5f)
        assertEquals(ProgressScrubModel.Effect.None, m.onDragCancel())
        assertNull(m.previewMs)
        assertTrue(m.acceptSnapshot(1L))
    }

    @Test
    fun `move and end without a start are ignored`() {
        val m = model()
        assertEquals(ProgressScrubModel.Effect.None, m.onDragMove(0.5f))
        assertEquals(ProgressScrubModel.Effect.None, m.onDragEnd(0.5f))
        assertNull(m.previewMs)
    }

    @Test
    fun `snapshots are refused while a finger is down`() {
        val m = model()
        m.onDragStart(0.5f)
        assertFalse(m.acceptSnapshot(7L))
        m.onDragMove(0.6f)
        assertFalse(m.acceptSnapshot(8L))
    }

    // ---- D-pad ----

    @Test
    fun `steps accumulate from the live position and re-arm one commit`() {
        val m = model()
        assertEquals(ProgressScrubModel.Effect.ArmCommit(500L), m.onStep(1, 0, 60_000L))
        assertEquals(70_000L, m.previewMs)
        assertEquals(ProgressScrubModel.Effect.ArmCommit(500L), m.onStep(1, 0, 60_500L))
        assertEquals(80_000L, m.previewMs)
        assertEquals(ProgressScrubModel.Effect.ArmCommit(500L), m.onStep(-1, 0, 61_000L))
        assertEquals(70_000L, m.previewMs)
        assertTrue(m.isGestureInFlight)
        assertFalse(m.acceptSnapshot(3L))
    }

    @Test
    fun `commit timer issues one seek for the whole burst and then holds it`() {
        val m = model()
        m.onStep(1, 0, 60_000L)
        m.onStep(1, 0, 60_000L)
        m.onStep(1, 0, 60_000L)
        assertEquals(ProgressScrubModel.Effect.Seek(90_000L), m.onCommitTimer())
        assertFalse(m.isGestureInFlight)
        assertEquals(90_000L, m.previewMs)
        // Nothing pending any more: a stray timer is harmless.
        assertEquals(ProgressScrubModel.Effect.None, m.onCommitTimer())
    }

    @Test
    fun `steps accelerate to thirty seconds after five repeats`() {
        val m = model()
        m.onStep(1, 4, 0L)
        assertEquals(10_000L, m.previewMs)
        m.onStep(1, 5, 0L)
        assertEquals(40_000L, m.previewMs)
        m.onStep(1, 12, 0L)
        assertEquals(70_000L, m.previewMs)
    }

    @Test
    fun `steps clamp at the track ends`() {
        val m = model()
        m.onStep(-1, 0, 4_000L)
        assertEquals(0L, m.previewMs)
        val m2 = model()
        m2.onStep(1, 0, 195_000L)
        assertEquals(200_000L, m2.previewMs)
        assertEquals(ProgressScrubModel.Effect.Seek(200_000L), m2.onCommitTimer())
    }

    @Test
    fun `a step during the hold continues from the held target not the stale live position`() {
        val m = model()
        m.onStep(1, 0, 60_000L)
        m.onCommitTimer()                    // Seek(70_000), held
        m.onStep(1, 0, 60_000L)              // the store still says 60 s: not yet confirmed
        assertEquals(80_000L, m.previewMs)
    }

    @Test
    fun `a finger overrides a half-finished step sequence`() {
        val m = model()
        m.onStep(1, 0, 60_000L)
        m.onDragStart(0.5f)
        assertEquals(100_000L, m.previewMs)
        // The abandoned steps never commit.
        m.onDragEnd(0.5f)
        assertEquals(ProgressScrubModel.Effect.None, m.onCommitTimer())
    }

    @Test
    fun `steps are ignored while a finger is down`() {
        val m = model()
        m.onDragStart(0.5f)
        assertEquals(ProgressScrubModel.Effect.None, m.onStep(1, 0, 0L))
        assertEquals(100_000L, m.previewMs)
    }

    // ---- optimistic hold ----

    @Test
    fun `held position survives ticks until the anchor generation moves`() {
        val m = model()
        assertTrue(m.acceptSnapshot(5L))     // the model learns the current generation
        m.onDragStart(0.5f)
        m.onDragEnd(0.5f)                    // Seek(100_000) at generation 5
        now += 200
        assertFalse(m.acceptSnapshot(5L))    // a 1 Hz tick with the old anchor: keep the hold
        assertEquals(100_000L, m.previewMs)
        now += 200
        assertTrue(m.acceptSnapshot(6L))     // the receiver re-anchored: the store is the truth again
        assertNull(m.previewMs)
    }

    @Test
    fun `held position expires after the timeout`() {
        val m = model()
        m.acceptSnapshot(5L)
        m.onDragStart(0.5f)
        m.onDragEnd(0.5f)
        now += ProgressScrubModel.HOLD_TIMEOUT_MS - 1
        assertFalse(m.acceptSnapshot(5L))
        now += 1
        assertTrue(m.acceptSnapshot(5L))
        assertNull(m.previewMs)
    }

    @Test
    fun `reset drops every gesture and hold`() {
        val m = model()
        m.onStep(1, 0, 0L)
        m.reset()
        assertNull(m.previewMs)
        assertFalse(m.isGestureInFlight)
        assertEquals(ProgressScrubModel.Effect.None, m.onCommitTimer())
    }
}
