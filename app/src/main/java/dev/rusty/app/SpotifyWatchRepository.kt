package dev.rusty.app

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** One track or episode currently playing on the linked Spotify account. */
data class WatchNowPlaying(
    val trackId: String?,
    val title: String,
    val artist: String,
    val album: String?,
    val artUrl: String?,
    val progressMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val deviceName: String?,
    val deviceType: String?,
    val isEpisode: Boolean,
    /** Wall-clock time the API response (or local dashboard event) was received. */
    val receivedAtMs: Long,
) {
    /**
     * Progress advanced locally between polls. Spotify's progress_ms is a snapshot; without this
     * the bar would visibly tick only once every five seconds.
     */
    fun elapsedAt(nowMs: Long): Long {
        val advanced = if (isPlaying) progressMs + (nowMs - receivedAtMs).coerceAtLeast(0L) else progressMs
        val ceiling = if (durationMs > 0L) durationMs else Long.MAX_VALUE
        return advanced.coerceIn(0L, ceiling)
    }
}

/** A transport press the Watch detail view can ask the playing device to act on. */
enum class WatchTransport(val method: String, val path: String) {
    PLAY("PUT", "/me/player/play"),
    PAUSE("PUT", "/me/player/pause"),
    NEXT("POST", "/me/player/next"),
    PREVIOUS("POST", "/me/player/previous"),
}

/**
 * Outcome of a transport call against the Web API.
 *
 * [Forbidden] is its own case on purpose: it means the linked token was minted before the
 * `user-modify-playback-state` scope was requested (or the account has no Premium), and the only
 * fix is to re-link — a generic error would leave the user retrying a button that cannot work.
 */
sealed interface TransportResult {
    data object Done : TransportResult
    data object Unauthorized : TransportResult
    data object Forbidden : TransportResult
    data object Error : TransportResult
}

sealed interface WatchResult {
    data class Playing(val track: WatchNowPlaying) : WatchResult
    data object Nothing : WatchResult

    /** The bearer token was rejected; the caller should force a refresh and retry. */
    data object Unauthorized : WatchResult

    /**
     * Spotify answered 429. `GET /me/player` has a tight budget, and a five-second loop can trip it
     * (observed in logcat after a few minutes of watching). [retryAfterMs] is the server's
     * `Retry-After` when present, so the caller can back off instead of hammering the endpoint.
     */
    data class RateLimited(val retryAfterMs: Long?) : WatchResult

    data object Error : WatchResult
}

/**
 * Reads the account's current playback from `GET /v1/me/player`, independent of whether this
 * receiver is the active Connect device. That is the point of the Watch theme: when nothing is
 * cast here, the saver still shows what the linked profile is playing elsewhere.
 *
 * Parsing is split from I/O so it can be unit-tested on the JVM. The response is a track,
 * episode, advert, or nothing at all; an advert is treated as [WatchResult.Nothing] rather than
 * showing an internal Spotify item.
 */
object SpotifyWatchRepository {
    private const val TAG = "SpotifyWatchRepository"
    private const val API_ROOT = "https://api.spotify.com/v1"
    private const val ENDPOINT = "$API_ROOT/me/player?additional_types=track,episode"

    /** Pure: maps a `GET /v1/me/player` JSON body to a [WatchResult]. */
    fun parse(json: String, nowMs: Long = System.currentTimeMillis()): WatchResult {
        return try {
            val root = JSONObject(json)
            val item = root.optJSONObject("item") ?: return WatchResult.Nothing
            val type = item.optString("type")
            if (type == "ad") return WatchResult.Nothing

            val isEpisode = type == "episode" || root.optString("currently_playing_type") == "episode"
            val title = item.optString("name").takeIf { it.isNotBlank() }
                ?: return WatchResult.Nothing
            val artist = if (isEpisode) {
                item.optJSONObject("show")?.optString("name")?.takeIf { it.isNotBlank() }
                    ?: "Podcast"
            } else {
                artists(item.optJSONArray("artists")) ?: "Unknown artist"
            }
            val album = if (isEpisode) {
                null
            } else {
                item.optJSONObject("album")?.optString("name")?.takeIf { it.isNotBlank() }
            }
            val art = if (isEpisode) {
                largestImage(item.optJSONArray("images"), item.optJSONObject("show")?.optJSONArray("images"))
            } else {
                largestImage(item.optJSONObject("album")?.optJSONArray("images"))
            }
            val device = root.optJSONObject("device")
            WatchResult.Playing(
                WatchNowPlaying(
                    trackId = item.optString("id").takeIf { it.isNotBlank() },
                    title = title,
                    artist = artist,
                    album = album,
                    artUrl = art,
                    progressMs = root.optLong("progress_ms").coerceAtLeast(0L),
                    durationMs = item.optLong("duration_ms").coerceAtLeast(0L),
                    isPlaying = root.optBoolean("is_playing"),
                    deviceName = device?.optString("name")?.takeIf { it.isNotBlank() },
                    deviceType = device?.optString("type")?.takeIf { it.isNotBlank() },
                    isEpisode = isEpisode,
                    receivedAtMs = nowMs,
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "player parse failed: ${e.message}")
            WatchResult.Error
        }
    }

    /** I/O: fetches the current playback. [WatchResult.Unauthorized] is the only retryable failure. */
    fun fetch(token: String): WatchResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            // Spotify reads progress_ms when it builds the response, i.e. somewhere between these
            // two stamps. Anchoring on the end made the local clock late by the whole round trip,
            // which showed up as lyrics highlighting behind the song; the midpoint splits it.
            val startedMs = System.currentTimeMillis()
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val midMs = startedMs + (System.currentTimeMillis() - startedMs) / 2
                    parse(body, midMs)
                }
                204 -> WatchResult.Nothing
                401 -> WatchResult.Unauthorized
                429 -> {
                    val seconds = conn.getHeaderField("Retry-After")?.trim()?.toLongOrNull()
                    Log.w(TAG, "player fetch rate limited (Retry-After=${seconds ?: "unset"}s)")
                    WatchResult.RateLimited(seconds?.times(1_000L))
                }
                else -> {
                    Log.w(TAG, "player fetch HTTP $code")
                    WatchResult.Error
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "player fetch failed: ${e.message}")
            WatchResult.Error
        } finally {
            conn?.disconnect()
        }
    }

    /** I/O: asks the account's active device to pause, resume, or skip. */
    fun transport(token: String, action: WatchTransport): TransportResult =
        call(token, action.method, API_ROOT + action.path)

    /** I/O: seeks the account's active device. Clamped by the caller, not here. */
    fun seek(token: String, positionMs: Long): TransportResult =
        call(token, "PUT", "$API_ROOT/me/player/seek?position_ms=${positionMs.coerceAtLeast(0L)}")

    /**
     * One empty-bodied Web API call. Spotify answers 204 for a completed transport command and 403
     * when the token lacks the modify scope, so the status code is the whole result.
     */
    private fun call(token: String, method: String, url: String): TransportResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Content-Length", "0")
                connectTimeout = 8000
                readTimeout = 8000
            }
            when (val code = conn.responseCode) {
                in 200..299 -> TransportResult.Done
                401 -> TransportResult.Unauthorized
                403 -> {
                    Log.w(TAG, "transport $method ${conn.url.path} forbidden; relink needed")
                    TransportResult.Forbidden
                }
                else -> {
                    Log.w(TAG, "transport $method ${conn.url.path} HTTP $code")
                    TransportResult.Error
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "transport $method failed: ${e.message}")
            TransportResult.Error
        } finally {
            conn?.disconnect()
        }
    }

    private fun artists(array: JSONArray?): String? {
        if (array == null || array.length() == 0) return null
        val names = buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return names.joinToString(", ").takeIf { it.isNotBlank() }
    }

    /** Spotify returns several sizes; take the widest so a TV backdrop is not a 64 px thumbnail. */
    private fun largestImage(vararg arrays: JSONArray?): String? {
        var bestUrl: String? = null
        var bestWidth = -1
        for (array in arrays) {
            if (array == null) continue
            for (i in 0 until array.length()) {
                val image = array.optJSONObject(i) ?: continue
                val width = image.optInt("width", 0)
                val url = image.optString("url").takeIf { it.isNotBlank() } ?: continue
                if (width > bestWidth) {
                    bestWidth = width
                    bestUrl = url
                }
            }
        }
        return bestUrl
    }
}
