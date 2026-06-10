package dev.rusty.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Fetches + parses Spotify's color-lyrics for a track. Parsing is split from I/O so it's
 * unit-testable on the JVM. Results are cached per track id for the process lifetime.
 */
object LyricsRepository {
    private const val TAG = "LyricsRepository"
    private val cache = ConcurrentHashMap<String, LyricsResult>()

    /** Pure: maps a color-lyrics JSON body to a [LyricsResult]. */
    fun parse(json: String): LyricsResult {
        return try {
            val root = JSONObject(json)
            val lyrics = root.optJSONObject("lyrics")
                ?: return LyricsResult(LyricsKind.NONE)
            val linesJson = lyrics.optJSONArray("lines")
            val lines = ArrayList<LyricLine>(linesJson?.length() ?: 0)
            if (linesJson != null) {
                for (i in 0 until linesJson.length()) {
                    val line = linesJson.getJSONObject(i)
                    val startMs = line.optString("startTimeMs", "0").toLongOrNull() ?: 0L
                    val words = line.optString("words", "")
                    lines.add(LyricLine(startMs, words))
                }
            }
            if (lines.isEmpty()) return LyricsResult(LyricsKind.NONE)

            val colors = lyrics.optJSONObject("colors")
            fun color(key: String): Int? =
                if (colors != null && colors.has(key)) colors.optInt(key).takeIf { it != 0 } else null

            val synced = lyrics.optString("syncType") == "LINE_SYNCED"
            LyricsResult(
                kind = if (synced) LyricsKind.SYNCED else LyricsKind.UNSYNCED,
                lines = lines,
                bgColor = color("background"),
                highlightColor = color("highlightText"),
                dimColor = color("text"),
                provider = lyrics.optString("provider").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            LyricsResult(LyricsKind.ERROR)
        }
    }

    /** I/O: fetches + caches lyrics for the base62 [trackId]. 404 → NONE, other failures → ERROR. */
    fun fetch(trackId: String, token: String): LyricsResult {
        cache[trackId]?.let { return it }
        val url = "https://spclient.wg.spotify.com/color-lyrics/v2/track/$trackId" +
            "?format=json&vocalRemoval=false&market=from_token"
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("App-platform", "WebPlayer")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 8000
                readTimeout = 8000
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parse(body).also { if (it.kind != LyricsKind.ERROR) cache[trackId] = it }
                }
                404 -> LyricsResult(LyricsKind.NONE).also { cache[trackId] = it }
                else -> {
                    Log.w(TAG, "lyrics fetch HTTP $code for $trackId")
                    LyricsResult(LyricsKind.ERROR)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lyrics fetch failed: ${e.message}")
            LyricsResult(LyricsKind.ERROR)
        } finally {
            conn?.disconnect()
        }
    }
}
