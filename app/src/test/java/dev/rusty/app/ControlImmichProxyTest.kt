package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

private val CFG = ImmichConfig(baseUrl = "https://immich.example", apiKey = "key")

private class FakeFetcher : (String, ImmichConfig) -> ImmichResult<List<ImmichPickerItem>> {
    val calls = mutableListOf<String>()
    /** Queue of results per invocation; last entry repeats once exhausted. */
    var results: MutableMap<String, ImmichResult<List<ImmichPickerItem>>> = mutableMapOf()

    override fun invoke(kind: String, cfg: ImmichConfig): ImmichResult<List<ImmichPickerItem>> {
        calls.add(kind)
        return results[kind] ?: ImmichResult.Ok(emptyList())
    }
}

/** Mutable fake clock the test advances directly, per the brief. */
private class FakeClock {
    var now: Long = 0L
    val fn: () -> Long = { now }
}

class ControlImmichProxyTest {

    @Test
    fun `null config returns NotConfigured without calling the fetcher`() {
        val fetcher = FakeFetcher()
        val proxy = ControlImmichProxy(configProvider = { null }, fetcher = fetcher, clock = { 0L })

        val result = proxy.list("albums")

        assertEquals(ControlImmichResult.NotConfigured, result)
        assertTrue(fetcher.calls.isEmpty())
    }

    @Test
    fun `Ok result is cached within the 60s TTL - fetcher called once for two calls`() {
        val fetcher = FakeFetcher()
        val items = listOf(ImmichPickerItem(id = "a1", label = "Album One"))
        fetcher.results["albums"] = ImmichResult.Ok(items)
        val clock = FakeClock()
        val proxy = ControlImmichProxy(configProvider = { CFG }, fetcher = fetcher, clock = clock.fn)

        val first = proxy.list("albums")
        clock.now = 59_999L
        val second = proxy.list("albums")

        assertEquals(ControlImmichResult.Ok(items), first)
        assertEquals(ControlImmichResult.Ok(items), second)
        assertEquals(1, fetcher.calls.size)
    }

    @Test
    fun `re-fetches after TTL expiry at exactly 60000ms later`() {
        val fetcher = FakeFetcher()
        val items = listOf(ImmichPickerItem(id = "a1", label = "Album One"))
        fetcher.results["albums"] = ImmichResult.Ok(items)
        val clock = FakeClock()
        val proxy = ControlImmichProxy(configProvider = { CFG }, fetcher = fetcher, clock = clock.fn)

        proxy.list("albums")
        clock.now = 60_000L
        proxy.list("albums")

        // TTL is exclusive at the boundary: exactly 60_000ms after the first fetch is a re-fetch.
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `Unauthorized error is not cached - second call hits fetcher again`() {
        val fetcher = FakeFetcher()
        fetcher.results["albums"] = ImmichResult.Error(ImmichErrorKind.AUTH)
        val proxy = ControlImmichProxy(configProvider = { CFG }, fetcher = fetcher, clock = { 0L })

        val first = proxy.list("albums")
        val second = proxy.list("albums")

        assertEquals(ControlImmichResult.Unauthorized, first)
        assertEquals(ControlImmichResult.Unauthorized, second)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `Unreachable error is not cached - second call hits fetcher again`() {
        val fetcher = FakeFetcher()
        fetcher.results["people"] = ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
        val proxy = ControlImmichProxy(configProvider = { CFG }, fetcher = fetcher, clock = { 0L })

        val first = proxy.list("people")
        val second = proxy.list("people")

        assertEquals(ControlImmichResult.Unreachable, first)
        assertEquals(ControlImmichResult.Unreachable, second)
        assertEquals(2, fetcher.calls.size)
    }

    @Test
    fun `cache is keyed per kind - albums fetch does not satisfy a later tags call`() {
        val fetcher = FakeFetcher()
        val albumItems = listOf(ImmichPickerItem(id = "a1", label = "Album One"))
        val tagItems = listOf(ImmichPickerItem(id = "t1", label = "Tag One"))
        fetcher.results["albums"] = ImmichResult.Ok(albumItems)
        fetcher.results["tags"] = ImmichResult.Ok(tagItems)
        val proxy = ControlImmichProxy(configProvider = { CFG }, fetcher = fetcher, clock = { 0L })

        val albums = proxy.list("albums")
        val tags = proxy.list("tags")

        assertEquals(ControlImmichResult.Ok(albumItems), albums)
        assertEquals(ControlImmichResult.Ok(tagItems), tags)
        assertEquals(listOf("albums", "tags"), fetcher.calls)
    }

    @Test
    fun `an unrecognised kind returns Unreachable rather than throwing`() {
        // The proxy doesn't validate kind itself (Task 5's router already whitelists it); the
        // requirement is that a fetcher which can't map an unrecognised kind and throws must not
        // crash list() -- it degrades to Unreachable instead of propagating.
        val fetcher = object : (String, ImmichConfig) -> ImmichResult<List<ImmichPickerItem>> {
            val calls = mutableListOf<String>()
            override fun invoke(kind: String, cfg: ImmichConfig): ImmichResult<List<ImmichPickerItem>> {
                calls.add(kind)
                throw IllegalArgumentException("unknown kind: $kind")
            }
        }
        val proxy = ControlImmichProxy(configProvider = { CFG }, fetcher = fetcher, clock = { 0L })

        val result = proxy.list("bogus")

        assertEquals(ControlImmichResult.Unreachable, result)
        assertEquals(listOf("bogus"), fetcher.calls)
    }

    // ---- wall-clock budget: one slow crawl must not own an HTTP worker ---------------------
    //
    // Every remote-control route is answered from one small worker pool, and this is the only
    // route that talks to an upstream server. A people crawl against a half-dead Immich can run
    // for minutes (200 pages x 8s timeouts); three of them (one control-page load) would leave
    // Home Assistant's /api/state poll with no worker, and HA maps a timed-out poll to "device
    // unavailable". These pin the two mechanisms that bound it.

    @Test
    fun `a crawl that outlives the budget releases the caller with Unreachable`() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val items = listOf(ImmichPickerItem(id = "p1", label = "Person One"))
        val fetcher: (String, ImmichConfig) -> ImmichResult<List<ImmichPickerItem>> = { _, _ ->
            calls.incrementAndGet()
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            ImmichResult.Ok(items)
        }
        val proxy = ControlImmichProxy(
            configProvider = { CFG }, fetcher = fetcher, clock = { 0L }, budgetMs = 100L,
        )

        val began = System.nanoTime()
        val result = proxy.list("people")
        val waitedMs = (System.nanoTime() - began) / 1_000_000

        assertEquals(ControlImmichResult.Unreachable, result)
        assertTrue("caller waited ${waitedMs}ms, expected to be released near the 100ms budget", waitedMs < 3_000)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertEquals(1, calls.get())
    }

    @Test
    fun `a crawl abandoned at the budget still warms the cache for the next caller`() {
        // Deliberate: the crawl is not cancelled, so a genuinely slow first load turns into a fast
        // second one instead of restarting the whole thing from scratch every 20 seconds.
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val items = listOf(ImmichPickerItem(id = "p1", label = "Person One"))
        val fetcher: (String, ImmichConfig) -> ImmichResult<List<ImmichPickerItem>> = { _, _ ->
            calls.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            ImmichResult.Ok(items)
        }
        val proxy = ControlImmichProxy(
            configProvider = { CFG }, fetcher = fetcher, clock = { 0L }, budgetMs = 100L,
        )

        assertEquals(ControlImmichResult.Unreachable, proxy.list("people"))
        release.countDown()

        // Let the abandoned crawl finish and store its result.
        val cached = awaitResult(5_000) { proxy.list("people") as? ControlImmichResult.Ok }
        assertEquals(items, cached.items)
        assertEquals("the abandoned crawl must be reused, not re-run", 1, calls.get())
    }

    @Test
    fun `concurrent callers for one kind share a single crawl`() {
        // N open browser tabs must not become N upstream crawls: with the budget alone, three
        // tabs loading at once would still be nine simultaneous requests to a server that is
        // already struggling.
        val inFetcher = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val items = listOf(ImmichPickerItem(id = "p1", label = "Person One"))
        val fetcher: (String, ImmichConfig) -> ImmichResult<List<ImmichPickerItem>> = { _, _ ->
            calls.incrementAndGet()
            inFetcher.countDown()
            release.await(5, TimeUnit.SECONDS)
            ImmichResult.Ok(items)
        }
        val proxy = ControlImmichProxy(
            configProvider = { CFG }, fetcher = fetcher, clock = { 0L }, budgetMs = 5_000L,
        )

        val results = java.util.concurrent.ConcurrentLinkedQueue<ControlImmichResult>()
        val threads = (1..3).map { Thread { results.add(proxy.list("people")) } }
        threads[0].start()
        assertTrue("first crawl must actually be running", inFetcher.await(5, TimeUnit.SECONDS))
        threads.drop(1).forEach { it.start() }
        // Give the joiners a moment to reach the shared future before it completes.
        Thread.sleep(100)
        release.countDown()
        threads.forEach { it.join(5_000) }

        assertEquals(3, results.size)
        results.forEach { assertEquals(ControlImmichResult.Ok(items), it) }
        assertEquals("three concurrent callers must share one crawl", 1, calls.get())
    }

    /** Polls [attempt] until it yields a value or [timeoutMs] elapses. Used where the thing being
     *  awaited is a side effect of a task the test deliberately abandoned, so there is no handle
     *  on it to join. */
    private fun awaitResult(timeoutMs: Long, attempt: () -> ControlImmichResult.Ok?): ControlImmichResult.Ok {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            attempt()?.let { return it }
            Thread.sleep(20)
        }
        throw AssertionError("no Ok result within ${timeoutMs}ms")
    }
}
