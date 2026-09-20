package dev.rusty.app

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStreamRepairerTest {
    private val sps = byteArrayOf(0x67, 1, 2, 3)
    private val pps = byteArrayOf(0x68, 4)
    private val direct = Executor { it.run() }

    private class FakeProxy(host: String, port: Int, sps: ByteArray, pps: ByteArray) : SdpRepairProxy(host, port, sps, pps) {
        var closed = false
        override fun start(): Int? = 40_000
        override fun close() { closed = true }
    }

    @Test
    fun `repair sniffs then starts a proxy on the camera's host and port`() {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        val proxies = mutableListOf<FakeProxy>()
        val r = LiveStreamRepairer(
            fetch = { u, us, p -> calls += Triple(u, us, p); RtspParamSetFetcher.Result.Found(sps, pps, "v=0") },
            proxyFactory = { h, p, s, pp -> assertEquals("192.168.4.90", h); assertEquals(554, p); FakeProxy(h, p, s, pp).also { proxies += it } },
            executor = direct,
        )
        var result: RepairResult? = null
        r.repair("rtsp://192.168.4.90:554/h264Preview_01_sub?x=1", "admin", "pw", true) { result = it }
        assertEquals(RepairResult.Ready("rtsp://127.0.0.1:40000/h264Preview_01_sub?x=1"), result)
        assertEquals(listOf(Triple("rtsp://192.168.4.90:554/h264Preview_01_sub?x=1", "admin", "pw")), calls)
        assertEquals(1, proxies.size)
        r.close()
    }

    @Test
    fun `the proxy is fed the sniffed parameter sets`() {
        var seenSps: ByteArray? = null
        var seenPps: ByteArray? = null
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> seenSps = s; seenPps = pp; FakeProxy(h, p, s, pp) },
            executor = direct,
        )
        r.repair("rtsp://cam/live", null, null, true) {}
        org.junit.Assert.assertArrayEquals(sps, seenSps)
        org.junit.Assert.assertArrayEquals(pps, seenPps)
        r.close()
    }

    @Test
    fun `a failed sniff is reported and starts nothing`() {
        var started = 0
        val r = LiveStreamRepairer(fetch = { _, _, _ -> RtspParamSetFetcher.Result.Failed("no SPS") }, proxyFactory = { h, p, s, pp -> started++; FakeProxy(h, p, s, pp) }, executor = direct)
        var result: RepairResult? = null
        r.repair("rtsp://cam/live", null, null, true) { result = it }
        assertEquals(RepairResult.Failed("no SPS"), result)
        assertEquals(0, started)
        assertNull(r.cachedProxyUrl("rtsp://cam/live"))
        r.close()
    }

    @Test
    fun `cached parameter sets skip the sniff and a new proxy replaces the old one`() {
        var fetches = 0
        val proxies = mutableListOf<FakeProxy>()
        val r = LiveStreamRepairer(fetch = { _, _, _ -> fetches++; RtspParamSetFetcher.Result.Found(sps, pps, "") }, proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp).also { proxies += it } }, executor = direct)
        r.repair("rtsp://cam/live", null, null, true) {}
        assertEquals("rtsp://127.0.0.1:40000/live", r.cachedProxyUrl("rtsp://cam/live"))
        assertEquals(1, fetches)
        assertEquals(2, proxies.size)
        assertTrue(proxies[0].closed)
        r.invalidate("rtsp://cam/live")
        assertNull(r.cachedProxyUrl("rtsp://cam/live"))
        r.close()
        assertTrue(proxies[1].closed)
    }

    @Test
    fun `a second repair of a cached stream does not sniff again`() {
        var fetches = 0
        val proxies = mutableListOf<FakeProxy>()
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> fetches++; RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp).also { proxies += it } },
            executor = direct,
        )
        r.repair("rtsp://cam/live", null, null, true) {}
        var result: RepairResult? = null
        r.repair("rtsp://cam/live", null, null, true) { result = it }
        assertEquals(RepairResult.Ready("rtsp://127.0.0.1:40000/live"), result)
        assertEquals(1, fetches)
        assertEquals(2, proxies.size)
        assertTrue(proxies[0].closed)
        r.close()
    }

    @Test
    fun `close stops the proxy but keeps the learned parameter sets`() {
        val proxies = mutableListOf<FakeProxy>()
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp).also { proxies += it } },
            executor = direct,
        )
        r.repair("rtsp://cam/live", null, null, true) {}
        r.close()
        assertTrue(proxies[0].closed)
        // The cache outlives the proxy: the next session opens straight through a fresh one.
        assertEquals("rtsp://127.0.0.1:40000/live", r.cachedProxyUrl("rtsp://cam/live"))
        assertEquals(2, proxies.size)
        r.close()
    }

    @Test
    fun `default port is 554 and a bind failure fails the repair`() {
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> assertEquals(554, p); object : SdpRepairProxy(h, p, s, pp) { override fun start(): Int? = null } },
            executor = direct,
        )
        var result: RepairResult? = null
        r.repair("rtsp://cam/live", null, null, true) { result = it }
        assertEquals(RepairResult.Failed("proxy bind failed"), result)
        r.close()
    }

    @Test
    fun `a sniff that throws fails the repair without leaking the exception message`() {
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> throw IllegalStateException("rtsp://admin:hunter2@cam/live broke") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp) },
            executor = direct,
        )
        var result: RepairResult? = null
        r.repair("rtsp://cam/live", null, null, true) { result = it }
        assertEquals(RepairResult.Failed("IllegalStateException"), result)
        r.close()
    }

    @Test
    fun `proxy url keeps path and query only`() {
        assertEquals("rtsp://127.0.0.1:5/a/b?c=d", LiveStreamRepairer.proxyUrlFor("rtsp://h:554/a/b?c=d", 5))
        assertEquals("rtsp://127.0.0.1:5/", LiveStreamRepairer.proxyUrlFor("rtsp://h", 5))
    }

    @Test
    fun `the proxy url carries no credentials`() {
        val proxies = mutableListOf<FakeProxy>()
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp).also { proxies += it } },
            executor = direct,
        )
        var result: RepairResult? = null
        r.repair("rtsp://admin:hunter2@cam:554/live", "admin", "hunter2", true) { result = it }
        assertEquals(RepairResult.Ready("rtsp://127.0.0.1:40000/live"), result)
        r.close()
    }

    // ---- stale repairs (real executor, gated fetch) ----

    @Test
    fun `a repair that finishes after close starts no proxy`() {
        val gate = CountDownLatch(1)
        var started = 0
        val exec = Executors.newSingleThreadExecutor()
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> gate.await(); RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> started++; FakeProxy(h, p, s, pp) },
            executor = exec,
        )
        val done = CountDownLatch(1)
        var result: RepairResult? = null
        r.repair("rtsp://cam/live", null, null, true) { result = it; done.countDown() }
        r.close()                       // the user left the live view while the sniff was running
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(RepairResult.Failed("superseded"), result)
        assertEquals(0, started)
        exec.shutdown()
    }

    @Test
    fun `a repair that finishes after another session started leaves that session's proxy alone`() {
        val gate = CountDownLatch(1)
        val proxies = mutableListOf<FakeProxy>()
        val exec = Executors.newSingleThreadExecutor()
        val r = LiveStreamRepairer(
            fetch = { url, _, _ -> if (url.endsWith("/a")) gate.await(); RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp).also { proxies += it } },
            executor = exec,
        )
        val warm = CountDownLatch(1)
        r.repair("rtsp://cam/b", null, null, true) { warm.countDown() }        // learns B's parameter sets
        assertTrue(warm.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        var result: RepairResult? = null
        r.repair("rtsp://cam/a", null, null, true) { result = it; done.countDown() }   // session A, slow sniff
        r.close()                                                                // A's view closed …
        assertEquals("rtsp://127.0.0.1:40000/b", r.cachedProxyUrl("rtsp://cam/b"))    // … B opened from cache
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(RepairResult.Failed("superseded"), result)
        assertEquals(2, proxies.size)            // B's first proxy and B's cached one — none for A
        assertFalse(proxies[1].closed)
        exec.shutdown()
        r.close()
    }

    @Test
    fun `a late repair still records what it learned`() {
        val gate = CountDownLatch(1)
        val proxies = mutableListOf<FakeProxy>()
        val exec = Executors.newSingleThreadExecutor()
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> gate.await(); RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp).also { proxies += it } },
            executor = exec,
        )
        val done = CountDownLatch(1)
        r.repair("rtsp://cam/live", null, null, true) { done.countDown() }
        r.close()
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(0, proxies.size)
        // Superseded or not, the parameter sets are this camera's: the next session reuses them.
        assertEquals("rtsp://127.0.0.1:40000/live", r.cachedProxyUrl("rtsp://cam/live"))
        assertEquals(1, proxies.size)
        r.close()
        exec.shutdown()
    }

    // ---- fix round 1 ----

    @Test
    fun `a malformed url is reported as a bad url, not a bind failure`() {
        var started = 0
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> started++; FakeProxy(h, p, s, pp) },
            executor = direct,
        )
        var result: RepairResult? = null
        r.repair("rtsp://[not a host/live", null, null, true) { result = it }
        assertEquals(RepairResult.Failed("bad url"), result)
        assertEquals(0, started)
        r.close()
    }

    @Test
    fun `a url with no host is a bad url too`() {
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Found(sps, pps, "") },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp) },
            executor = direct,
        )
        var result: RepairResult? = null
        r.repair("rtsp:live", null, null, true) { result = it }
        assertEquals(RepairResult.Failed("bad url"), result)
        r.close()
    }

    @Test
    fun `proxyUrlFor returns null for a malformed url instead of throwing`() {
        assertNull(LiveStreamRepairer.proxyUrlFor("rtsp://[not a host/live", 5))
    }

    @Test
    fun `a sniff failure reason is stripped of credentials before it is logged`() {
        val lines = mutableListOf<String>()
        val leak = "DESCRIBE failed for rtsp://admin:hunter2@cam/live"
        val r = LiveStreamRepairer(
            fetch = { _, _, _ -> RtspParamSetFetcher.Result.Failed(leak) },
            proxyFactory = { h, p, s, pp -> FakeProxy(h, p, s, pp) },
            executor = direct,
            log = { lines += it },
        )
        r.repair("rtsp://cam/live", "admin", "hunter2", true) {}
        assertTrue(lines.isNotEmpty())
        lines.forEach {
            assertFalse(it, it.contains("hunter2"))
            assertFalse(it, it.contains("admin:"))
        }
        r.close()
    }
}
