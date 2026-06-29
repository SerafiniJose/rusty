package dev.rusty.app

import dev.rusty.app.HomeAssistantDashboards.HaDashboard
import dev.rusty.app.HomeAssistantDashboards.OVERVIEW_PATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Test infrastructure — synchronous fakes for JVM testing
// ---------------------------------------------------------------------------

/**
 * Fake [Scheduler]: records scheduled actions and exposes [fire] / [cancel] control for tests.
 * Actions are NOT fired automatically — call [fire] to simulate timeout expiry.
 */
class FakeScheduler : Scheduler {
    data class Entry(val delayMs: Long, val action: Runnable, var cancelled: Boolean = false)

    val entries = mutableListOf<Entry>()

    override fun schedule(delayMs: Long, action: Runnable): Cancellable {
        val entry = Entry(delayMs, action)
        entries.add(entry)
        return Cancellable { entry.cancelled = true }
    }

    /** Fires the most recently scheduled non-cancelled entry (simulates timeout). */
    fun fireLast() {
        val last = entries.lastOrNull { !it.cancelled }
            ?: error("No pending scheduled action to fire")
        last.action.run()
    }

    val scheduledCount: Int get() = entries.size
    val cancelledCount: Int get() = entries.count { it.cancelled }
    val pendingCount: Int get() = entries.count { !it.cancelled }
}

/** Fake [HaStore]: in-memory, origin-keyed map. */
class FakeHaStore : HaStore {
    data class Saved(val dashboards: List<HaDashboard>, val selectedPaths: List<String>)

    val saves = mutableMapOf<String, Saved>()
    var initial: Map<String, PersistedHa> = emptyMap()

    /** Seeds an origin-keyed entry so [load] returns it from the start. */
    fun seed(origin: String, dashboards: List<HaDashboard>, selectedPaths: List<String>) {
        initial = initial + (origin to PersistedHa(dashboards, selectedPaths))
    }

    /** The [selectedPaths] argument from the most recent [save] call (null if never saved). */
    val lastSavedSelection: List<String>?
        get() = saves.values.lastOrNull()?.selectedPaths

    override fun load(origin: String): PersistedHa? = initial[origin]
    override fun save(origin: String, dashboards: List<HaDashboard>, selectedPaths: List<String>) {
        saves[origin] = Saved(dashboards, selectedPaths)
    }
}

typealias FakeStore = FakeHaStore

/** Synchronous [MainPoster]: runs runnables immediately so tests need no threading. */
val synchronousPoster = MainPoster { it.run() }

/** Instantiable variant of [synchronousPoster] — same behaviour, but constructable via `SyncPoster()`. */
class SyncPoster : MainPoster { override fun post(r: Runnable) = r.run() }

/**
 * Deferred [MainPoster]: captures posted runnables in FIFO order instead of running them, so a test
 * can interleave mutations (e.g. removeListener) between the post and the run. Call [runNext] /
 * [runAll] to drain.
 */
class DeferredPoster : MainPoster {
    val queued = ArrayDeque<Runnable>()
    override fun post(r: Runnable) { queued.addLast(r) }
    fun runNext() { queued.removeFirst().run() }
    fun runAll() { while (queued.isNotEmpty()) queued.removeFirst().run() }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private val VALID_PANELS_JSON = """
    {"panels":{
      "kitchen": {"component_name":"lovelace","title":"Kitchen","icon":"mdi:fridge","url_path":"kitchen"},
      "energy":  {"component_name":"lovelace","title":"Energy",  "icon":null,       "url_path":"energy"}
    }}
""".trimIndent()

private val VALID_PANELS = listOf(
    HaDashboard("Kitchen", "kitchen", "mdi:fridge",
        componentName = "lovelace", kind = HomeAssistantDashboards.Kind.DASHBOARD),
    HaDashboard("Energy",  "energy",  null,
        componentName = "lovelace", kind = HomeAssistantDashboards.Kind.DASHBOARD),
)

private const val ORIGIN = "http://homeassistant.local:8123"
private const val GARBAGE_JSON = "this is not json"

private fun repo(
    store: HaStore = FakeHaStore(),
    scheduler: FakeScheduler = FakeScheduler(),
): HomeAssistantDashboardRepository =
    HomeAssistantDashboardRepository(store, synchronousPoster, scheduler)

// ---------------------------------------------------------------------------
// Repository tests
// ---------------------------------------------------------------------------

class HomeAssistantDashboardRepositoryTest {

    // --- hydrate ---

    @Test fun hydrate_withCache_transitionsToLoadedImmediately_noGeneration() {
        val store = FakeHaStore().apply {
            initial = mapOf(ORIGIN to PersistedHa(VALID_PANELS, listOf(OVERVIEW_PATH)))
        }
        val scheduler = FakeScheduler()
        val r = repo(store, scheduler)

        val received = mutableListOf<HaDiscovery>()
        r.addListener { received.add(it) }

        r.hydrate(ORIGIN)

        // Should be Loaded; no generation scheduled (no timeout)
        assertTrue("Expected Loaded state", r.state is HaDiscovery.Loaded)
        assertEquals(VALID_PANELS, (r.state as HaDiscovery.Loaded).dashboards)
        assertEquals(0, scheduler.scheduledCount)
    }

    @Test fun hydrate_noCache_staysIdle() {
        val r = repo()
        r.hydrate(ORIGIN)
        assertTrue("Expected Idle when no cache", r.state is HaDiscovery.Idle)
    }

    // --- beginRefresh ---

    @Test fun beginRefresh_transitionsToRefreshing_andSchedulesTimeout() {
        val scheduler = FakeScheduler()
        val r = repo(scheduler = scheduler)

        r.beginRefresh(ORIGIN)

        assertTrue("Expected Refreshing after beginRefresh", r.state is HaDiscovery.Refreshing)
        assertEquals("Expected exactly one scheduled timeout", 1, scheduler.scheduledCount)
        assertEquals("Timeout should be pending (not cancelled)", 1, scheduler.pendingCount)
    }

    @Test fun beginRefresh_returnsMonotonicallyIncreasingGeneration() {
        val r = repo()
        val gen1 = r.beginRefresh(ORIGIN)
        val gen2 = r.beginRefresh(ORIGIN)
        assertTrue("Gen2 must be > Gen1", gen2 > gen1)
    }

    // --- submitResult (valid) ---

    @Test fun submitResult_validJson_transitionsToLoaded_savesAndCancelsTimeout() {
        val store = FakeHaStore().apply {
            initial = mapOf(ORIGIN to PersistedHa(emptyList(), listOf(OVERVIEW_PATH)))
        }
        val scheduler = FakeScheduler()
        val r = repo(store, scheduler)

        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, VALID_PANELS_JSON)

        assertTrue("Expected Loaded after valid submitResult", r.state is HaDiscovery.Loaded)
        assertEquals(VALID_PANELS, (r.state as HaDiscovery.Loaded).dashboards)

        // store.save must have been called for this origin
        assertNotNull("store.save should have been called", store.saves[ORIGIN])

        // Selection must be pruned (normalizeSelection → OVERVIEW_PATH + any previously-selected that exist)
        val saved = store.saves[ORIGIN]!!
        assertTrue("Pruned selection must contain OVERVIEW_PATH", OVERVIEW_PATH in saved.selectedPaths)

        // Timeout must be cancelled
        assertEquals("Timeout should be cancelled after submitResult", 1, scheduler.cancelledCount)
    }

    @Test fun submitResult_validJson_prunesSelectionAgainstNewList() {
        // Pre-select "kitchen" and "ghost" (ghost doesn't exist in the new panels)
        val store = FakeHaStore().apply {
            initial = mapOf(ORIGIN to PersistedHa(emptyList(), listOf(OVERVIEW_PATH, "kitchen", "ghost")))
        }
        val r = repo(store)

        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, VALID_PANELS_JSON)

        val saved = store.saves[ORIGIN]!!
        // "ghost" must be pruned; "kitchen" stays; OVERVIEW_PATH stays
        assertTrue(OVERVIEW_PATH in saved.selectedPaths)
        assertTrue("kitchen" in saved.selectedPaths)
        assertTrue("ghost should be pruned", "ghost" !in saved.selectedPaths)
    }

    // --- submitResult (garbage) ---

    @Test fun submitResult_garbageJson_transitionsToError() {
        val r = repo()
        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, GARBAGE_JSON)

        assertTrue("Expected Error on malformed JSON", r.state is HaDiscovery.Error)
    }

    // --- stale generation ---

    @Test fun submitResult_staleGeneration_isIgnored() {
        val r = repo()
        val gen1 = r.beginRefresh(ORIGIN)
        r.beginRefresh(ORIGIN)   // gen2 is now current

        // Submit with old gen1 — must be ignored
        r.submitResult(gen1, VALID_PANELS_JSON)

        // State should still be Refreshing (gen2 is pending), not Loaded
        assertTrue("Stale submitResult must be ignored; state should stay Refreshing",
            r.state is HaDiscovery.Refreshing)
    }

    @Test fun submitResult_staleGeneration_doesNotPersistToStore() {
        // Regression guard for the stale-save race: store.save must NOT be called when the
        // generation supplied to submitResult is no longer current.
        //
        // Old ordering: store.save happened BEFORE the second generation re-check, so a
        // beginRefresh that advanced the generation between the first check and the save would
        // still commit the stale result to persistence even though the Loaded transition was
        // correctly suppressed.
        //
        // New ordering: store.save is gated behind the second generation re-check, so a stale
        // call performs neither the save nor the transition.
        val store = FakeHaStore()
        val r = repo(store)

        val gen1 = r.beginRefresh(ORIGIN)
        r.beginRefresh(ORIGIN)   // gen2 is now current; gen1 is stale

        // Submit with stale gen1
        r.submitResult(gen1, VALID_PANELS_JSON)

        // State must remain Refreshing — not Loaded
        assertTrue(
            "Stale submitResult must not transition to Loaded; state should stay Refreshing",
            r.state is HaDiscovery.Refreshing
        )
        // store.save must NOT have been called for this origin
        assertNull(
            "store.save must NOT be called for a stale generation",
            store.saves[ORIGIN]
        )
    }

    @Test(timeout = 5_000) fun submitResult_saveAndTransitionAreAtomicWithGenerationGuard() {
        // Bug #2 regression — the cross-thread race is REAL: @JavascriptInterface (submitResult) runs
        // on the WebView "JavaBridge" thread while beginRefresh runs on the main thread.
        //
        // We force the exact vulnerable interleaving with two threads coordinated by latches inside a
        // store seam (TEST-ONLY, no production hook): the submitting thread (gen1) parks where it is
        // about to PERSIST, and a second thread runs beginRefresh(gen2) meanwhile.
        //
        // PRE-FIX: submitResult's store.save + Loaded transition lived OUTSIDE the second guarded
        // block, NOT serialized with the guard. So while gen1 is parked at save (already past its
        // guard, lock released), gen2's beginRefresh runs UNBLOCKED and sets Refreshing; then gen1
        // resumes and its UNCONDITIONAL transition(Loaded) overwrites gen2's state with Loaded(stale).
        // Final state = Loaded(stale) — torn.
        //
        // POST-FIX: gen1 holds the lock across guard + save + _state, so gen2's beginRefresh BLOCKS
        // until gen1 fully commits; gen2 then runs LAST and sets Refreshing. Final state = Refreshing.
        // (gen1's save is legitimate here — it was current under the lock — so we assert on the
        // observable torn-vs-atomic outcome: the final state.)
        val arrivedAtSave = java.util.concurrent.CountDownLatch(1)
        val mayPersist = java.util.concurrent.CountDownLatch(1)
        val store = object : HaStore {
            override fun load(origin: String): PersistedHa? = null
            override fun save(origin: String, dashboards: List<HaDashboard>, selectedPaths: List<String>) {
                arrivedAtSave.countDown()   // gen1 is at the persist point
                mayPersist.await()          // hold here until the test releases it
            }
        }
        val r = HomeAssistantDashboardRepository(store, synchronousPoster, FakeScheduler())

        val gen1 = r.beginRefresh(ORIGIN)
        val submitter = Thread { r.submitResult(gen1, VALID_PANELS_JSON) }.apply { start() }
        arrivedAtSave.await()   // gen1 parked at its persist point (past its first guard)

        // Second thread advances to gen2. PRE-FIX it runs to completion now; POST-FIX it blocks on the
        // lock that the parked gen1 holds.
        val refresher = Thread { r.beginRefresh(ORIGIN) }.apply { start() }
        Thread.sleep(150)       // give the refresher time to either complete (pre-fix) or block (post-fix)

        mayPersist.countDown()  // let gen1 finish persisting + transitioning
        submitter.join(2_000)
        refresher.join(2_000)
        assertTrue("threads should finish", !submitter.isAlive && !refresher.isAlive)

        // The atomic guard guarantees the newer gen2 wins: final state is Refreshing, NOT a torn
        // Loaded(stale) left over from gen1.
        assertTrue(
            "save+transition must be atomic with the guard: final state must be Refreshing (gen2 won), " +
                "not a torn Loaded(stale) from gen1. Was: ${r.state}",
            r.state is HaDiscovery.Refreshing
        )
    }

    @Test fun transition_listenerRemovedBeforePostedDeliveryRuns_doesNotReceiveIt() {
        // Bug #7 regression: a delivery is posted (deferred), the listener is removed BEFORE the
        // posted runnable runs, and it must NOT receive that already-posted delivery (which would
        // mutate an obsolete view/controller). Pre-fix the runnable captured the listener list at
        // post time, so the removed listener still got the callback.
        val poster = DeferredPoster()
        val r = HomeAssistantDashboardRepository(FakeHaStore(), poster, FakeScheduler())

        val received = mutableListOf<HaDiscovery>()
        val listener = HomeAssistantDashboardRepository.Listener { received.add(it) }
        r.addListener(listener)
        poster.runAll()   // drain the initial-state delivery
        received.clear()

        r.beginRefresh(ORIGIN)   // posts a Refreshing delivery (still queued, not run)
        r.removeListener(listener)   // removed BEFORE the queued delivery runs
        poster.runAll()          // now run the queued delivery

        assertTrue(
            "A listener removed before the posted delivery runs must not receive it",
            received.isEmpty()
        )
    }

    // --- timeout ---

    @Test fun timeout_fires_transitionsToError() {
        val scheduler = FakeScheduler()
        val r = repo(scheduler = scheduler)

        r.beginRefresh(ORIGIN)
        scheduler.fireLast()   // simulate timeout expiry

        assertTrue("Expected Error state after timeout", r.state is HaDiscovery.Error)
    }

    // --- fail ---

    @Test fun fail_withCurrentGeneration_transitionsToError() {
        val r = repo()
        val gen = r.beginRefresh(ORIGIN)
        r.fail(gen, "Network unreachable")

        assertTrue("Expected Error after fail()", r.state is HaDiscovery.Error)
        assertEquals("Network unreachable", (r.state as HaDiscovery.Error).reason)
    }

    @Test fun fail_withStaleGeneration_isIgnored() {
        val r = repo()
        val gen1 = r.beginRefresh(ORIGIN)
        r.beginRefresh(ORIGIN)   // gen2 is now current

        r.fail(gen1, "old error")

        // State must remain Refreshing
        assertTrue("Stale fail() must be ignored", r.state is HaDiscovery.Refreshing)
    }

    // --- listeners ---

    @Test fun listeners_receiveEachTransitionOnce() {
        val scheduler = FakeScheduler()
        val r = repo(scheduler = scheduler)

        val received = mutableListOf<HaDiscovery>()
        val listener = HomeAssistantDashboardRepository.Listener { received.add(it) }
        r.addListener(listener)

        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, VALID_PANELS_JSON)

        // addListener fires the current state immediately, then each transition
        // Sequence: Idle (initial), Refreshing, Loaded
        assertTrue("Listener should have received at least Refreshing and Loaded",
            received.any { it is HaDiscovery.Refreshing })
        assertTrue("Listener should have received Loaded",
            received.any { it is HaDiscovery.Loaded })

        // No duplicates: each distinct transition appears exactly once
        val refreshingCount = received.count { it is HaDiscovery.Refreshing }
        val loadedCount = received.count { it is HaDiscovery.Loaded }
        assertEquals("Refreshing delivered once", 1, refreshingCount)
        assertEquals("Loaded delivered once", 1, loadedCount)
    }

    @Test fun submitResult_enrichedPayload_loadsDashboardsAndAccount() {
        val store = FakeStore()                       // reuse the test's existing fake
        val repo = HomeAssistantDashboardRepository(store, SyncPoster(), FakeScheduler())
        val gen = repo.beginRefresh("http://ha.local:8123")
        repo.submitResult(gen, """
            {"panels":{"kitchen":{"component_name":"lovelace","title":"Kitchen","url_path":"kitchen"}},
             "dashboards":null,"user":{"name":"Jose","is_admin":true}}
        """.trimIndent())
        val state = repo.state as HaDiscovery.Loaded
        assertEquals(listOf("kitchen"), state.dashboards.map { it.urlPath })
        assertEquals("Jose", state.account?.name)
    }

    @Test fun submitResult_preservesDeliberatelyEmptySelection() {
        val store = FakeStore()
        store.seed("http://ha.local:8123",
            dashboards = listOf(HomeAssistantDashboards.HaDashboard("Kitchen", "kitchen")),
            selectedPaths = emptyList())              // user cleared everything
        val repo = HomeAssistantDashboardRepository(store, SyncPoster(), FakeScheduler())
        val gen = repo.beginRefresh("http://ha.local:8123")
        repo.submitResult(gen, """
            {"panels":{"kitchen":{"component_name":"lovelace","title":"Kitchen","url_path":"kitchen"}}}
        """.trimIndent())
        assertEquals(emptyList<String>(), store.lastSavedSelection)
    }

    @Test fun removeListener_stopsDelivery() {
        val r = repo()
        val received = mutableListOf<HaDiscovery>()
        val listener = HomeAssistantDashboardRepository.Listener { received.add(it) }

        r.addListener(listener)
        val sizeAfterAdd = received.size   // should get current state

        r.removeListener(listener)
        r.beginRefresh(ORIGIN)

        // No new deliveries after removal
        assertEquals("No new deliveries after removeListener", sizeAfterAdd, received.size)
    }
}
