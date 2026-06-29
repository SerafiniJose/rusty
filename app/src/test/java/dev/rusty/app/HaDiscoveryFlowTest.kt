package dev.rusty.app

import dev.rusty.app.HomeAssistantDashboards.HaDashboard
import dev.rusty.app.HomeAssistantDashboards.OVERVIEW_PATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM test — zero android.* imports.
 *
 * Validates the *ordered sequence of [HaDiscovery] states* that a UI consumer (chips bar,
 * checklist) observes as the repository moves through its lifecycle. The focus here is on
 * what the Listener sees rather than the internal state-machine mechanics covered by
 * [HomeAssistantDashboardRepositoryTest].
 *
 * Infrastructure reused from HomeAssistantDashboardRepositoryTest.kt:
 *   [FakeScheduler], [FakeHaStore], [synchronousPoster] — same file, same package.
 *
 * Each test builds a small [FakeListener] that records every [HaDiscovery] delivered via
 * [HomeAssistantDashboardRepository.addListener], then drives the repository through a scenario
 * and asserts the exact delivery sequence.
 */

// ---------------------------------------------------------------------------
// Shared helpers local to this file
// ---------------------------------------------------------------------------

private val PANELS_JSON = """
    {"panels":{
      "kitchen": {"component_name":"lovelace","title":"Kitchen","icon":"mdi:fridge","url_path":"kitchen"},
      "energy":  {"component_name":"lovelace","title":"Energy",  "icon":null,       "url_path":"energy"}
    }}
""".trimIndent()

private const val ORIGIN = "http://ha.local:8123"
private const val GARBAGE = "{ this is not valid JSON"

private fun buildRepo(
    store: FakeHaStore = FakeHaStore(),
    scheduler: FakeScheduler = FakeScheduler(),
): HomeAssistantDashboardRepository =
    HomeAssistantDashboardRepository(store, synchronousPoster, scheduler)

/** Collects every state delivered to a listener in order. */
private class FakeListener : HomeAssistantDashboardRepository.Listener {
    val received = mutableListOf<HaDiscovery>()
    override fun onState(state: HaDiscovery) { received.add(state) }
}

// ---------------------------------------------------------------------------
// Flow tests
// ---------------------------------------------------------------------------

class HaDiscoveryFlowTest {

    /**
     * Cold start (no cache): consumer sees Idle immediately after addListener, nothing more until
     * beginRefresh is called.
     */
    @Test
    fun coldStart_noCache_consumerSeesIdle() {
        val r = buildRepo()
        val listener = FakeListener()
        r.addListener(listener)

        // addListener fires the current state synchronously (via synchronousPoster).
        assertEquals("Expected exactly one delivery: Idle", 1, listener.received.size)
        assertTrue(listener.received[0] is HaDiscovery.Idle)
    }

    /**
     * Warm start (cache present): hydrate → consumer sees Loaded immediately; no Refreshing in between.
     */
    @Test
    fun warmStart_withCache_consumerSeesLoadedImmediately() {
        val dashboards = listOf(HaDashboard("Kitchen", "kitchen"))
        val store = FakeHaStore().apply {
            initial = mapOf(ORIGIN to PersistedHa(dashboards, listOf(OVERVIEW_PATH)))
        }
        val r = buildRepo(store = store)
        val listener = FakeListener()
        r.addListener(listener)

        r.hydrate(ORIGIN)

        // addListener fires Idle (initial), then hydrate transitions to Loaded.
        val types = listener.received.map { it::class.simpleName }
        assertTrue("Sequence must contain Loaded: $types", listener.received.any { it is HaDiscovery.Loaded })
        // Must NOT contain Refreshing (hydrate goes directly to Loaded, no network hop).
        assertTrue(
            "Sequence must NOT contain Refreshing for a cache-hit hydrate: $types",
            listener.received.none { it is HaDiscovery.Refreshing },
        )
    }

    /**
     * Happy path: Idle → Refreshing → Loaded.
     *
     * The full discovery flow a UI consumer observes on first launch with no cache: the listener
     * joins in Idle, a WebView page-load triggers beginRefresh → Refreshing, then the JS bridge
     * delivers the panel JSON → submitResult → Loaded.
     */
    @Test
    fun happyPath_idleToRefreshingToLoaded_orderedSequence() {
        val r = buildRepo()
        val listener = FakeListener()
        r.addListener(listener)

        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, PANELS_JSON)

        val received = listener.received
        // Expected minimum sequence: [Idle, Refreshing, Loaded]
        assertTrue("Must have at least 3 deliveries, got: $received", received.size >= 3)
        assertTrue("First delivery must be Idle",        received[0] is HaDiscovery.Idle)
        assertTrue("Second delivery must be Refreshing", received[1] is HaDiscovery.Refreshing)
        assertTrue("Third delivery must be Loaded",      received[2] is HaDiscovery.Loaded)

        // Loaded state must carry the parsed dashboards.
        val loaded = received[2] as HaDiscovery.Loaded
        val paths = loaded.dashboards.map { it.urlPath }.toSet()
        assertEquals(setOf("kitchen", "energy"), paths)
    }

    /**
     * Error path (malformed JSON): Idle → Refreshing → Error.
     *
     * A Listener watching a repository that receives un-parseable panel JSON must observe
     * the Error terminal state, never Loaded.
     */
    @Test
    fun malformedJson_consumerSeesError_neverLoaded() {
        val r = buildRepo()
        val listener = FakeListener()
        r.addListener(listener)

        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, GARBAGE)

        val received = listener.received
        assertTrue("Sequence must contain Error", received.any { it is HaDiscovery.Error })
        assertTrue("Sequence must NOT contain Loaded", received.none { it is HaDiscovery.Loaded })
    }

    /**
     * Timeout path: Idle → Refreshing → Error (via scheduler timeout, not submitResult).
     *
     * The scheduler fires TIMEOUT_MS after beginRefresh; the consumer must see Error without any
     * submitResult call having been made.
     */
    @Test
    fun timeout_consumerSeesError_afterSchedulerFires() {
        val scheduler = FakeScheduler()
        val r = buildRepo(scheduler = scheduler)
        val listener = FakeListener()
        r.addListener(listener)

        r.beginRefresh(ORIGIN)
        // Simulate the timeout expiry.
        scheduler.fireLast()

        val received = listener.received
        assertTrue("Sequence must contain Refreshing before timeout", received.any { it is HaDiscovery.Refreshing })
        assertTrue("Sequence must end with Error after timeout fires", received.last() is HaDiscovery.Error)
    }

    /**
     * Stale generation is invisible to consumers.
     *
     * When a second beginRefresh supersedes the first, submitResult for the stale first generation
     * must not deliver any state transition to the listener; the listener stays at Refreshing
     * (the current live generation's state).
     */
    @Test
    fun staleGeneration_noStateDeliveredToConsumer() {
        val r = buildRepo()
        val listener = FakeListener()
        r.addListener(listener)

        val gen1 = r.beginRefresh(ORIGIN)
        r.beginRefresh(ORIGIN)   // gen2 is now current

        val sizeBeforeStaleSubmit = listener.received.size

        r.submitResult(gen1, PANELS_JSON) // stale — must be silently dropped

        // No new delivery should have been made for the stale gen1 result.
        assertEquals(
            "Stale submitResult must not deliver any new state to listeners",
            sizeBeforeStaleSubmit,
            listener.received.size,
        )
        // State must remain Refreshing — listener's last delivery was Refreshing (from gen2 beginRefresh).
        assertTrue(
            "Consumer's last seen state must still be Refreshing",
            listener.received.last() is HaDiscovery.Refreshing,
        )
    }

    /**
     * removeListener stops delivery.
     *
     * A consumer that unsubscribes (e.g. a chip bar fragment that goes off-screen) must receive
     * no further transitions after removeListener is called.
     */
    @Test
    fun removeListener_stopsDeliveryToConsumer() {
        val r = buildRepo()
        val listener = FakeListener()
        r.addListener(listener)

        // Get the initial delivery count (Idle).
        val sizeAfterAdd = listener.received.size

        r.removeListener(listener)

        // These transitions must not be delivered.
        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, PANELS_JSON)

        assertEquals(
            "No deliveries must occur after removeListener",
            sizeAfterAdd,
            listener.received.size,
        )
    }

    /**
     * Two independent listeners each receive the full ordered sequence.
     *
     * A chips-bar listener and a checklist listener both join before the flow starts; each must
     * receive the same Idle → Refreshing → Loaded sequence independently.
     */
    @Test
    fun twoListeners_eachReceiveFullSequence() {
        val r = buildRepo()
        val chipsListener = FakeListener()
        val checklistListener = FakeListener()
        r.addListener(chipsListener)
        r.addListener(checklistListener)

        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, PANELS_JSON)

        for ((name, lst) in listOf("chips" to chipsListener, "checklist" to checklistListener)) {
            val received = lst.received
            assertTrue("$name: must contain Idle",       received.any { it is HaDiscovery.Idle })
            assertTrue("$name: must contain Refreshing", received.any { it is HaDiscovery.Refreshing })
            assertTrue("$name: must contain Loaded",     received.any { it is HaDiscovery.Loaded })
            // Each distinct state must appear exactly once for this clean flow.
            assertEquals("$name: Refreshing delivered once", 1, received.count { it is HaDiscovery.Refreshing })
            assertEquals("$name: Loaded delivered once",     1, received.count { it is HaDiscovery.Loaded })
        }
    }

    /**
     * Late-joining listener immediately receives current state (no replay of prior transitions).
     *
     * A UI component that binds after the repository has already advanced to Loaded must
     * receive Loaded immediately on addListener (not Idle/Refreshing first).
     */
    @Test
    fun lateListener_receivesCurrentStateImmediately() {
        val r = buildRepo()

        // Drive to Loaded before the listener joins.
        val gen = r.beginRefresh(ORIGIN)
        r.submitResult(gen, PANELS_JSON)
        assertTrue("Pre-condition: repo must be Loaded", r.state is HaDiscovery.Loaded)

        // Late join — must get Loaded as the first (and only) delivery.
        val lateListener = FakeListener()
        r.addListener(lateListener)

        assertEquals("Late listener must receive exactly one delivery", 1, lateListener.received.size)
        assertTrue(
            "Late listener's only delivery must be Loaded (current state)",
            lateListener.received[0] is HaDiscovery.Loaded,
        )
    }
}
