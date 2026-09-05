package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SnapshotSchedulerTest {

    private fun cam(
        id: String,
        position: Int,
        snapshotUrl: String? = "http://host/$id.jpg",
        mainRtspUrl: String? = null,
    ) = CameraRecord(
        id = id,
        name = id.uppercase(),
        rtspUrl = "rtsp://host/$id",
        mainRtspUrl = mainRtspUrl,
        snapshotUrl = snapshotUrl,
        audioEnabled = false,
        forceTcp = true,
        position = position,
    )

    private fun scheduler(interval: Long = 10_000L, deadline: Long = 15_000L) =
        SnapshotScheduler(refreshIntervalMs = interval, jobDeadlineMs = deadline)

    // --- one in flight -----------------------------------------------------

    @Test
    fun `second tick while a job is in flight returns null`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0), cam("b", 1)), frameGrabAllowed = true)

        val first = s.onTick(0L)
        assertEquals("a", first?.cameraId)
        assertNull(s.onTick(1L))
        assertNull(s.onTick(5_000L))

        s.onJobFinished("a", 10L, ok = true)
        assertEquals("b", s.onTick(11L)?.cameraId)
    }

    // --- round robin -------------------------------------------------------

    @Test
    fun `each camera gets a turn before the first repeats even when it is fastest`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0), cam("b", 1), cam("c", 2)), frameGrabAllowed = true)

        // "a" finishes instantly every time; it must not starve b and c.
        assertEquals("a", s.onTick(0L)?.cameraId)
        s.onJobFinished("a", 1L, ok = true)
        assertEquals("b", s.onTick(2L)?.cameraId)
        s.onJobFinished("b", 3L, ok = true)
        assertEquals("c", s.onTick(4L)?.cameraId)
        s.onJobFinished("c", 5L, ok = true)

        // Nothing due until an interval has passed.
        assertNull(s.onTick(6L))

        // With all three overdue, the rotation continues in the same order.
        assertEquals("a", s.onTick(100_000L)?.cameraId)
        s.onJobFinished("a", 100_001L, ok = true)
        assertEquals("b", s.onTick(100_002L)?.cameraId)
        s.onJobFinished("b", 100_003L, ok = true)
        assertEquals("c", s.onTick(100_004L)?.cameraId)
    }

    // --- job kind ----------------------------------------------------------

    @Test
    fun `camera with a snapshot url falls back to a frame grab when one is allowed`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)
        assertEquals(JobKind.HTTP_THEN_FRAME, s.onTick(0L)?.kind)
    }

    @Test
    fun `camera with a snapshot url produces a plain HTTP job when frame grabs are off`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = false)
        assertEquals(JobKind.HTTP, s.onTick(0L)?.kind)
    }

    @Test
    fun `camera without a snapshot url produces a frame grab when allowed`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0, snapshotUrl = null)), frameGrabAllowed = true)
        val job = s.onTick(0L)
        assertEquals("a", job?.cameraId)
        assertEquals(JobKind.FRAME_GRAB, job?.kind)
    }

    @Test
    fun `camera with neither mechanism is never scheduled and reports NONE`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0, snapshotUrl = null), cam("b", 1)), frameGrabAllowed = false)

        assertEquals("b", s.onTick(0L)?.cameraId)
        s.onJobFinished("b", 1L, ok = true)
        assertNull(s.onTick(2L))
        assertEquals(TileState.NONE, s.tileState("a", 100_000L))
    }

    @Test
    fun `unknown camera id reports NONE`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)
        assertEquals(TileState.NONE, s.tileState("nope", 0L))
    }

    // --- deadline ----------------------------------------------------------

    @Test
    fun `job deadline carries now plus the deadline budget`() {
        val s = scheduler(interval = 10_000L, deadline = 15_000L)
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)
        assertEquals(15_000L, s.onTick(0L)?.deadlineAt)
        s.onJobFinished("a", 1L, ok = true)
        assertEquals(25_001L, s.onTick(10_001L)?.deadlineAt)
    }

    @Test
    fun `expired frame grab frees the slot and goes stale then unreachable`() {
        val s = scheduler(interval = 10_000L, deadline = 15_000L)
        s.setCameras(listOf(cam("a", 0, snapshotUrl = null)), frameGrabAllowed = true)

        assertEquals(JobKind.FRAME_GRAB, s.onTick(0L)?.kind)
        assertNull(s.onJobDeadline(14_999L))
        assertEquals("a", s.onJobDeadline(15_000L))
        assertNull(s.onJobDeadline(15_001L))
        assertEquals(TileState.STALE, s.tileState("a", 15_000L))

        // Slot is free: the camera is due again one interval after the expiry.
        assertNull(s.onTick(20_000L))
        assertEquals("a", s.onTick(25_000L)?.cameraId)
        assertEquals("a", s.onJobDeadline(40_000L))
        assertEquals(TileState.UNREACHABLE, s.tileState("a", 40_000L))
    }

    @Test
    fun `two consecutive failed results mark the camera unreachable`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)

        s.onTick(0L)
        s.onJobFinished("a", 1L, ok = false)
        assertEquals(TileState.STALE, s.tileState("a", 1L))

        assertEquals("a", s.onTick(10_001L)?.cameraId)
        s.onJobFinished("a", 10_002L, ok = false)
        assertEquals(TileState.UNREACHABLE, s.tileState("a", 10_002L))

        // A success clears the failure streak.
        assertEquals("a", s.onTick(20_002L)?.cameraId)
        s.onJobFinished("a", 20_003L, ok = true)
        assertEquals(TileState.OK, s.tileState("a", 20_003L))
    }

    // --- coalescing --------------------------------------------------------

    @Test
    fun `several missed intervals collapse into a single job`() {
        val s = scheduler(interval = 10_000L)
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)

        s.onTick(0L)
        s.onJobFinished("a", 0L, ok = true)

        // Three intervals went by unserviced.
        assertEquals("a", s.onTick(35_000L)?.cameraId)
        s.onJobFinished("a", 35_000L, ok = true)

        // The backlog is not replayed: next due is one interval from completion.
        assertNull(s.onTick(35_001L))
        assertNull(s.onTick(44_999L))
        assertEquals("a", s.onTick(45_000L)?.cameraId)
    }

    // --- suspension --------------------------------------------------------

    @Test
    fun `live view suspends scheduling and resume picks up where it left`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0), cam("b", 1)), frameGrabAllowed = true)

        s.onTick(0L)
        s.onJobFinished("a", 1L, ok = true)

        s.setSuspended(liveViewOpen = true, dlnaVideo = false, testRunning = emptySet())
        assertNull(s.onTick(2L))
        assertNull(s.onTick(1_000_000L))

        s.setSuspended(liveViewOpen = false, dlnaVideo = false, testRunning = emptySet())
        assertEquals("b", s.onTick(1_000_001L)?.cameraId)
    }

    @Test
    fun `dlna video suspends scheduling`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)
        s.setSuspended(liveViewOpen = false, dlnaVideo = true, testRunning = emptySet())
        assertNull(s.onTick(0L))
        s.setSuspended(liveViewOpen = false, dlnaVideo = false, testRunning = emptySet())
        assertNotNull(s.onTick(0L))
    }

    @Test
    fun `suspension causes no deadline churn`() {
        val s = scheduler(interval = 10_000L, deadline = 15_000L)
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)
        s.onTick(0L)

        s.setSuspended(liveViewOpen = true, dlnaVideo = false, testRunning = emptySet())
        assertNull(s.onJobDeadline(15_000L))
        assertNull(s.onJobDeadline(999_999L))
        assertEquals(0, s.consecutiveFailures("a"))

        s.setSuspended(liveViewOpen = false, dlnaVideo = false, testRunning = emptySet())
        assertEquals("a", s.onJobDeadline(999_999L))
    }

    @Test
    fun `a camera under test is skipped while the others keep their turns`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0), cam("b", 1)), frameGrabAllowed = true)
        s.setSuspended(liveViewOpen = false, dlnaVideo = false, testRunning = setOf("a"))

        assertEquals("b", s.onTick(0L)?.cameraId)
        s.onJobFinished("b", 1L, ok = true)
        assertNull(s.onTick(2L))

        s.setSuspended(liveViewOpen = false, dlnaVideo = false, testRunning = emptySet())
        assertEquals("a", s.onTick(3L)?.cameraId)
    }

    // --- staleness math ----------------------------------------------------

    @Test
    fun `state is OK up to three intervals after the last success and STALE past it`() {
        val s = scheduler(interval = 10_000L)
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)

        s.onTick(0L)
        s.onJobFinished("a", 0L, ok = true)

        assertEquals(TileState.OK, s.tileState("a", 29_999L))
        assertEquals(TileState.OK, s.tileState("a", 30_000L))
        assertEquals(TileState.STALE, s.tileState("a", 30_001L))
    }

    @Test
    fun `a schedulable camera with no attempt yet reports NONE`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0)), frameGrabAllowed = true)
        assertEquals(TileState.NONE, s.tileState("a", 0L))
    }

    // --- reconciliation ----------------------------------------------------

    @Test
    fun `setCameras keeps bookkeeping for surviving ids and drops removed ones`() {
        val s = scheduler(interval = 10_000L)
        s.setCameras(listOf(cam("a", 0), cam("b", 1)), frameGrabAllowed = true)

        s.onTick(0L)
        s.onJobFinished("a", 0L, ok = true)

        // "b" is dropped, "c" is new.
        s.setCameras(listOf(cam("a", 0), cam("c", 1)), frameGrabAllowed = true)

        assertEquals(TileState.OK, s.tileState("a", 100L))
        assertEquals(TileState.NONE, s.tileState("b", 100L))

        // "a" is not due yet; the new camera is due immediately.
        assertEquals("c", s.onTick(100L)?.cameraId)
    }

    @Test
    fun `removing the in-flight camera frees the slot`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0), cam("b", 1)), frameGrabAllowed = true)
        assertEquals("a", s.onTick(0L)?.cameraId)

        s.setCameras(listOf(cam("b", 1)), frameGrabAllowed = true)
        assertEquals("b", s.onTick(1L)?.cameraId)
    }

    @Test
    fun `a stale finish for a camera that is no longer in flight is ignored`() {
        val s = scheduler()
        s.setCameras(listOf(cam("a", 0), cam("b", 1)), frameGrabAllowed = true)

        assertEquals("a", s.onTick(0L)?.cameraId)
        s.onJobFinished("b", 1L, ok = true)

        // "a" is still in flight, so nothing else may start.
        assertNull(s.onTick(2L))
        assertEquals(TileState.NONE, s.tileState("b", 2L))
    }

    // --- offlineSince --------------------------------------------------------

    @Test
    fun `offlineSince is null before any failure and is lastOk once a run of failures starts`() {
        val s = SnapshotScheduler(refreshIntervalMs = 10_000)
        s.setCameras(listOf(cam("a", position = 0, snapshotUrl = "http://x/a.jpg")), frameGrabAllowed = false)
        assertNull(s.offlineSince("a"))
        s.onTick(0); s.onJobFinished("a", 1_000, ok = true)
        assertNull(s.offlineSince("a"))
        s.onTick(11_000); s.onJobFinished("a", 12_000, ok = false)
        assertEquals(1_000L, s.offlineSince("a"))
        s.onTick(22_000); s.onJobFinished("a", 23_000, ok = false)
        assertEquals(1_000L, s.offlineSince("a"))
        s.onTick(33_000); s.onJobFinished("a", 34_000, ok = true)
        assertNull(s.offlineSince("a"))
    }

    @Test
    fun `offlineSince falls back to the first failure time when there was never a success`() {
        val s = SnapshotScheduler(refreshIntervalMs = 10_000)
        s.setCameras(listOf(cam("a", position = 0, snapshotUrl = "http://x/a.jpg")), frameGrabAllowed = false)
        s.onTick(0); s.onJobFinished("a", 500, ok = false)
        assertEquals(500L, s.offlineSince("a"))
    }

    @Test
    fun `a deadline write-off starts the offline run too`() {
        val s = SnapshotScheduler(refreshIntervalMs = 10_000, jobDeadlineMs = 15_000)
        s.setCameras(listOf(cam("a", position = 0, snapshotUrl = "http://x/a.jpg")), frameGrabAllowed = false)
        s.onTick(0)
        assertEquals("a", s.onJobDeadline(15_000))
        assertEquals(15_000L, s.offlineSince("a"))
    }
}
