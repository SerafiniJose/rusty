package dev.rusty.app

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the pure [SnapshotGuards] plus the [CameraSnapshots] loop's sequencing, which is driven
 * entirely through injected seams (fake [SnapshotIo], hand-cranked clock, test dispatchers) — no
 * Robolectric, no mocking, and no `Bitmap` is ever instantiated: the fake returns null bitmaps and
 * publishing is observed through the JPEG map, which is exactly the shape a device produces when
 * the still-image bytes arrive but this device cannot decode them.
 *
 * `AndroidSnapshotIo` itself (HttpURLConnection, ExoPlayer, PixelCopy) is exercised on-device.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraSnapshotsTest {

    // --- acceptContentLength -------------------------------------------------------------

    @Test
    fun `null content length is accepted (chunked response)`() {
        assertTrue(SnapshotGuards.acceptContentLength(null))
    }

    @Test
    fun `small content length is accepted`() {
        assertTrue(SnapshotGuards.acceptContentLength(64_000L))
    }

    @Test
    fun `content length exactly at the 8MB cap is accepted`() {
        assertEquals(8_388_608L, SnapshotGuards.MAX_BYTES)
        assertTrue(SnapshotGuards.acceptContentLength(8_388_608L))
    }

    @Test
    fun `a 5MB still from a 4K camera is accepted`() {
        assertTrue(SnapshotGuards.acceptContentLength(5_460_000L))
    }

    @Test
    fun `content length one byte over the cap is rejected`() {
        assertFalse(SnapshotGuards.acceptContentLength(8_388_609L))
    }

    @Test
    fun `hugely oversized content length is rejected`() {
        assertFalse(SnapshotGuards.acceptContentLength(50L * 1024 * 1024))
    }

    @Test
    fun `zero length is accepted (the read decides)`() {
        assertTrue(SnapshotGuards.acceptContentLength(0L))
    }

    // --- targetSampleSize ----------------------------------------------------------------

    @Test
    fun `4000x3000 samples down by 4`() {
        assertEquals(4, SnapshotGuards.targetSampleSize(4000, 3000))
    }

    @Test
    fun `1920x1080 samples down by 2`() {
        assertEquals(2, SnapshotGuards.targetSampleSize(1920, 1080))
    }

    @Test
    fun `1280x720 is not sampled`() {
        assertEquals(1, SnapshotGuards.targetSampleSize(1280, 720))
    }

    @Test
    fun `640x480 is not sampled`() {
        assertEquals(1, SnapshotGuards.targetSampleSize(640, 480))
    }

    @Test
    fun `sample size is a power of two`() {
        for (w in intArrayOf(1281, 2000, 2561, 5000, 12000)) {
            val sample = SnapshotGuards.targetSampleSize(w, w / 2)
            assertTrue("$w -> $sample", sample > 0 && (sample and (sample - 1)) == 0)
            assertTrue("$w -> $sample", w / sample <= 1280)
        }
    }

    @Test
    fun `a tall portrait source is capped on its long side`() {
        assertEquals(2, SnapshotGuards.targetSampleSize(1080, 1920))
    }

    @Test
    fun `a custom max width is honoured`() {
        assertEquals(4, SnapshotGuards.targetSampleSize(1280, 720, maxW = 320))
    }

    @Test
    fun `unknown bounds decode without sampling`() {
        assertEquals(1, SnapshotGuards.targetSampleSize(-1, -1))
        assertEquals(1, SnapshotGuards.targetSampleSize(0, 0))
    }

    // --- the loop -------------------------------------------------------------------------

    /**
     * A fetch that only completes when the test says so. The wait is [NonCancellable] on purpose:
     * it models a job whose reply lands *after* the scheduler already wrote the job off, which is
     * the exact case the single-use ticket exists to make harmless.
     */
    private class FakeIo : SnapshotIo {
        val pending = ArrayDeque<CompletableDeferred<ByteArray?>>()
        var calls = 0

        override suspend fun fetchHttp(url: String, user: String?, pass: String?): ByteArray? {
            calls++
            val gate = CompletableDeferred<ByteArray?>()
            pending.addLast(gate)
            return withContext(NonCancellable) { gate.await() }
        }

        override suspend fun grabFrame(rtspUriWithCreds: String, forceTcp: Boolean, deadlineMs: Long): Bitmap? = null

        /** Releases every outstanding fetch so no coroutine is left parked at teardown. */
        fun completeAll() {
            while (pending.isNotEmpty()) pending.removeFirst().complete(null)
        }
    }

    private fun camera(id: String, mainRtspUrl: String? = null) = CameraRecord(
        id = id,
        name = id,
        rtspUrl = "rtsp://cam.local/$id",
        mainRtspUrl = mainRtspUrl,
        snapshotUrl = "http://cam.local/$id.jpg",
        audioEnabled = false,
        forceTcp = true,
        position = 0,
    )

    @Test
    fun `a job written off at its deadline can never report back`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cams = mutableListOf(camera("A"))
        val scheduler = SnapshotScheduler(refreshIntervalMs = 1_000, jobDeadlineMs = 2_000)
        scheduler.setCameras(cams)
        var now = 0L
        val io = FakeIo()
        val snapshots = CameraSnapshots(
            scope = backgroundScope,
            scheduler = scheduler,
            io = io,
            cameras = { cams },
            secrets = { null to null },
            clock = { now },
            tickMs = 500L,
            main = dispatcher,
            worker = dispatcher,
        )

        snapshots.start()
        runCurrent()
        assertEquals(1, io.calls)
        assertNotNull(scheduler.inFlightJob())

        // Deadline passes with the fetch still out: the tick writes the job off.
        now = 3_000
        testScheduler.advanceTimeBy(600)
        runCurrent()
        assertNull(scheduler.inFlightJob())
        assertEquals(1, scheduler.consecutiveFailures("A"))

        // The camera comes due again and a second job goes out.
        now = 5_000
        testScheduler.advanceTimeBy(600)
        runCurrent()
        assertEquals(2, io.calls)
        val second = scheduler.inFlightJob()
        assertNotNull(second)

        // Now the written-off first fetch finally answers. It must not clear the second job's slot
        // and must not publish anything.
        io.pending.removeFirst().complete(byteArrayOf(1, 2, 3))
        runCurrent()
        assertEquals(second, scheduler.inFlightJob())
        assertTrue(snapshots.jpegs.isEmpty())
        assertTrue(snapshots.tiles.isEmpty())

        io.completeAll()
        runCurrent()
        snapshots.stop()
    }

    @Test
    fun `stop releases the scheduler slot so a restart schedules at once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cams = mutableListOf(camera("A"))
        val scheduler = SnapshotScheduler(refreshIntervalMs = 1_000, jobDeadlineMs = 2_000)
        scheduler.setCameras(cams)
        var now = 0L
        val io = FakeIo()
        val snapshots = CameraSnapshots(
            scope = backgroundScope,
            scheduler = scheduler,
            io = io,
            cameras = { cams },
            secrets = { null to null },
            clock = { now },
            tickMs = 500L,
            main = dispatcher,
            worker = dispatcher,
        )

        snapshots.start()
        runCurrent()
        assertNotNull(scheduler.inFlightJob())

        now = 100L
        snapshots.stop()
        assertNull(scheduler.inFlightJob())

        // Well inside the abandoned job's 2 s deadline: a slot left held would stall everything.
        now = 1_500L
        snapshots.start()
        runCurrent()
        assertEquals(2, io.calls)
        assertNotNull(scheduler.inFlightJob())

        io.completeAll()
        runCurrent()
        snapshots.stop()
    }

    @Test
    fun `a deleted camera's last frame is pruned`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cams = mutableListOf(camera("A"))
        val scheduler = SnapshotScheduler(refreshIntervalMs = 1_000, jobDeadlineMs = 2_000)
        scheduler.setCameras(cams)
        var now = 0L
        val io = FakeIo()
        val snapshots = CameraSnapshots(
            scope = backgroundScope,
            scheduler = scheduler,
            io = io,
            cameras = { cams },
            secrets = { null to null },
            clock = { now },
            tickMs = 500L,
            main = dispatcher,
            worker = dispatcher,
        )

        snapshots.start()
        runCurrent()
        val bytes = byteArrayOf(9, 8, 7)
        io.pending.removeFirst().complete(bytes)
        runCurrent()
        // The bytes could not be decoded into a tile on the JVM, so they are published as the
        // camera's own JPEG — which is what the API proxy serves.
        assertEquals(bytes, snapshots.jpegs["A"])
        assertNull(scheduler.inFlightJob())

        cams.clear()
        scheduler.setCameras(emptyList())
        now = 2_000L
        testScheduler.advanceTimeBy(600)
        runCurrent()
        assertTrue(snapshots.jpegs.isEmpty())
        assertTrue(snapshots.tiles.isEmpty())

        io.completeAll()
        runCurrent()
        snapshots.stop()
    }

    // --- frame-grab fallback ---------------------------------------------------------------

    /** A fetch scripted by the test, plus a frame grab that only records how it was called. */
    private class ScriptedIo(private val onFetch: suspend () -> ByteArray?) : SnapshotIo {
        var fetchCalls = 0
        var grabCalls = 0
        var grabUri: String? = null
        var grabForceTcp: Boolean? = null
        var grabDeadlineMs: Long = -1L

        override suspend fun fetchHttp(url: String, user: String?, pass: String?): ByteArray? {
            fetchCalls++
            return onFetch()
        }

        override suspend fun grabFrame(rtspUriWithCreds: String, forceTcp: Boolean, deadlineMs: Long): Bitmap? {
            grabCalls++
            grabUri = rtspUriWithCreds
            grabForceTcp = forceTcp
            grabDeadlineMs = deadlineMs
            // No Bitmap can exist on the JVM, so a *successful* grab cannot be modelled here; what
            // these tests pin down is that the fallback runs at all, and with the right budget.
            return null
        }
    }

    private fun TestScope.snapshotsWith(
        io: SnapshotIo,
        scheduler: SnapshotScheduler,
        cams: List<CameraRecord>,
        dispatcher: TestDispatcher,
        clock: () -> Long,
    ) = CameraSnapshots(
        scope = backgroundScope,
        scheduler = scheduler,
        io = io,
        cameras = { cams },
        secrets = { null to null },
        clock = clock,
        tickMs = 500L,
        main = dispatcher,
        worker = dispatcher,
    )

    @Test
    fun `a failed HTTP snapshot falls back to a frame grab with the remaining budget`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cams = listOf(camera("A"))
        val scheduler = SnapshotScheduler(refreshIntervalMs = 1_000, jobDeadlineMs = 2_000)
        scheduler.setCameras(cams)
        var now = 0L
        // The fetch fails after burning half the job's budget.
        val io = ScriptedIo {
            now = 500L
            null
        }
        val snapshots = snapshotsWith(io, scheduler, cams, dispatcher) { now }

        snapshots.start()
        runCurrent()

        assertEquals(1, io.fetchCalls)
        assertEquals(1, io.grabCalls)
        assertEquals("rtsp://cam.local/A", io.grabUri)
        assertEquals(true, io.grabForceTcp)
        // deadlineAt (2_000) minus the clock as it stood after the failed fetch (500).
        assertEquals(1_500L, io.grabDeadlineMs)
        // The grab could not produce a bitmap either, so the job is simply a failure.
        assertEquals(1, scheduler.consecutiveFailures("A"))

        snapshots.stop()
    }

    @Test
    fun `a successful HTTP snapshot never reaches the frame grab`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cams = listOf(camera("A"))
        val scheduler = SnapshotScheduler(refreshIntervalMs = 1_000, jobDeadlineMs = 2_000)
        scheduler.setCameras(cams)
        val now = 0L
        val bytes = byteArrayOf(1, 2, 3)
        val io = ScriptedIo { bytes }
        val snapshots = snapshotsWith(io, scheduler, cams, dispatcher) { now }

        snapshots.start()
        runCurrent()

        assertEquals(1, io.fetchCalls)
        assertEquals(0, io.grabCalls)
        assertEquals(bytes, snapshots.jpegs["A"])
        assertEquals(0, scheduler.consecutiveFailures("A"))

        snapshots.stop()
    }

    @Test
    fun `a throwing HTTP snapshot still falls back to a frame grab`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val cams = listOf(camera("A"))
        val scheduler = SnapshotScheduler(refreshIntervalMs = 1_000, jobDeadlineMs = 2_000)
        scheduler.setCameras(cams)
        val now = 0L
        val io = ScriptedIo { throw IllegalStateException("boom") }
        val snapshots = snapshotsWith(io, scheduler, cams, dispatcher) { now }

        snapshots.start()
        runCurrent()

        assertEquals(1, io.fetchCalls)
        assertEquals(1, io.grabCalls)
        assertEquals(1, scheduler.consecutiveFailures("A"))

        snapshots.stop()
    }

}
