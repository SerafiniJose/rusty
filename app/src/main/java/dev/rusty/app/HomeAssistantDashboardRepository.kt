package dev.rusty.app

/**
 * Observable repository for HA dashboard discovery.
 *
 * Pure JVM / Android-free: persistence ([HaStore]), main-thread dispatch ([MainPoster]), and timeout
 * scheduling ([Scheduler]) are all injected so the repository is fully unit-testable on the JVM.
 * App-level adapters (SharedPreferences + Handler) are wired in Task 15.
 *
 * ## Semantics
 *
 * - [hydrate]: loads origin-gated cache → [HaDiscovery.Loaded] immediately (no network, no generation).
 *   If no cache exists the state stays [HaDiscovery.Idle]. Cold-start chips must not blank out.
 * - [beginRefresh]: transitions to [HaDiscovery.Refreshing] and starts a timeout via [Scheduler].
 *   Returns a monotonically-increasing *generation* long that identifies this request.
 * - [submitResult]: silently dropped if the generation is stale (latest-wins guard). On current+valid
 *   JSON: parses via [HomeAssistantDashboards.parseDiscoveryOrNull]; `null` → [HaDiscovery.Error];
 *   otherwise → [HaDiscovery.Loaded] + [HaStore.save] with selection pruned via
 *   [HomeAssistantDashboards.normalizeSelection] + timeout cancelled.
 * - [fail] / timeout → [HaDiscovery.Error].
 * - Listeners receive each transition exactly once, on the poster thread.
 */

/** A handle that can cancel a previously scheduled action. */
fun interface Cancellable { fun cancel() }

/** Delays and then fires a [Runnable]; the concrete implementation will use a Handler. */
fun interface Scheduler { fun schedule(delayMs: Long, action: Runnable): Cancellable }

/**
 * Discriminated union of discovery states:
 * [Idle] → [Refreshing] → [Loaded] or [Error]; [hydrate] can skip straight to [Loaded].
 */
sealed interface HaDiscovery {
    object Idle : HaDiscovery
    object Refreshing : HaDiscovery
    data class Loaded(
        val dashboards: List<HomeAssistantDashboards.HaDashboard>,
        val account: HomeAssistantDashboards.HaAccount? = null,
    ) : HaDiscovery
    data class Error(val reason: String) : HaDiscovery
}

/**
 * Origin-gated persistent snapshot: the dashboard list and the user's pruned selection paths.
 * [HaStore.load] returns `null` when nothing is cached for the requested origin.
 */
data class PersistedHa(
    val dashboards: List<HomeAssistantDashboards.HaDashboard>,
    val selectedPaths: List<String>,
)

/** Injected persistence — mirrors the prefs written by [HomeAssistantFragment.HaBridge]. */
interface HaStore {
    /** Returns `null` if no cache exists for this origin (origin-gated). */
    fun load(origin: String): PersistedHa?

    /** Persists the latest list and the pruned selection for [origin]. */
    fun save(origin: String, dashboards: List<HomeAssistantDashboards.HaDashboard>, selectedPaths: List<String>)
}

/**
 * Observable repository for HA dashboard discovery.
 *
 * @param store   Injected persistence (SharedPreferences adapter in production, fake in tests).
 * @param poster  Posts runnables on the main thread (Handler in production, synchronous in tests).
 * @param scheduler  Schedules the timeout runnable (Handler.postDelayed in production, fake in tests).
 */
class HomeAssistantDashboardRepository(
    private val store: HaStore,
    private val poster: MainPoster,
    private val scheduler: Scheduler,
) {
    fun interface Listener { fun onState(state: HaDiscovery) }

    private val lock = Any()

    // --- mutable state, all guarded by `lock` ---
    private var _state: HaDiscovery = HaDiscovery.Idle
    private var currentGeneration: Long = 0L
    private var currentOrigin: String? = null
    private var pendingTimeout: Cancellable? = null
    private val listeners = ArrayList<Listener>()

    /** Current discovery state. May be read from any thread. */
    val state: HaDiscovery
        get() = synchronized(lock) { _state }

    /**
     * Loads the origin-gated cache and moves to [HaDiscovery.Loaded] immediately if a cache exists,
     * or stays [HaDiscovery.Idle] if not. Does NOT start a network request or increment the generation.
     */
    fun hydrate(origin: String) {
        val persisted = store.load(origin)
        val next: HaDiscovery = if (persisted != null) {
            HaDiscovery.Loaded(persisted.dashboards)
        } else {
            HaDiscovery.Idle
        }
        transition(next) { currentOrigin = origin }
    }

    /**
     * Moves to [HaDiscovery.Refreshing] and schedules a timeout. Returns the request *generation*
     * that the caller must echo back via [submitResult] or [fail].
     */
    fun beginRefresh(origin: String): Long {
        val gen: Long
        synchronized(lock) {
            pendingTimeout?.cancel()
            pendingTimeout = null
            currentGeneration += 1
            gen = currentGeneration
            currentOrigin = origin
        }
        val timeoutRunnable = Runnable {
            fail(gen, "No response from Home Assistant. Check your network connection.")
        }
        val cancellable = scheduler.schedule(TIMEOUT_MS, timeoutRunnable)
        synchronized(lock) {
            // Only store the cancellable if the generation is still ours
            if (currentGeneration == gen) pendingTimeout = cancellable
            else cancellable.cancel()
        }
        transition(HaDiscovery.Refreshing) {}
        return gen
    }

    /**
     * Handles the enriched `{panels, dashboards, user}` JSON response for a given [generation]. Stale
     * generations are silently ignored (latest-wins). On success, parses via
     * [HomeAssistantDashboards.parseDiscoveryOrNull], saves to [store] with a pruned selection, cancels
     * the timeout, and transitions to [HaDiscovery.Loaded]. Malformed JSON (null from parser)
     * transitions to [HaDiscovery.Error].
     */
    fun submitResult(generation: Long, rawJson: String) {
        val origin: String?
        synchronized(lock) {
            if (generation != currentGeneration) return
            origin = currentOrigin
        }
        // JSON parsing (potentially slow) intentionally happens OUTSIDE the lock so that it does
        // not block concurrent calls to beginRefresh, addListener, or removeListener. A second
        // generation guard below ensures we don't commit a result that was superseded while we
        // were parsing.
        val result = HomeAssistantDashboards.parseDiscoveryOrNull(rawJson)
        if (result == null) {
            fail(generation, "Could not parse dashboard response.")
            return
        }
        val parsed = result.dashboards
        val available = listOf(HomeAssistantDashboards.OVERVIEW) + parsed
        val existing: PersistedHa? = origin?.let { store.load(it) }
        val existingPaths = existing?.selectedPaths ?: listOf(HomeAssistantDashboards.OVERVIEW_PATH)
        val pruned = HomeAssistantDashboards.normalizeSelection(existingPaths, available)
        val next = HaDiscovery.Loaded(parsed, result.account)
        // store.save, _state assignment, and pendingTimeout capture are ALL inside ONE lock block so
        // that a racing beginRefresh cannot interleave between the guard re-check and the persist/
        // transition steps. This atomicity guarantee is what prevents the stale-save race described
        // in submitResult_saveAndTransitionAreAtomicWithGenerationGuard.
        val cancelled = synchronized(lock) {
            if (generation != currentGeneration) return
            if (origin != null) store.save(origin, parsed, pruned)
            _state = next
            val c = pendingTimeout
            pendingTimeout = null
            c
        }
        cancelled?.cancel()
        deliver(next)
    }

    /**
     * Records a failure for [generation]. Stale generations are silently ignored. Transitions to
     * [HaDiscovery.Error] and cancels any pending timeout.
     */
    fun fail(generation: Long, reason: String) {
        // Same atomicity as submitResult: the generation guard, the timeout capture, and the Error
        // state-set all happen inside ONE lock so a concurrent beginRefresh cannot interleave between
        // the guard and the transition. A stale generation performs neither.
        val next = HaDiscovery.Error(reason)
        val cancelled = synchronized(lock) {
            if (generation != currentGeneration) return   // stale
            _state = next
            val c = pendingTimeout
            pendingTimeout = null
            c
        }
        cancelled?.cancel()
        deliver(next)
    }

    /**
     * Resets the repository to [HaDiscovery.Idle], cancelling any in-flight refresh.
     *
     * Use this when the HA base URL changes to a different origin so that stale [HaDiscovery.Loaded]
     * dashboards from the old server are not returned to listeners. The next [hydrate] or [beginRefresh]
     * call will start fresh from the new origin.
     */
    fun reset() {
        val cancelled = synchronized(lock) {
            val c = pendingTimeout
            pendingTimeout = null
            currentGeneration += 1   // invalidate any in-flight generation
            currentOrigin = null
            c
        }
        cancelled?.cancel()
        transition(HaDiscovery.Idle) {}
    }

    fun addListener(l: Listener) {
        val current: HaDiscovery
        synchronized(lock) {
            listeners.add(l)
            current = _state
        }
        // Deliver the current state to the new listener via the poster
        poster.post(Runnable { l.onState(current) })
    }

    fun removeListener(l: Listener) {
        synchronized(lock) { listeners.remove(l) }
    }

    // --- internals ---

    /**
     * Applies [mutate] under the lock, records the new state, then posts listener delivery off-lock.
     * Used by transitions ([hydrate], [beginRefresh], [reset]) whose state-set is not also gated by a
     * generation re-check. [submitResult] / [fail] instead set `_state` directly inside their atomic
     * guard block and call [deliver] themselves.
     */
    private fun transition(next: HaDiscovery, mutate: () -> Unit) {
        synchronized(lock) {
            mutate()
            _state = next
        }
        deliver(next)
    }

    /**
     * Posts [next] to listeners off-lock. The posted runnable re-reads the CURRENT listener set under
     * the lock at delivery time (mirroring [ReceiverStateStore]'s drain), so a listener removed
     * between the post and the run does NOT receive an already-posted delivery that would mutate
     * obsolete view/controller objects.
     */
    private fun deliver(next: HaDiscovery) {
        poster.post(Runnable {
            val targets: List<Listener> = synchronized(lock) { ArrayList(listeners) }
            for (l in targets) l.onState(next)
        })
    }

    companion object {
        /** How long to wait for a `submitResult` before firing a timeout error. */
        const val TIMEOUT_MS = 10_000L
    }
}
