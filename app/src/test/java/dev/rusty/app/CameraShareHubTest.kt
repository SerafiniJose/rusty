package dev.rusty.app

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraShareHubTest {
    private val sps = byteArrayOf(0x67, 1); private val pps = byteArrayOf(0x68, 2)

    private val defaultEncoding = CameraShareSettings.Encoding(
        CameraShareSettings.Resolution.MEDIUM, CameraShareSettings.Tier.GOOD, CameraShareSettings.FrameRate.FPS_15,
    )

    private class FakePipeline(
        var failWith: String? = null,
        val configOnStart: Boolean = true,
        val onGrab: () -> Unit = {},
    ) : CapturePipeline {
        var listener: PipelineListener? = null
        var starts = 0; var stops = 0; var keyframes = 0
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            starts++
            failWith?.let { return PipelineStart.Failed(it) }
            this.listener = listener
            if (configOnStart) listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2))
            return PipelineStart.Ok
        }
        override fun stop() { stops++; listener = null }
        override fun requestKeyframe() { keyframes++ }
        override fun grabJpeg(): ByteArray? { onGrab(); return byteArrayOf(0xFF.toByte(), 0xD8.toByte()) }
    }

    /** Hands over parameter sets the way MediaCodec's csd-0/csd-1 buffers do: behind a start code. */
    private class AnnexBPipeline : CapturePipeline {
        var listener: PipelineListener? = null
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            this.listener = listener
            listener.onCodecConfig(byteArrayOf(0, 0, 0, 1, 0x67, 1), byteArrayOf(0, 0, 0, 1, 0x68, 2))
            return PipelineStart.Ok
        }
        override fun stop() {}
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    /** Hands over whatever raw buffers the test gives it, the way MediaCodec's csd-0/csd-1 do. */
    private class RawConfigPipeline(private val cfgSps: ByteArray, private val cfgPps: ByteArray) : CapturePipeline {
        var starts = 0; var stops = 0
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            starts++; listener.onCodecConfig(cfgSps, cfgPps); return PipelineStart.Ok
        }
        override fun stop() { stops++ }
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    /** Parks inside start() until the test releases it, so "the camera is opening" is a state the
     *  test can hold still and probe. */
    private class BlockingStartPipeline(private val inStart: CountDownLatch, private val release: CountDownLatch) : CapturePipeline {
        val starts = AtomicInteger()
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            starts.incrementAndGet()
            inStart.countDown()
            release.await(5, TimeUnit.SECONDS)
            listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2))
            return PipelineStart.Ok
        }
        override fun stop() {}
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    /** Parks inside stop(), so "the camera is closing" is a state the test can hold still. */
    private class BlockingStopPipeline(private val inStop: CountDownLatch, private val release: CountDownLatch) : CapturePipeline {
        var starts = 0
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            starts++; listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2)); return PipelineStart.Ok
        }
        override fun stop() { inStop.countDown(); release.await(5, TimeUnit.SECONDS) }
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    /** Parks inside requestKeyframe(), so "the encoder is mid-callout" is a state the test can
     *  hold still, and records a close that lands there -- in production
     *  `setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME)` against a codec being released. */
    private class BlockingKeyframePipeline(private val inKeyframe: CountDownLatch, private val release: CountDownLatch) : CapturePipeline {
        @Volatile var keyframeInFlight = false
        @Volatile var overlapped = false
        var starts = 0; var stops = 0
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            starts++; listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2)); return PipelineStart.Ok
        }
        override fun stop() { stops++; if (keyframeInFlight) overlapped = true }
        override fun requestKeyframe() {
            keyframeInFlight = true
            inKeyframe.countDown()
            release.await(5, TimeUnit.SECONDS)
            keyframeInFlight = false
        }
        override fun grabJpeg(): ByteArray? = null
    }

    /** A camera whose close fails: the hub must not go on believing it is up. */
    private class ThrowingStopPipeline : CapturePipeline {
        var starts = 0; var stops = 0
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            starts++; listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2)); return PipelineStart.Ok
        }
        override fun stop() { stops++; throw IllegalStateException("camera close failed") }
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    /** A linger whose task has ALREADY begun running: its cancel function cannot un-post it. */
    private class UncancellableScheduler : DelayScheduler {
        val pending = mutableListOf<() -> Unit>()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit { pending.add(task); return {} }
        fun fireFirst() { pending.removeAt(0)() }
    }

    /** Runs the first task it is handed INLINE, later ones normally: the deterministic stand-in
     *  for a linger that fires in the window between describe() and the snapshot guard. */
    private class InlineFirstScheduler : DelayScheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        private var fired = false
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            if (!fired) { fired = true; task(); return {} }
            val entry = delayMs to task; pending.add(entry); return { pending.remove(entry) }
        }
    }

    /** Runs [body] on another thread and reports whether it finished: it cannot finish while the
     *  caller holds the hub's lock, so this fails deterministically on a lock held across a
     *  callout and passes deterministically otherwise. */
    private fun completesOffThread(body: () -> Unit): Boolean {
        val t = Thread(body).apply { isDaemon = true; start() }
        t.join(2_000)
        return !t.isAlive
    }

    private class ManualScheduler : DelayScheduler {
        val pending = mutableListOf<Pair<Long, () -> Unit>>()
        override fun schedule(delayMs: Long, task: () -> Unit): () -> Unit {
            val entry = delayMs to task; pending.add(entry); return { pending.remove(entry) }
        }
        fun fireAll() { val copy = pending.toList(); pending.clear(); copy.forEach { it.second() } }
    }

    private class RecordingSink : FrameSink {
        val units = mutableListOf<AccessUnit>()
        override fun onAccessUnit(au: AccessUnit) { units.add(au) }
    }

    private fun hub(p: FakePipeline, s: ManualScheduler, states: MutableList<CameraShareStatus.State>) =
        CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, s, { states.add(it) }, configTimeoutMs = 10, clock = { 0L })

    @Test fun `describe starts the pipeline once and returns sps pps`() {
        val p = FakePipeline(); val s = ManualScheduler(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, s, states)
        val d = h.describe() as DescribeResult.Ready
        assertTrue(d.sps.contentEquals(sps)); assertTrue(d.pps.contentEquals(pps))
        h.describe()
        assertEquals(1, p.starts)
        assertEquals(CameraShareStatus.State.Ready, states.last())
        assertEquals(1, s.pending.size)   // linger armed: DESCRIBE with no PLAY must not pin the camera
    }

    @Test fun `describe reports a camera that cannot open`() {
        val p = FakePipeline(failWith = "camera gated"); val states = mutableListOf<CameraShareStatus.State>()
        val d = hub(p, ManualScheduler(), states).describe()
        assertEquals(DescribeResult.Unavailable("camera gated"), d)
        assertEquals(CameraShareStatus.State.Unavailable("camera gated"), states.last())
    }

    @Test fun `describe times out when no codec config arrives, stops the pipeline and can retry`() {
        val p = FakePipeline(configOnStart = false); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, ManualScheduler(), states)
        val d = h.describe()
        assertTrue(d is DescribeResult.Unavailable)
        assertEquals(1, p.stops)                                   // not left "running" on a dead latch
        assertEquals(CameraShareStatus.State.Unavailable("no encoder output"), states.last())
        h.describe(); assertEquals(2, p.starts)                    // the next DESCRIBE tries again
    }

    @Test fun `attach cancels linger requests a keyframe and counts viewers, detach lingers then stops`() {
        val p = FakePipeline(); val s = ManualScheduler(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, s, states)
        h.describe()
        val sink = RecordingSink()
        h.attach(sink)
        assertEquals(0, s.pending.size); assertEquals(1, p.keyframes)
        assertEquals(CameraShareStatus.State.Streaming(1), states.last()); assertEquals(1, h.viewerCount())
        h.detach(sink)
        assertEquals(CameraShareStatus.State.Ready, states.last()); assertEquals(1, s.pending.size); assertEquals(0, p.stops)
        s.fireAll()
        assertEquals(1, p.stops)
        // A later DESCRIBE starts it again.
        h.describe(); assertEquals(2, p.starts)
    }

    @Test fun `re-attach within the linger cancels the stop`() {
        val p = FakePipeline(); val s = ManualScheduler()
        val h = hub(p, s, mutableListOf()); h.describe()
        val sink = RecordingSink(); h.attach(sink); h.detach(sink)
        h.attach(sink)
        assertEquals(0, s.pending.size)
        s.fireAll(); assertEquals(0, p.stops)
    }

    @Test fun `keyframes get sps and pps prepended, other frames pass through`() {
        val p = FakePipeline(); val h = hub(p, ManualScheduler(), mutableListOf()); h.describe()
        val sink = RecordingSink(); h.attach(sink)
        p.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x65, 9)), 1000, keyframe = true))
        p.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x41, 9)), 2000, keyframe = false))
        assertEquals(3, sink.units[0].nals.size); assertTrue(sink.units[0].nals[0].contentEquals(sps))
        assertEquals(1, sink.units[1].nals.size)
    }

    @Test fun `pipeline error drops viewers publishes unavailable and notifies the server`() {
        val p = FakePipeline(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, ManualScheduler(), states); h.describe()
        var errored: String? = null; h.onError = { errored = it }
        h.attach(RecordingSink())
        p.listener!!.onPipelineError("camera disconnected")
        assertEquals(0, h.viewerCount()); assertEquals("camera disconnected", errored)
        assertEquals(CameraShareStatus.State.Unavailable("camera disconnected"), states.last())
        assertEquals(1, p.stops)
    }

    @Test fun `snapshot starts the camera grabs and leaves the linger to stop it`() {
        val p = FakePipeline(); val s = ManualScheduler()
        val h = hub(p, s, mutableListOf())
        assertNotNull(h.snapshotJpeg()); assertEquals(1, p.starts); assertEquals(1, s.pending.size)
        val failing = FakePipeline(failWith = "gated")
        assertNull(hub(failing, ManualScheduler(), mutableListOf()).snapshotJpeg())
    }

    @Test fun `shutdown stops and publishes off`() {
        val p = FakePipeline(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, ManualScheduler(), states); h.describe(); h.shutdown()
        assertEquals(1, p.stops); assertEquals(CameraShareStatus.State.Off, states.last())
    }

    @Test fun `codec config carrying annex-b start codes is held as bare nals`() {
        // A start code in the SDP makes profile-level-id read 000001 and corrupts every in-band
        // parameter set we prepend to a keyframe, so the hub strips what the pipeline hands it.
        val p = AnnexBPipeline()
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {}, configTimeoutMs = 10, clock = { 0L })
        val d = h.describe() as DescribeResult.Ready
        assertTrue(d.sps.contentEquals(sps)); assertTrue(d.pps.contentEquals(pps))
        val sink = RecordingSink(); h.attach(sink)
        p.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x65, 9)), 1000, keyframe = true))
        assertTrue(sink.units[0].nals[0].contentEquals(sps)); assertTrue(sink.units[0].nals[1].contentEquals(pps))
    }

    @Test fun `a linger armed while a snapshot is in flight does not stop the camera under it`() {
        val s = ManualScheduler()
        lateinit var h: CameraShareHub
        val sink = RecordingSink()
        // The last viewer leaves while the still capture (up to 3 s) is running: the linger it arms
        // must not tear the camera down underneath the grab.
        val p = FakePipeline(onGrab = { h.detach(sink); s.fireAll() })
        h = hub(p, s, mutableListOf())
        h.describe(); h.attach(sink)
        assertNotNull(h.snapshotJpeg())
        assertEquals(0, p.stops)
        assertEquals(1, s.pending.size)   // re-armed once the grab finished
        s.fireAll(); assertEquals(1, p.stops)
    }

    @Test fun `repeated attach and an unknown detach leave the viewer count intact`() {
        val p = FakePipeline(); val s = ManualScheduler(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, s, states); h.describe()
        val sink = RecordingSink()
        h.attach(sink); h.attach(sink)
        assertEquals(1, h.viewerCount())
        val published = states.size
        h.detach(RecordingSink())
        assertEquals(1, h.viewerCount()); assertEquals(published, states.size); assertEquals(0, s.pending.size)
    }

    @Test fun `describe and attach after shutdown never restart the camera`() {
        val p = FakePipeline(); val s = ManualScheduler(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, s, states); h.describe(); h.shutdown()
        // A client thread still inside the server when the service died must not reopen a camera
        // that now has no owner to close it.
        assertTrue(h.describe() is DescribeResult.Unavailable)
        h.attach(RecordingSink())
        assertEquals(1, p.starts); assertEquals(0, h.viewerCount()); assertEquals(0, s.pending.size)
        assertEquals(CameraShareStatus.State.Off, states.last())
    }

    @Test fun `lens change restarts the pipeline and keeps an idle camera on the linger`() {
        val p = FakePipeline(); val s = ManualScheduler()
        val h = hub(p, s, mutableListOf())
        h.describe()
        h.restartForLensChange()
        assertEquals(2, p.starts); assertEquals(1, p.stops)
        assertEquals(1, s.pending.size)   // the reopened idle camera is still on a leash
        s.fireAll(); assertEquals(2, p.stops)
    }

    @Test fun `a lens change with a viewer keyframes instead of putting the camera on a leash`() {
        val p = FakePipeline(); val s = ManualScheduler()
        val h = hub(p, s, mutableListOf())
        h.describe()
        h.attach(RecordingSink())
        assertEquals(1, p.keyframes)
        h.restartForLensChange()
        assertEquals(2, p.starts); assertEquals(1, p.stops)
        assertEquals(2, p.keyframes)      // the viewer needs an IDR off the new lens
        assertEquals(0, s.pending.size)   // an actively watched camera is never on a linger
        assertEquals(1, h.viewerCount())
    }

    @Test fun `a lens change that cannot reopen the camera drops viewers and tells the server`() {
        val p = FakePipeline(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, ManualScheduler(), states)
        h.describe(); h.attach(RecordingSink())
        var errored: String? = null; h.onError = { errored = it }
        p.failWith = "camera gated"
        h.restartForLensChange()
        assertEquals(0, h.viewerCount())
        assertEquals("camera failed to reopen", errored)
        assertEquals(CameraShareStatus.State.Unavailable("camera failed to reopen"), states.last())
    }

    @Test fun `attach restarts a camera the linger has already stopped`() {
        val p = FakePipeline(); val s = ManualScheduler(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, s, states)
        // A client that pauses longer than the linger between DESCRIBE and PLAY -- or that PLAYs
        // straight from a cached SDP -- must not be attached to a camera that is off.
        h.describe(); s.fireAll()
        assertEquals(1, p.stops)
        h.attach(RecordingSink())
        assertEquals(2, p.starts)
        assertEquals(1, h.viewerCount())
        assertEquals(CameraShareStatus.State.Streaming(1), states.last())
    }

    @Test fun `an empty parameter set is not a usable config`() {
        val p = RawConfigPipeline(ByteArray(0), pps); val states = mutableListOf<CameraShareStatus.State>()
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), { states.add(it) }, configTimeoutMs = 10, clock = { 0L })
        assertEquals(DescribeResult.Unavailable("no encoder output"), h.describe())
        assertEquals(1, p.stops)
        assertEquals(CameraShareStatus.State.Unavailable("no encoder output"), states.last())
    }

    @Test fun `a parameter set that is nothing but a start code is not a usable config`() {
        // splitAnnexB returns an EMPTY list here, so a fallback to the raw buffer would publish
        // profile-level-id=000001 -- the very corruption the bare-NAL rule exists to prevent.
        val p = RawConfigPipeline(byteArrayOf(0, 0, 0, 1), pps); val states = mutableListOf<CameraShareStatus.State>()
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), { states.add(it) }, configTimeoutMs = 10, clock = { 0L })
        assertEquals(DescribeResult.Unavailable("no encoder output"), h.describe())
        assertEquals(1, p.stops)
        assertEquals(CameraShareStatus.State.Unavailable("no encoder output"), states.last())
    }

    @Test fun `a snapshot re-describes when the linger stops the camera under it`() {
        val p = FakePipeline(); val s = InlineFirstScheduler()
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, s, {}, configTimeoutMs = 10, clock = { 0L })
        // The linger describe() arms fires immediately, so the camera is already down by the time
        // the in-flight guard is re-checked: the second attempt has to reopen it.
        assertNotNull(h.snapshotJpeg())
        assertEquals(2, p.starts); assertEquals(1, p.stops)
    }

    @Test fun `only one snapshot grabs at a time`() {
        val inGrab = CountDownLatch(1); val hold = CountDownLatch(1)
        val live = AtomicInteger(); val peak = AtomicInteger()
        val p = FakePipeline(onGrab = {
            // grabJpeg owns a single ImageReader listener: two overlapping grabs would clobber
            // each other and both time out.
            peak.accumulateAndGet(live.incrementAndGet()) { a, b -> maxOf(a, b) }
            inGrab.countDown(); hold.await(5, TimeUnit.SECONDS)
            live.decrementAndGet()
        })
        val h = hub(p, ManualScheduler(), mutableListOf())
        val first = Thread { h.snapshotJpeg() }.apply { isDaemon = true; start() }
        assertTrue(inGrab.await(5, TimeUnit.SECONDS))
        val second = Thread { h.snapshotJpeg() }.apply { isDaemon = true; start() }
        second.join(500)          // long enough for an unserialized grab to slip in
        hold.countDown()
        first.join(5_000); second.join(5_000)
        assertEquals(1, peak.get())
    }

    @Test fun `a camera opening does not block the hub lock`() {
        val inStart = CountDownLatch(1); val release = CountDownLatch(1)
        val p = BlockingStartPipeline(inStart, release)
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {}, configTimeoutMs = 2_000, clock = { 0L })
        val describer = Thread { h.describe() }.apply { isDaemon = true; start() }
        assertTrue(inStart.await(5, TimeUnit.SECONDS))
        try {
            // The linger, service teardown and every other viewer's socket thread take this lock.
            assertTrue("viewerCount() blocked behind pipeline.start()", completesOffThread { h.viewerCount() })
        } finally {
            release.countDown()
        }
        describer.join(5_000); assertFalse(describer.isAlive)
    }

    @Test fun `two concurrent describes open one camera and share its result`() {
        val inStart = CountDownLatch(1); val release = CountDownLatch(1)
        val p = BlockingStartPipeline(inStart, release)
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {}, configTimeoutMs = 2_000, clock = { 0L })
        val results = Collections.synchronizedList(mutableListOf<DescribeResult>())
        val one = Thread { results.add(h.describe()) }.apply { isDaemon = true; start() }
        assertTrue(inStart.await(5, TimeUnit.SECONDS))
        val two = Thread { results.add(h.describe()) }.apply { isDaemon = true; start() }
        release.countDown()
        one.join(5_000); two.join(5_000)
        assertEquals(1, p.starts.get())   // the second waits for the first, it does not open a second camera
        assertEquals(2, results.size)
        assertTrue(results.all { it is DescribeResult.Ready })
    }

    @Test fun `frames reach every sink even when one throws`() {
        val p = FakePipeline(); val h = hub(p, ManualScheduler(), mutableListOf()); h.describe()
        h.attach(object : FrameSink { override fun onAccessUnit(au: AccessUnit) = throw IllegalStateException("bad client") })
        val good = RecordingSink(); h.attach(good)
        p.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x41, 9)), 1000, keyframe = false))
        assertEquals(1, good.units.size)
    }

    @Test fun `the fan-out does not run under the hub lock`() {
        val p = FakePipeline(); val h = hub(p, ManualScheduler(), mutableListOf()); h.describe()
        var probeFinished = false
        h.attach(object : FrameSink {
            override fun onAccessUnit(au: AccessUnit) { probeFinished = completesOffThread { h.viewerCount() } }
        })
        p.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x41, 9)), 1000, keyframe = false))
        assertTrue("viewerCount() blocked: the fan-out ran under the hub lock", probeFinished)
    }

    @Test fun `the publish callout does not run under the hub lock`() {
        val p = FakePipeline(); lateinit var h: CameraShareHub
        var probeFinished = false
        h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(),
            { probeFinished = completesOffThread { h.viewerCount() } }, configTimeoutMs = 10, clock = { 0L })
        h.describe()
        assertTrue("viewerCount() blocked: publish ran under the hub lock", probeFinished)
    }

    @Test fun `a publish that re-enters the hub sees each state once and in order`() {
        val p = FakePipeline(); val states = mutableListOf<CameraShareStatus.State>()
        lateinit var h: CameraShareHub
        var reentered = false
        h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), { state ->
            states.add(state)
            if (!reentered) { reentered = true; h.attach(RecordingSink()) }
        }, configTimeoutMs = 10, clock = { 0L })
        h.describe()
        assertEquals(listOf(CameraShareStatus.State.Ready, CameraShareStatus.State.Streaming(1)), states)
    }

    @Test fun `a lens change does not hold the hub lock while the camera closes and reopens`() {
        val inStop = CountDownLatch(1); val release = CountDownLatch(1)
        val p = BlockingStopPipeline(inStop, release)
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {}, configTimeoutMs = 2_000, clock = { 0L })
        h.describe()
        val restarter = Thread { h.restartForLensChange() }.apply { isDaemon = true; start() }
        assertTrue(inStop.await(5, TimeUnit.SECONDS))
        try {
            assertTrue("viewerCount() blocked behind the lens change", completesOffThread { h.viewerCount() })
        } finally {
            release.countDown()
        }
        restarter.join(5_000); assertFalse(restarter.isAlive)
        assertEquals(2, p.starts)
    }

    @Test fun `a lens change does not strand a describe waiting for the codec config`() {
        val captured = CountDownLatch(1)
        val states = Collections.synchronizedList(mutableListOf<CameraShareStatus.State>())
        // Only the SECOND start produces a codec config, so the first describe is still waiting on
        // the latch the lens change is about to replace.
        val p = object : CapturePipeline {
            var starts = 0; var stops = 0
            override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
                starts++
                if (starts >= 2) listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2))
                return PipelineStart.Ok
            }
            override fun stop() { stops++ }
            override fun requestKeyframe() {}
            override fun grabJpeg(): ByteArray? = null
        }
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), { state ->
            states.add(state)
            // describe() publishes Ready only after it has captured the latch it will wait on.
            if (state == CameraShareStatus.State.Ready) captured.countDown()
        }, configTimeoutMs = 1_000, clock = { 0L })
        val describer = Thread { h.describe() }.apply { isDaemon = true; start() }
        assertTrue(captured.await(5, TimeUnit.SECONDS))
        h.restartForLensChange()
        describer.join(5_000); assertFalse(describer.isAlive)
        assertEquals(2, p.starts)
        assertEquals(1, p.stops)     // the timed-out waiter must not stop the camera the lens change reopened
        assertTrue(states.none { it is CameraShareStatus.State.Unavailable })
    }

    @Test fun `a camera close that throws does not leave the hub believing it is running`() {
        val p = ThrowingStopPipeline(); val s = ManualScheduler()
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, s, {}, configTimeoutMs = 10, clock = { 0L })
        h.describe()
        s.fireAll()                  // the linger stops the camera and the close blows up
        assertEquals(1, p.stops)
        h.describe()                 // the share must not be dead until the service restarts
        assertEquals(2, p.starts)
    }

    @Test fun `a linger that could not be cancelled cannot stop a camera that was just reopened`() {
        val p = FakePipeline(); val s = UncancellableScheduler()
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, s, {}, configTimeoutMs = 10, clock = { 0L })
        h.describe()                 // arms linger #1
        h.restartForLensChange()     // cancels #1 in vain (it is already running) and arms #2
        assertEquals(2, p.starts); assertEquals(1, p.stops)
        s.fireFirst()                // the stale task runs
        assertEquals(1, p.stops)             // the reopened camera is untouched
        assertEquals(1, s.pending.size)      // and the fresh linger is still armed
    }

    @Test fun `an unchanged status is not published again`() {
        val p = FakePipeline(); val states = mutableListOf<CameraShareStatus.State>()
        val h = hub(p, ManualScheduler(), states)
        // Home Assistant polls the snapshot endpoint; every poll describes, and a re-published
        // Ready rebuilds and re-posts the foreground notification.
        h.describe(); h.describe(); h.describe()
        assertEquals(listOf<CameraShareStatus.State>(CameraShareStatus.State.Ready), states)
    }

    @Test fun `a stop cannot land inside the keyframe request a new viewer triggers`() {
        val inKeyframe = CountDownLatch(1); val release = CountDownLatch(1)
        val p = BlockingKeyframePipeline(inKeyframe, release)
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {}, configTimeoutMs = 2_000, clock = { 0L })
        h.describe()
        val player = Thread { h.attach(RecordingSink()) }.apply { isDaemon = true; start() }
        assertTrue(inKeyframe.await(5, TimeUnit.SECONDS))
        // A PLAY landing exactly as the linger, a lens change or the service teardown closes the
        // camera: setParameters(PARAMETER_KEY_REQUEST_SYNC_FRAME) overlapping codec.stop()/
        // release() throws at best and aborts the process in the MediaCodec JNI layer at worst,
        // where runCatching cannot save it.
        h.shutdown()
        release.countDown()
        player.join(5_000); assertFalse(player.isAlive)
        assertFalse("the camera closed while requestKeyframe() was in flight", p.overlapped)
        assertEquals(1, p.stops)     // the stop is handed to the mutex owner, not lost
    }

    @Test fun `a describe in flight during a lens change is not answered with the old lens sets`() {
        val inStop = CountDownLatch(1); val release = CountDownLatch(1)
        val reading = CountDownLatch(1); val closing = CountDownLatch(1)
        // A different SPS per start, so an answer built from the retained sets is recognisable.
        val p = object : CapturePipeline {
            var starts = 0
            override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
                starts++
                listener.onCodecConfig(byteArrayOf(0x67, starts.toByte()), byteArrayOf(0x68, starts.toByte()))
                return PipelineStart.Ok
            }
            override fun stop() { inStop.countDown(); release.await(5, TimeUnit.SECONDS) }
            override fun requestKeyframe() {}
            override fun grabJpeg(): ByteArray? = null
        }
        var parked = false
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {
            // describe() publishes Ready once it has captured the latch and generation it will
            // answer from and before it reads the parameter sets -- the window a lens change has
            // to close, and the only deterministic way to hold a describe open inside it.
            if (!parked) { parked = true; reading.countDown(); closing.await(5, TimeUnit.SECONDS) }
        }, configTimeoutMs = 2_000, clock = { 0L })
        var answer: DescribeResult? = null
        val describer = Thread { answer = h.describe() }.apply { isDaemon = true; start() }
        assertTrue(reading.await(5, TimeUnit.SECONDS))
        val restarter = Thread { h.restartForLensChange() }.apply { isDaemon = true; start() }
        assertTrue(inStop.await(5, TimeUnit.SECONDS))   // the camera is closing on the old lens
        closing.countDown()
        describer.join(5_000); assertFalse(describer.isAlive)
        val d = answer
        assertFalse("DESCRIBE answered with the parameter sets of the lens we were leaving",
            d is DescribeResult.Ready && d.sps.contentEquals(byteArrayOf(0x67, 1)))
        release.countDown()
        restarter.join(5_000); assertFalse(restarter.isAlive)
        assertEquals(2, p.starts)
        // and the reopened camera describes itself with the NEW lens's sets
        assertTrue((h.describe() as DescribeResult.Ready).sps.contentEquals(byteArrayOf(0x67, 2)))
    }

    @Test fun `a shutdown during a lens change does not reopen the camera`() {
        val inStop = CountDownLatch(1); val release = CountDownLatch(1)
        val p = BlockingStopPipeline(inStop, release)
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), {}, configTimeoutMs = 2_000, clock = { 0L })
        h.describe()
        val restarter = Thread { h.restartForLensChange() }.apply { isDaemon = true; start() }
        assertTrue(inStop.await(5, TimeUnit.SECONDS))
        h.shutdown()                 // the main thread, while the lens change owns the camera mutex
        release.countDown()
        restarter.join(5_000); assertFalse(restarter.isAlive)
        // Reopening a camera the service has already abandoned turns the privacy light back on for
        // the four seconds the open takes, only for the abandon path to close it again.
        assertEquals(1, p.starts)
    }

    @Test fun `a publish that throws does not wedge the states behind it`() {
        val p = FakePipeline(); val states = mutableListOf<CameraShareStatus.State>()
        var boom = true
        val h = CameraShareHub(p, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, ManualScheduler(), { state ->
            if (boom) { boom = false; throw IllegalStateException("listener blew up") }
            states.add(state)
        }, configTimeoutMs = 10, clock = { 0L })
        h.describe()
        assertTrue(states.isEmpty())
        h.attach(RecordingSink())
        assertEquals(listOf<CameraShareStatus.State>(CameraShareStatus.State.Streaming(1)), states)
    }

    /** Emits whatever access units the test pushes, so the hub's metering can be observed. */
    private class FeedPipeline : CapturePipeline {
        var listener: PipelineListener? = null
        var lastEncoding: CameraShareSettings.Encoding? = null
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            this.listener = listener
            lastEncoding = encoding
            listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2))
            listener.onVideoSize(1280, 720)
            return PipelineStart.Ok
        }
        override fun stop() { listener = null }
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    @Test fun `streaming carries the size the pipeline reported and the metered rates`() {
        val pipeline = FeedPipeline()
        var now = 0L
        val published = Collections.synchronizedList(mutableListOf<CameraShareStatus.State>())
        val hub = CameraShareHub(
            pipeline = pipeline, lens = { CameraShareSettings.Lens.FRONT }, encoding = { defaultEncoding },
            scheduler = { _, _ -> {} }, publish = { published.add(it) }, clock = { now },
        )
        val sink = FrameSink { }
        assertNotNull(hub.describe())
        hub.attach(sink)
        assertEquals(defaultEncoding, pipeline.lastEncoding)
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720), published.last())
        // 15 frames of 12 500 bytes in one second: 15 fps, 1.5 Mbit/s.
        for (i in 0 until 15) {
            now = (i * 1_000L) / 15
            pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(12_500)), now * 1_000, i == 0))
        }
        now = 1_000
        pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(12_500)), 1_000_000, false))
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720, 15, 1_500_000), published.last())
        hub.shutdown()
    }

    @Test fun `a viewer change keeps the last measured rates instead of blanking them`() {
        val pipeline = FeedPipeline()
        var now = 0L
        val published = Collections.synchronizedList(mutableListOf<CameraShareStatus.State>())
        val hub = CameraShareHub(
            pipeline = pipeline, lens = { CameraShareSettings.Lens.FRONT }, encoding = { defaultEncoding },
            scheduler = { _, _ -> {} }, publish = { published.add(it) }, clock = { now },
        )
        val a = FrameSink { }; val b = FrameSink { }
        assertNotNull(hub.describe()); hub.attach(a)
        repeat(10) { i -> now = i * 100L; pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(1_000)), now * 1_000, false)) }
        now = 1_000; pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(1_000)), 1_000_000, false))
        // 80 kbit/s measured, published to the nearest 100 kbit/s (see quantizeBps).
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720, 10, 100_000), published.last())
        hub.attach(b)
        assertEquals(CameraShareStatus.State.Streaming(2, 1280, 720, 10, 100_000), published.last())
        hub.detach(b)
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720, 10, 100_000), published.last())
        hub.shutdown()
    }

    @Test fun `a second window at the same rounded bitrate publishes nothing new`() {
        val pipeline = FeedPipeline()
        var now = 0L
        val published = Collections.synchronizedList(mutableListOf<CameraShareStatus.State>())
        val hub = CameraShareHub(
            pipeline = pipeline, lens = { CameraShareSettings.Lens.FRONT }, encoding = { defaultEncoding },
            scheduler = { _, _ -> {} }, publish = { published.add(it) }, clock = { now },
        )
        assertNotNull(hub.describe()); hub.attach(FrameSink { })
        // Window one: 15 x 12 500 bytes = 1 500 000 bps exactly.
        for (i in 0 until 15) {
            now = (i * 1_000L) / 15
            pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(12_500)), now * 1_000, false))
        }
        // Window two: 15 x 12 667 bytes = 1 520 040 bps, which rounds to the same 1.5 Mbit/s. The
        // frame that CLOSES window one is already the first of window two.
        for (i in 0 until 15) {
            now = 1_000 + (i * 1_000L) / 15
            pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(12_667)), now * 1_000, false))
        }
        val streaming = CameraShareStatus.State.Streaming(1, 1280, 720, 15, 1_500_000)
        assertEquals(streaming, published.last())
        val afterFirstWindow = published.size
        now = 2_000
        pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(12_667)), 2_000_000, false))
        assertEquals(streaming, published.last())
        assertEquals("a second window at the same rounded rate must not re-publish", afterFirstWindow, published.size)
        assertEquals(1, published.count { it == streaming })
        hub.shutdown()
    }

    @Test fun `a window whose frame rate wobbles by one publishes nothing new`() {
        val pipeline = FeedPipeline()
        var now = 0L
        val published = Collections.synchronizedList(mutableListOf<CameraShareStatus.State>())
        val hub = CameraShareHub(
            pipeline = pipeline, lens = { CameraShareSettings.Lens.FRONT }, encoding = { defaultEncoding },
            scheduler = { _, _ -> {} }, publish = { published.add(it) }, clock = { now },
        )
        assertNotNull(hub.describe()); hub.attach(FrameSink { })
        // Every window below carries the same 187 500 bytes, so all four round to 1.5 Mbit/s and
        // only the frame rate can move the published state. The frame that CLOSES one window is
        // already the first frame of the next.
        fun window(startMs: Long, frames: Int, bytesPerFrame: Int) {
            for (i in 0 until frames) {
                now = startMs + (i * 1_000L) / frames
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(bytesPerFrame)), now * 1_000, false))
            }
        }
        window(0, 15, 12_500)       // 15 fps
        window(1_000, 16, 11_719)   // closes window one: 15 fps, 1.5 Mbit/s
        val streaming = CameraShareStatus.State.Streaming(1, 1280, 720, 15, 1_500_000)
        assertEquals(streaming, published.last())
        val afterFirstWindow = published.size
        window(2_000, 14, 13_393)   // closes window two: 16 fps -- one off, so nothing new
        assertEquals(streaming, published.last())
        assertEquals("a one-fps wobble up must not re-publish", afterFirstWindow, published.size)
        window(3_000, 30, 6_250)    // closes window three: 14 fps -- one off the other way
        assertEquals(streaming, published.last())
        assertEquals("a one-fps wobble down must not re-publish", afterFirstWindow, published.size)
        assertEquals(1, published.count { it == streaming })
        now = 4_000                 // closes window four: 30 fps is a real change
        pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(6_250)), 4_000_000, false))
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720, 30, 1_500_000), published.last())
        assertEquals(afterFirstWindow + 1, published.size)
        hub.shutdown()
    }

    @Test fun `stopping the camera clears the measured rates and the next start re-reports the size`() {
        val pipeline = FeedPipeline()
        var now = 0L
        val s = ManualScheduler()
        val published = Collections.synchronizedList(mutableListOf<CameraShareStatus.State>())
        val hub = CameraShareHub(
            pipeline, { CameraShareSettings.Lens.FRONT }, { defaultEncoding }, s, { published.add(it) },
            configTimeoutMs = 10, clock = { now },
        )
        val sink = FrameSink { }
        assertNotNull(hub.describe()); hub.attach(sink)
        repeat(10) { i -> now = i * 100L; pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(1_000)), now * 1_000, false)) }
        now = 1_000; pipeline.listener!!.onAccessUnit(AccessUnit(listOf(ByteArray(1_000)), 1_000_000, false))
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720, 10, 100_000), published.last())
        hub.detach(sink)                 // arms the linger
        s.fireAll()                      // ...which stops the camera
        hub.attach(sink)                 // reopens it: no measurement yet, the size comes back
        assertEquals(CameraShareStatus.State.Streaming(1, 1280, 720), published.last())
        hub.shutdown()
    }
}
