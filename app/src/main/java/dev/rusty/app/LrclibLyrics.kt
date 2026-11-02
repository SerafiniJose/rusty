package dev.rusty.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lyrics fallback for tracks this receiver is not playing.
 *
 * Spotify's color-lyrics endpoint answers 403 to an OAuth user token — it wants a token minted for
 * the native client session, which by definition does not exist while the Watch face is showing
 * someone else's playback. LRCLIB is a free, unauthenticated lyrics database with time-synced LRC
 * for most catalogue tracks, so it fills that gap without pretending to be Spotify.
 *
 * Parsing is split from I/O so the LRC handling is unit-testable on the JVM.
 */
object LrclibLyrics {
    private const val TAG = "LrclibLyrics"
    private const val ENDPOINT = "https://lrclib.net/api/get"
    private const val USER_AGENT =
        "rusty-got-better/1.0 (https://github.com/Ibi-bit/rusty-got-better)"

    /**
     * Pure: maps an LRCLIB response to a [LyricsResult]. Synced LRC wins over plain text; a 404
     * body (`{"code":404,...}`) and anything unparseable map to [LyricsKind.NONE].
     */
    fun parse(json: String): LyricsResult {
        return try {
            val root = JSONObject(json)
            if (root.optInt("code", 200) == 404) return LyricsResult(LyricsKind.NONE)
            val synced = root.optString("syncedLyrics").takeIf { it.isNotBlank() }
            if (synced != null) {
                val lines = parseLrc(synced)
                if (lines.isNotEmpty()) {
                    return LyricsResult(
                        kind = LyricsKind.SYNCED,
                        lines = lines,
                        provider = "LRCLIB",
                    )
                }
            }
            val plain = root.optString("plainLyrics").takeIf { it.isNotBlank() }
                ?: return LyricsResult(LyricsKind.NONE)
            val lines = plain.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(0L, it) }
            if (lines.isEmpty()) {
                LyricsResult(LyricsKind.NONE)
            } else {
                LyricsResult(kind = LyricsKind.UNSYNCED, lines = lines, provider = "LRCLIB")
            }
        } catch (e: Exception) {
            // No log here: parse() is called from JVM unit tests where android.util.Log is a stub.
            LyricsResult(LyricsKind.ERROR)
        }
    }

    /**
     * Pure: turns LRC text into ascending [LyricLine]s. Handles `[mm:ss]`, `[mm:ss.xx]`,
     * `[mm:ss.xxx]` and `[mm:ss:xx]` stamps, several stamps on one line, and skips metadata tags
     * such as `[ar:…]` (their "timestamp" would not parse as a number).
     */
    fun parseLrc(text: String): List<LyricLine> {
        val stamp = Regex("""^\[(\d+):(\d+)(?:[.:](\d{1,3}))?]""")
        val out = ArrayList<LyricLine>()
        for (raw in text.lines()) {
            var line = raw.trim()
            val stamps = ArrayList<Long>()
            while (true) {
                val match = stamp.find(line) ?: break
                val minutes = match.groupValues[1].toLongOrNull() ?: break
                val seconds = match.groupValues[2].toLongOrNull() ?: break
                val fraction = match.groupValues[3].toLongOrNull() ?: 0L
                val fractionMs = when (match.groupValues[3].length) {
                    0 -> 0L
                    1 -> fraction * 100L
                    2 -> fraction * 10L
                    else -> fraction
                }
                stamps.add(minutes * 60_000L + seconds * 1_000L + fractionMs)
                line = line.substring(match.range.last + 1).trimStart()
            }
            if (stamps.isEmpty() || line.isBlank()) continue
            for (ms in stamps) out.add(LyricLine(ms, line))
        }
        return out.sortedBy { it.startMs }
    }

    /**
     * I/O: looks the track up by its own metadata. LRCLIB matches on artist/title/album/duration,
     * so the caller passes what the Web API already reported. Failures are [LyricsKind.NONE]
     * except a transport error, which is [LyricsKind.ERROR] (the UI wording differs).
     */
    fun fetch(artist: String, title: String, album: String?, durationMs: Long): LyricsResult {
        val seconds = if (durationMs > 0L) durationMs / 1000L else 0L
        val url = buildString {
            append(ENDPOINT)
            append("?artist_name=").append(encode(artist))
            append("&track_name=").append(encode(title))
            if (!album.isNullOrBlank()) append("&album_name=").append(encode(album))
            if (seconds > 0L) append("&duration=").append(seconds)
        }
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 8000
                readTimeout = 8000
            }
            when (val code = conn.responseCode) {
                200 -> parse(conn.inputStream.bufferedReader().use { it.readText() })
                404 -> LyricsResult(LyricsKind.NONE)
                else -> {
                    Log.w(TAG, "lookup HTTP $code")
                    LyricsResult(LyricsKind.ERROR)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "lookup failed: ${e.message}")
            LyricsResult(LyricsKind.ERROR)
        } finally {
            conn?.disconnect()
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}
