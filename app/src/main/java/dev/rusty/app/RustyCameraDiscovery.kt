package dev.rusty.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * NSD glue for [RustyCameraDiscoveryPlan]: browses `_rusty._tcp` for a fixed window and turns each
 * distinct hit into a [DiscoveredRustyCamera]. Not unit-tested (no Robolectric in this project) —
 * every decision worth pinning lives in the pure [RustyCameraDiscoveryPlan], and this half is
 * verified by build + on-device acceptance, so its rules are spelled out here instead.
 *
 * ## Threading, cancellation and how long it takes
 * [scan] is a `suspend fun` that does all of its blocking work on [Dispatchers.IO] — the same shape
 * as the sibling [OnvifDiscovery] that feeds the same Add-camera dialog — so a UI coroutine may
 * call it without ever blocking the main thread, and cancelling that coroutine (the user backing
 * out of the dialog) aborts the scan within about [SLICE_MS] instead of leaving a browse and a
 * resolve running to completion.
 *
 * It returns after at most `windowMs` plus one [SLICE_MS] tick — ~3.3 s at the default window — no
 * matter how many peers answer. `windowMs` is one overall deadline, not a per-hit budget: every
 * wait inside the scan (for the next browse hit, for the resolve slot, for a resolve callback) is
 * bounded by that same deadline, and no new resolve is started once it has passed.
 *
 * ## Why resolves are serialized
 * `NsdManager` allows a single outstanding `resolveService` per client: a second one issued while
 * the first is still in flight fails immediately with [NsdManager.FAILURE_ALREADY_ACTIVE], and
 * before API 34 (`stopServiceResolution`, far above this app's minimum) there is no way to cancel
 * the first. mDNS also repeats each announcement two or three times (RFC 6762 §8.3), so even one
 * sharing device normally puts several hits in the queue. Resolving those concurrently — or
 * abandoning a slow resolve on a short local timeout and starting the next one anyway — is exactly
 * how a single slow peer empties the whole Nearby list: every later resolve bounces off the first
 * one. [tryClaimResolveSlot] is the interlock that makes that impossible; see its documentation.
 */
class RustyCameraDiscovery(private val context: Context) {

    /**
     * Browses `_rusty._tcp` for [windowMs] and returns the sharing peers that resolved inside that
     * window — this device's own advertisement excluded, and everything else
     * [RustyCameraDiscoveryPlan.fromResolved] rejects. An unavailable `NsdManager`, a browse that
     * will not start and a resolve that fails or never answers all end as a shorter list (logged),
     * never as an exception; the only thing [scan] propagates is the calling coroutine's
     * cancellation.
     *
     * A browse hit's instance name is marked seen before it is resolved, not after (see the loop
     * below), so a resolve that fails retires that name for the rest of this call — it is not
     * retried until the next [scan]. That is a deliberate trade, not an oversight: retrying a
     * failed name within the same window would reintroduce the duplicate-resolve waste the dedupe
     * exists to remove, for a peer this call already has a full window's worth of evidence is not
     * answering.
     */
    suspend fun scan(ownDeviceId: String, windowMs: Long = 3_000): List<DiscoveredRustyCamera> =
        withContext(Dispatchers.IO) {
            // Started here, before anything that can block, so the deadline below really does cap
            // the whole call and not just the browse. Elapsed-realtime, not wall-clock: this app
            // runs from boot, and a device that steps its clock (NTP/NITZ correction) right after
            // boot must not stretch or truncate the scan window — see the class doc.
            val started = SystemClock.elapsedRealtime()
            // Application context, like AndroidDiscoveryIo: the manager outlives any one dialog.
            // Wrapped because on the platform versions this app targets, this lookup is where
            // NsdManager itself is constructed, and that constructor can block on a handshake with
            // the NSD service and throw when it fails.
            val nsd = runCatching { context.applicationContext.getSystemService(NsdManager::class.java) }
                .fold(
                    onSuccess = { manager ->
                        // A null return (no exception) means this platform has no NSD service at
                        // all. Logged the same way ControlNsdAdvertiser logs it, so this case is
                        // never silently indistinguishable from "browsed and found nothing".
                        if (manager == null) Log.w(TAG, "NsdManager unavailable; skipping Rusty discovery")
                        manager
                    },
                    onFailure = { e ->
                        Log.w(TAG, "NsdManager unavailable; skipping Rusty discovery", e)
                        null
                    },
                ) ?: return@withContext emptyList()

            val results = Collections.synchronizedList(ArrayList<DiscoveredRustyCamera>())
            val queue = LinkedBlockingQueue<NsdServiceInfo>()
            // Set on the NSD callback thread, read by the scanning thread: a browse that never
            // started has nothing to wait for, so the scan should end now rather than sit out the
            // whole window.
            val browseFailed = AtomicBoolean(false)
            val listener = object : NsdManager.DiscoveryListener {
                override fun onServiceFound(service: NsdServiceInfo) { queue.offer(service) }
                override fun onServiceLost(service: NsdServiceInfo) {}
                override fun onDiscoveryStarted(t: String) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, error: Int) {
                    Log.w(TAG, "NSD browse of $t failed to start (error $error)")
                    browseFailed.set(true)
                }
                override fun onStopDiscoveryFailed(t: String, error: Int) {
                    // Expected after onStartDiscoveryFailed — the listener the finally block below
                    // stops was never adopted by the daemon. Logged anyway: silently swallowing it
                    // is what would hide a browse that failed for any other reason.
                    Log.w(TAG, "NSD browse of $t failed to stop (error $error)")
                }
            }

            val submitted = runCatching {
                nsd.discoverServices(ControlNsdPlan.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            }.onFailure { Log.w(TAG, "Failed to submit the ${ControlNsdPlan.SERVICE_TYPE} browse", it) }.isSuccess
            // Nothing was registered, so there is nothing to stop: return before the try/finally.
            if (!submitted) return@withContext emptyList()

            val deadline = started + windowMs
            val seen = HashSet<String>()
            try {
                while (true) {
                    coroutineContext.ensureActive()
                    if (browseFailed.get()) break
                    val left = deadline - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    // Sliced rather than one long poll so cancellation is noticed promptly; the
                    // deadline check above, not the poll, is what ends the scan.
                    val service = queue.poll(left.coerceAtMost(SLICE_MS), TimeUnit.MILLISECONDS) ?: continue
                    val name = service.serviceName
                    // mDNS sends each announcement two or three times (RFC 6762 §8.3), so the same
                    // instance normally arrives more than once. Resolving is the expensive,
                    // strictly serialized part of a scan, so spend it on distinct names only. Note
                    // this also means a name that fails to resolve is retired for the rest of this
                    // scan, not retried — see this method's doc for why that is the intended trade
                    // rather than an oversight.
                    if (name.isNullOrBlank() || !seen.add(name)) continue
                    resolveOne(nsd, service, ownDeviceId, results, deadline)
                }
            } finally {
                runCatching { nsd.stopServiceDiscovery(listener) }
                    .onFailure { Log.w(TAG, "Failed to stop the ${ControlNsdPlan.SERVICE_TYPE} browse", it) }
            }
            // Snapshot under the list's own monitor (the documented lock for Collections
            // .synchronizedList): a resolve callback may still be adding to it from the NSD thread.
            val found = synchronized(results) { results.toList() }
            val took = SystemClock.elapsedRealtime() - started
            Log.i(TAG, "Rusty scan: ${seen.size} advert(s), ${found.size} sharing camera(s), ${took}ms")
            found
        }

    /**
     * Resolves one browse hit and, if it turns out to be a sharing peer, adds it to [results].
     *
     * Claims the process-wide resolve slot first and only ever hands it back from the resolve's own
     * callback, so this method cannot start a resolve while another may still be outstanding. If
     * the callback has not arrived by [deadline] it stops *waiting* — it does not abandon the slot,
     * because there is no way to cancel the resolve and pretending otherwise is what produces
     * [NsdManager.FAILURE_ALREADY_ACTIVE] storms. A late callback still adds its camera to
     * [results], which the caller re-reads afterwards, so a resolve that lands between the give-up
     * and the end of the scan is not wasted.
     */
    private suspend fun resolveOne(
        nsd: NsdManager,
        service: NsdServiceInfo,
        ownDeviceId: String,
        results: MutableList<DiscoveredRustyCamera>,
        deadline: Long,
    ) {
        val ticket = claimResolveSlot(deadline) ?: run {
            Log.w(TAG, "Scan window ended before \"${service.serviceName}\" could be resolved")
            return
        }
        val done = CountDownLatch(1)
        // The slot must be released exactly once per claim, whichever thread gets there first:
        // either NSD callback, or this thread if the resolve could not even be submitted.
        val settled = AtomicBoolean(false)
        fun settle() {
            if (settled.compareAndSet(false, true)) {
                releaseResolveSlot(ticket)
                done.countDown()
            }
        }

        val issued = runCatching {
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    // Both callbacks run on NsdManager's own callback thread (its HandlerThread on
                    // the platform versions this app targets), where an uncaught exception does
                    // not just fail this resolve — it kills the whole process, taking playback,
                    // the slideshow and Home Assistant down with it. `NsdServiceInfo.getAttributes`
                    // returning null on an OEM ROM is the realistic trigger, so every callback body
                    // runs inside `runCatching` (logged, never rethrown) and `settle()` — the only
                    // thing that frees the interlock for the next resolve — runs in `finally` so it
                    // fires whether the body throws or returns normally.
                    override fun onServiceResolved(info: NsdServiceInfo) {
                        try {
                            runCatching {
                                val txt = info.attributes
                                    .mapValues { (_, v) -> v?.let { String(it, Charsets.UTF_8) } ?: "" }
                                RustyCameraDiscoveryPlan
                                    .fromResolved(info.serviceName, info.host?.hostAddress, info.port, txt, ownDeviceId)
                                    ?.let { results.add(it) }
                            }.onFailure {
                                Log.w(TAG, "Error handling resolved NSD info for \"${service.serviceName}\"", it)
                            }
                        } finally {
                            settle()
                        }
                    }

                    override fun onResolveFailed(info: NsdServiceInfo, error: Int) {
                        try {
                            // A handful of lines per scan at worst, and the only evidence there
                            // would ever be that resolves are bouncing (error 3 =
                            // FAILURE_ALREADY_ACTIVE, i.e. something else on this device still
                            // holds the single resolve slot).
                            runCatching { Log.w(TAG, "NSD resolve failed for \"${info.serviceName}\" (error $error)") }
                                .onFailure { Log.w(TAG, "Error logging an NSD resolve failure (error $error)", it) }
                        } finally {
                            settle()
                        }
                    }
                },
            )
        }.onFailure { Log.w(TAG, "Failed to submit a resolve for \"${service.serviceName}\"", it) }.isSuccess
        if (!issued) {
            settle()
            return
        }

        while (true) {
            coroutineContext.ensureActive()
            val left = deadline - SystemClock.elapsedRealtime()
            if (left <= 0) break
            if (done.await(left.coerceAtMost(SLICE_MS), TimeUnit.MILLISECONDS)) return
        }
        Log.w(TAG, "NSD resolve of \"${service.serviceName}\" outlived the scan window; leaving it to finish")
    }

    /** Waits for the resolve slot, giving up once fewer than [SLICE_MS] remain before [deadline] —
     *  not just when the deadline is already past. A claim granted with only a sliver of the
     *  window left would issue a `resolveService` this scan can never wait for, and would still be
     *  holding the slot when the *next* scan starts, degrading it too (see the class doc on
     *  [tryClaimResolveSlot]). Returns the ticket that [releaseResolveSlot] takes back, or null if
     *  the window (effectively) ran out first. */
    private suspend fun claimResolveSlot(deadline: Long): Long? {
        while (true) {
            coroutineContext.ensureActive()
            val left = deadline - SystemClock.elapsedRealtime()
            if (left < SLICE_MS) return null
            tryClaimResolveSlot(left.coerceAtMost(SLICE_MS))?.let { return it }
        }
    }

    /**
     * The process-wide "one resolve at a time" interlock, and the state it is built from.
     *
     * Held from just before `resolveService` until that resolve's own callback, never released by
     * the thread that merely stopped waiting for it — including across [scan] calls, which is what
     * makes an immediate Rescan safe. Without that, the straggler from the previous scan would
     * still be outstanding and every resolve of the new scan would bounce off it with
     * [NsdManager.FAILURE_ALREADY_ACTIVE], leaving the Nearby list empty a second time.
     *
     * [STALE_RESOLVE_MS] is the escape hatch for a resolve the framework never calls back at all —
     * a peer that leaves the network between announcing itself and being resolved is the real case,
     * and pre-API-34 `NsdManager` has no timeout of its own. Without it, one such peer would wedge
     * Rusty discovery for the life of the process; with it the slot is reclaimed and the worst that
     * can then happen is the [NsdManager.FAILURE_ALREADY_ACTIVE] this interlock exists to avoid —
     * logged, and no worse than not having the interlock at all. The ticket is what keeps that
     * honest: a callback arriving after its slot was reclaimed frees nothing.
     */
    private companion object {
        const val TAG = "RustyCameraDiscovery"

        /** Longest any single blocking wait may last, so cancellation and the overall deadline are
         *  both noticed promptly without busy-waiting. Mirrors [OnvifDiscovery]'s socket timeout. */
        const val SLICE_MS = 250L

        /** How long a resolve may stay outstanding before the slot is assumed lost and reclaimed. */
        const val STALE_RESOLVE_MS = 20_000L

        private val resolveLock = ReentrantLock()
        private val resolveFreed = resolveLock.newCondition()

        /** Ticket of the resolve currently outstanding; null when the slot is free. */
        private var resolveHolder: Long? = null
        private var resolveClaimedAt = 0L
        private var nextResolveTicket = 1L

        /** Waits up to [waitMs] for the slot; returns the claimed ticket, or null if still busy. */
        fun tryClaimResolveSlot(waitMs: Long): Long? = resolveLock.withLock {
            // Only worth waiting on a holder that could still call back; an already-stale one is
            // reclaimed below without burning the caller's slice on it first.
            if (resolveHolder != null && SystemClock.elapsedRealtime() - resolveClaimedAt < STALE_RESOLVE_MS) {
                resolveFreed.await(waitMs, TimeUnit.MILLISECONDS)
            }
            if (resolveHolder != null) {
                val heldFor = SystemClock.elapsedRealtime() - resolveClaimedAt
                if (heldFor < STALE_RESOLVE_MS) return@withLock null
                Log.w(TAG, "NSD resolve never called back after $heldFor ms; reclaiming the resolve slot")
            }
            resolveClaimedAt = SystemClock.elapsedRealtime()
            resolveHolder = nextResolveTicket++
            resolveHolder
        }

        /** Frees the slot if [ticket] still holds it. */
        fun releaseResolveSlot(ticket: Long) = resolveLock.withLock {
            if (resolveHolder == ticket) {
                resolveHolder = null
                resolveFreed.signalAll()
            }
        }
    }
}
