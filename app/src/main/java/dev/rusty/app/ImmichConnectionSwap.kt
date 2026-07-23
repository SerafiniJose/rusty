package dev.rusty.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The serialized invalidate-then-reload transaction for an Immich connection change.
 *
 * Ordering is load-bearing (see SlideshowSettingsPanel's Save handler): when only the
 * API key changed, URLs — and Coil's default cache keys — are unchanged, so a reload
 * racing the clear could re-serve images fetched with the old key. Hence the reload runs
 * strictly AFTER the invalidate. NonCancellable guards just the cache clear, so an
 * activity teardown mid-flight can't leave the old server's photos half-deleted; the
 * reload is deliberately cancellable — if the owning scope died, there is no saver left
 * to tell. Callers MUST pass a scope that outlives the settings sheet (the activity's).
 */
object ImmichConnectionSwap {
    fun launch(
        scope: CoroutineScope,
        io: CoroutineDispatcher,
        invalidate: () -> Unit,
        reload: () -> Unit,
    ): Job = scope.launch {
        withContext(io + NonCancellable) { invalidate() }
        reload()
    }
}
