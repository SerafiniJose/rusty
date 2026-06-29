package dev.rusty.app

import android.util.Log
import com.spotify.canvaz.CanvazMetaProto
import com.spotify.canvaz.CanvazProto
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections

/** One HTTP POST of a protobuf body. Seam so [CanvasRepository] is unit-testable without a network. */
fun interface CanvasHttp {
    fun post(url: String, token: String, body: ByteArray): CanvasHttpResponse
}

data class CanvasHttpResponse(val code: Int, val body: ByteArray?)

/** What [CanvasController] depends on (so it can be faked in tests). */
interface CanvasFetcher {
    fun fetch(trackId: String, token: String): CanvasFetch
}

/**
 * Fetches + parses a track's Spotify Canvas (looping MP4). Mirrors [LyricsRepository] but speaks
 * protobuf. The pure [encodeRequest]/[parse] are split from I/O so they are JVM-unit-testable.
 */
class CanvasRepository(
    private val http: CanvasHttp = defaultHttp(),
) : CanvasFetcher {

    // Process-lifetime LRU keyed by trackId. Only successful resolves (Found/None) are cached;
    // Unauthorized/Error are never cached so a later valid token / transient recovery refetches.
    private val cache = Collections.synchronizedMap(
        object : LinkedHashMap<String, CanvasResult>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CanvasResult>) = size > MAX_CACHE
        }
    )

    /** base62 [trackId] -> a protobuf `EntityCanvazRequest` body for the single track. */
    fun encodeRequest(trackId: String): ByteArray =
        CanvazProto.EntityCanvazRequest.newBuilder()
            .addEntities(
                CanvazProto.EntityCanvazRequest.Entity.newBuilder()
                    .setEntityUri(entityUri(trackId))
            )
            .build()
            .toByteArray()

    /**
     * Pure: decode an `EntityCanvazResponse` and pick the usable (non-empty URL, video type) canvas
     * for [trackId]. Matches strictly by `entity_uri` so a stray other-track canvas is never shown;
     * tolerates the endpoint omitting `entity_uri` only when there is exactly one usable canvas.
     * Any decode failure -> [CanvasResult.None].
     */
    fun parse(bytes: ByteArray, trackId: String): CanvasResult {
        val resp = try {
            CanvazProto.EntityCanvazResponse.parseFrom(bytes)
        } catch (e: Exception) {
            return CanvasResult.None
        }
        val wantUri = entityUri(trackId)
        val match = resp.canvasesList.firstOrNull { it.entityUri == wantUri && it.usable() }
            ?: resp.canvasesList.singleOrNull()?.takeIf { it.usable() && it.entityUri.isBlank() }
        return if (match != null) CanvasResult.Found(match.url) else CanvasResult.None
    }

    private fun entityUri(trackId: String) = "spotify:track:$trackId"

    private fun CanvazProto.EntityCanvazResponse.Canvaz.usable(): Boolean =
        url.isNotBlank() && when (type) {
            CanvazMetaProto.Type.VIDEO,
            CanvazMetaProto.Type.VIDEO_LOOPING,
            CanvazMetaProto.Type.VIDEO_LOOPING_RANDOM -> true
            else -> false
        }

    /** I/O: resolve + cache the Canvas for [trackId]. Never throws; failures map to [CanvasFetch]. */
    override fun fetch(trackId: String, token: String): CanvasFetch {
        cache[trackId]?.let { return CanvasFetch.Success(it) }
        val resp = try {
            http.post(ENDPOINT, token, encodeRequest(trackId))
        } catch (e: Exception) {
            try { Log.w(TAG, "canvas fetch failed: ${e.message}") } catch (ignored: Exception) {}
            return CanvasFetch.Error
        }
        return when (resp.code) {
            200 -> {
                val result = parse(resp.body ?: ByteArray(0), trackId)
                cache[trackId] = result
                CanvasFetch.Success(result)
            }
            401 -> CanvasFetch.Unauthorized
            else -> {
                try { Log.w(TAG, "canvas fetch HTTP ${resp.code} for $trackId") } catch (ignored: Exception) {}
                CanvasFetch.Error
            }
        }
    }

    companion object {
        private const val TAG = "CanvasRepository"
        private const val MAX_CACHE = 64
        private const val ENDPOINT = "https://spclient.wg.spotify.com/canvaz-cache/v0/canvases"

        /**
         * Process-wide instance so the now-playing fragment and the screensaver theme share ONE LRU
         * cache and never double-fetch the same track. Production call sites use this; tests build
         * their own `CanvasRepository(fakeHttp)`.
         */
        val shared: CanvasRepository by lazy { CanvasRepository() }

        private fun defaultHttp(): CanvasHttp = CanvasHttp { url, token, body ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("App-platform", "WebPlayer")
                    setRequestProperty("Content-Type", "application/x-protobuf")
                    setRequestProperty("Accept", "application/x-protobuf")
                    connectTimeout = 8000
                    readTimeout = 8000
                }
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val bytes = stream?.use { it.readBytes() }
                CanvasHttpResponse(code, bytes)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
