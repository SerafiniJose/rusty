package dev.rusty.app

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * [ControlRuntime.immichList] implementation shared by all three `/api/immich/{kind}` routes.
 * Wraps [ImmichRepository]'s fetches with an in-memory, per-`kind` TTL cache so the control page
 * (which polls its filter checklists on every load) doesn't re-run the expensive people crawl
 * (up to [ImmichRepository.PEOPLE_PAGE_CAP] pages) on every page-load — see the design doc.
 *
 * Pure-JVM by construction: [configProvider], [fetcher], [clock] and [fetchExecutor] are all
 * injected rather than this class reaching for `SlideshowSettings`/`ImmichRepository`/
 * `System.currentTimeMillis()` itself, so it is unit-testable with a fake clock and no Android
 * runtime. The real wiring (Task 9) supplies `SlideshowSettings::config` partially applied to
 * prefs/secrets, and `ImmichRepository`'s `fetchAlbums`/`fetchPeople`/`fetchTags` selected by
 * `kind`.
 *
 * Only [ImmichResult.Ok] responses are cached. Errors (unauthorized, unreachable) are
 * deliberately NOT cached: pinning a transient outage or a just-fixed API key behind a 60s TTL
 * would make Save-then-retry on the settings page feel broken for up to a minute.
 *
 * ## Why this endpoint is time-boxed, and Home Assistant's is not
 * The remote-control server answers every route from ONE small worker pool
 * ([ControlHttpServer]). Every other route is microseconds of work against in-memory state; this
 * one is an unbounded upstream crawl. Against a slow or half-dead Immich server, a single
 * `GET /api/immich/people` can occupy a worker for MINUTES — [ImmichRepository.PEOPLE_PAGE_CAP]
 * pages at up to 8 s connect + 8 s read each — and one control-page load fires all three list
 * fetches at once. Left unbounded, two open browser tabs would hold every worker and Home
 * Assistant's `/api/state` poll (which has its own bounded timeout and maps a timeout to
 * `UpdateFailed`) would start marking the whole device unavailable. The 60 s cache is no defence:
 * it only helps AFTER a crawl has succeeded once, and a dead server never gets there.
 *
 * Two mechanisms close that, chosen over the alternative of routing these three paths to a
 * separate connection pool (which would have meant teaching the accept loop to parse before it
 * dispatches — a much larger change to shared, device-only-verifiable glue for the same outcome):
 *
 *  1. **A wall-clock budget** ([budgetMs], default [DEFAULT_BUDGET_MS]). The fetch runs on
 *     [fetchExecutor] and the HTTP worker waits at most this long before answering
 *     [ControlImmichResult.Unreachable] — which is honest, since a list that takes 20 s is
 *     unusable to the page either way. The crawl is NOT cancelled: `HttpURLConnection` does not
 *     answer `Thread.interrupt` promptly anyway, and letting it finish means it still populates
 *     the cache, so a genuinely slow first load turns into a fast second one.
 *  2. **Per-kind de-duplication** ([inFlight]). A crawl already running for a `kind` is joined by
 *     later callers instead of started again, so N open tabs still produce at most three
 *     concurrent upstream crawls — never N×3.
 *
 * Together those bound the pool's exposure to three workers for [budgetMs], which is why
 * [ControlHttpServer] carries a pool big enough to absorb that and still answer `/api/state`.
 *
 * Thread-safety: called from HTTP worker-pool threads, so [cache] and [inFlight] are each guarded
 * by `synchronized`. The (slow, blocking) fetch runs OUTSIDE both locks — holding one across a
 * network call would serialize all three kinds behind whichever is mid-crawl.
 */
class ControlImmichProxy(
    private val configProvider: () -> ImmichConfig?,
    private val fetcher: (String, ImmichConfig) -> ImmichResult<List<ImmichPickerItem>>,
    private val clock: () -> Long,
    private val budgetMs: Long = DEFAULT_BUDGET_MS,
    /** Where the blocking upstream crawl runs, so the HTTP worker can stop waiting on it. Cached
     *  (not fixed) threads with a 60 s idle timeout: at most three are ever busy at once, and an
     *  unused proxy — the normal case, since Immich is optional — costs no threads at all. Daemon
     *  so it can never hold the process up. */
    private val fetchExecutor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "control-immich").apply { isDaemon = true }
    },
) {
    /** kind -> (fetchedAtMs, items). Accessed only under `synchronized(cache)`. */
    private val cache = mutableMapOf<String, Pair<Long, List<ImmichPickerItem>>>()

    /** kind -> the crawl currently running for it, so concurrent callers join rather than pile on.
     *  Accessed only under `synchronized(inFlight)`. */
    private val inFlight = mutableMapOf<String, Future<ImmichResult<List<ImmichPickerItem>>>>()

    fun list(kind: String): ControlImmichResult {
        val cfg = configProvider() ?: return ControlImmichResult.NotConfigured

        val now = clock()
        cachedIfFresh(kind, now)?.let { return ControlImmichResult.Ok(it) }

        val future = try {
            crawlFor(kind, cfg)
        } catch (e: RejectedExecutionException) {
            // Only reachable if the executor was shut down under us; a request must degrade, not
            // throw into an HTTP worker.
            return ControlImmichResult.Unreachable
        }

        val result = try {
            future.get(budgetMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            // Deliberately left running: it still fills the cache for the next caller (see class
            // doc). This worker, however, is released now.
            return ControlImmichResult.Unreachable
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return ControlImmichResult.Unreachable
        } catch (e: Exception) {
            // ExecutionException, CancellationException — the crawl task already maps every
            // failure it can see, so anything arriving here is unforeseen and still degrades.
            return ControlImmichResult.Unreachable
        }

        return when (result) {
            is ImmichResult.Ok -> ControlImmichResult.Ok(result.value)
            is ImmichResult.Error -> when (result.kind) {
                ImmichErrorKind.AUTH -> ControlImmichResult.Unauthorized
                else -> ControlImmichResult.Unreachable
            }
        }
    }

    /**
     * The [Future] for [kind]'s crawl — the one already running if there is one, otherwise a newly
     * submitted one. The task itself owns caching a successful result and clearing its own
     * [inFlight] slot, so a caller that gave up on the budget still leaves the cache warmed.
     */
    private fun crawlFor(kind: String, cfg: ImmichConfig): Future<ImmichResult<List<ImmichPickerItem>>> =
        synchronized(inFlight) {
            inFlight[kind] ?: fetchExecutor.submit<ImmichResult<List<ImmichPickerItem>>> {
                try {
                    val fetched = try {
                        fetcher(kind, cfg)
                    } catch (e: Exception) {
                        // fetcher is documented as never-throwing (mirrors ImmichRepository), but a
                        // misbehaving implementation must degrade to Unreachable, not kill a thread.
                        ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
                    }
                    if (fetched is ImmichResult.Ok) {
                        synchronized(cache) { cache[kind] = clock() to fetched.value }
                    }
                    fetched
                } finally {
                    synchronized(inFlight) { inFlight.remove(kind) }
                }
            }.also { inFlight[kind] = it }
        }

    /** Returns the cached items for [kind] if present and fetched less than [TTL_MS] ago,
     *  otherwise null. The boundary is exclusive: an entry fetched exactly [TTL_MS] ago is
     *  treated as expired (re-fetches), not fresh. */
    private fun cachedIfFresh(kind: String, now: Long): List<ImmichPickerItem>? {
        val (fetchedAt, items) = synchronized(cache) { cache[kind] } ?: return null
        return if (now - fetchedAt < TTL_MS) items else null
    }

    companion object {
        private const val TTL_MS = 60_000L

        /**
         * How long an HTTP worker will wait for one Immich list before answering "unreachable".
         * ~20 s: comfortably longer than any healthy crawl (the people crawl is a handful of pages
         * against a real server) and short enough that a dead server cannot hold a worker long
         * enough for Home Assistant's poll to trip.
         */
        const val DEFAULT_BUDGET_MS = 20_000L
    }
}
