package dev.rusty.app

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache

/**
 * Dedicated Coil pipeline for Immich photos. Separate from the app-default loader so (a) requests
 * can carry the x-api-key header without leaking it onto other hosts' requests, and (b) a server or
 * API-key change can invalidate ONLY the photo caches (media cached under old credentials must not
 * be served) without dropping Spotify cover art.
 *
 * The disk cache is what makes the slideshow's "no blank panes" promise hold: the slideshow
 * controller decodes every photo (prefetch) before announcing its slide, and the theme then issues a
 * SECOND request to bind it into an ImageView. Both requests go through this one loader with the
 * same URL, so even if the memory cache evicted the bitmap in between, the bind is a local disk hit
 * rather than a network round trip. Sizing it generously (128 MB) keeps that true across a long
 * unattended run.
 */
object ImmichImages {
    @Volatile private var cached: ImageLoader? = null

    fun loader(context: Context): ImageLoader =
        cached ?: synchronized(this) {
            cached ?: build(context.applicationContext).also { cached = it }
        }

    /**
     * Clear caches after a connection change (server URL or API key), so any photo fetched after
     * that point re-authenticates and re-downloads under the new credentials rather than being
     * served from stale cache entries.
     *
     * Deliberately does NOT call [ImageLoader.shutdown]: that would cancel the loader's internal
     * `CoroutineScope`, and any in-flight `execute()`/`enqueue()` request awaiting on it — e.g. the
     * slideshow controller's prefetch, running concurrently on its own loop coroutine — would then
     * complete with a `CancellationException` that propagates up and cancels that loop.
     *
     * Also deliberately keeps the cached [ImageLoader] instance instead of nulling it: the loader
     * carries no per-server or per-credential state — it's configured only with a disk cache
     * directory and size, the API key is applied per request via the `x-api-key` header, and the
     * URL is rebuilt from the current config each time — so there is nothing about it that goes
     * stale. Nulling it would let a later [loader] call build a second [ImageLoader] pointed at the
     * same `immich_images` disk-cache directory while the old instance (and any request still
     * in flight against it) is still alive; Coil documents two live [DiskCache] instances open on
     * the same directory as an error condition that can corrupt the cache journal. Clearing the
     * memory and disk caches on the single surviving instance is by itself sufficient to guarantee
     * no stale content survives a server or key change.
     *
     * Takes a [Context] and goes through [loader] rather than clearing only an already-built
     * instance: `immich_images` outlives the process, so photos from an earlier session sit on disk
     * even in a session where the slideshow never mounted and therefore never built a loader.
     * Early-returning in that case would leave exactly the photos the caller asked to be removed.
     *
     * Performs blocking disk IO (a recursive cache-directory delete), so callers MUST invoke this
     * off the main thread — it is called whenever the user saves changed Immich connection settings
     * or turns the feature off.
     */
    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    fun invalidate(context: Context) {
        val loader = loader(context)
        loader.memoryCache?.clear()
        loader.diskCache?.clear()
    }

    private fun build(app: Context): ImageLoader =
        ImageLoader.Builder(app)
            .diskCache {
                DiskCache.Builder()
                    .directory(app.cacheDir.resolve("immich_images"))
                    .maxSizeBytes(128L * 1024 * 1024)
                    .build()
            }
            .build()
}
