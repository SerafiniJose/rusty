package dev.rusty.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Observes [ReceiverStateStore] snapshots and asynchronously resolves the playing track's Canvas,
 * exposing a [CanvasState] both the now-playing screen and the screensaver consume.
 *
 * The reaction key is the [trackId] gated by BOTH the toggle ([isEnabled]) AND active playback
 * (`status == "Playing"`), so pause/stop/OFF and toggle-off drop the key and release the video.
 * Debounce + generation guard are one mechanism: each new key cancels the in-flight resolve and
 * starts a fresh debounced one; a captured-key re-check drops any straggler result. The screensaver
 * theme passes `isEnabled = { true }` (selecting the theme is the opt-in).
 */
class CanvasController(
    private val store: ReceiverStateStore,
    private val fetcher: CanvasFetcher,
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
    private val isEnabled: () -> Boolean,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val debounceMs: Long = 250L,
) {
    fun interface Listener {
        fun onCanvasState(state: CanvasState)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile var state: CanvasState = CanvasState.None
        private set

    private var currentKey: String? = null
    private var job: Job? = null
    private var started = false

    private val storeListener = ReceiverStateStore.Listener { snapshot ->
        onSnapshot(snapshot.state)
    }

    fun addListener(l: Listener) {
        listeners.add(l)
        l.onCanvasState(state) // current-state delivery so a late subscriber catches up
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    /** Begin observing. Idempotent. The store delivers the current snapshot on subscribe. */
    fun start() {
        if (started) return
        started = true
        store.addListener(storeListener)
    }

    /** Stop observing, cancel any in-flight resolve, and reset to None. Idempotent. */
    fun stop() {
        if (!started) return
        started = false
        store.removeListener(storeListener)
        job?.cancel()
        job = null
        currentKey = null
        publish(CanvasState.None)
    }

    /** Re-derive the key from the latest snapshot — call after a toggle change (no store event). */
    fun reevaluate() {
        if (started) onSnapshot(store.snapshot.state)
    }

    private fun onSnapshot(state: ReceiverDashboardState) {
        val playing = state.status == STATUS_PLAYING
        val want = if (isEnabled() && playing) state.trackId?.takeIf { it.isNotBlank() } else null
        apply(want)
    }

    private fun apply(wantTrackId: String?) {
        if (wantTrackId == currentKey) return
        currentKey = wantTrackId
        job?.cancel()
        if (wantTrackId == null) {
            publish(CanvasState.None)
            return
        }
        publish(CanvasState.Loading)
        job = scope.launch {
            delay(debounceMs)
            if (!isActive) return@launch
            val resolved = resolve(wantTrackId)
            if (!isActive || wantTrackId != currentKey) return@launch
            publish(resolved)
        }
    }

    /** Fetch with a single 401 refresh+retry. Runs blocking I/O on [ioDispatcher]. */
    private suspend fun resolve(trackId: String): CanvasState {
        val token = tokenProvider(false) ?: return CanvasState.None
        var fetch = withContext(ioDispatcher) { fetcher.fetch(trackId, token) }
        if (fetch is CanvasFetch.Unauthorized) {
            val fresh = tokenProvider(true) ?: return CanvasState.None
            fetch = withContext(ioDispatcher) { fetcher.fetch(trackId, fresh) }
        }
        return when (fetch) {
            is CanvasFetch.Success -> when (val r = fetch.result) {
                is CanvasResult.Found -> CanvasState.Found(r.url)
                CanvasResult.None -> CanvasState.None
            }
            CanvasFetch.Unauthorized, CanvasFetch.Error -> CanvasState.None
        }
    }

    private fun publish(next: CanvasState) {
        state = next
        listeners.forEach { it.onCanvasState(next) }
    }

    private companion object {
        // The dashboard status string for active playback (see ReceiverDashboardStatusEvent /
        // ReceiverStateStoreTest, which asserts status == "Playing" for a PLAYING playback event).
        const val STATUS_PLAYING = "Playing"
    }
}
