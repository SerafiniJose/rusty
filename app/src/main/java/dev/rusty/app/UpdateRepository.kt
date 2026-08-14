package dev.rusty.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer release than the running build. Parsing and version
 * comparison are pure (split from I/O) so they're unit-testable on the JVM, mirroring
 * [LyricsRepository]. The latest [check] result is cached for [CACHE_TTL_MS] so reopening
 * the About sheet is instant, while a long-running receiver (weeks between restarts)
 * still notices new releases — the remote-control page's update check goes through
 * [check] too and must not be pinned to a boot-time answer.
 */
object UpdateRepository {
    private const val TAG = "UpdateRepository"

    /** Public repo whose Releases drive in-app update prompts. */
    const val RELEASES_URL = "https://github.com/SerafiniJose/rusty/releases/latest"
    const val REPO_URL = "https://github.com/SerafiniJose/rusty"
    private const val API_URL =
        "https://api.github.com/repos/SerafiniJose/rusty/releases/latest"

    enum class UpdateStatus { UP_TO_DATE, UPDATE_AVAILABLE, ERROR }

    /** [apkUrl]: direct download URL of the release's APK asset, or null when the release
     *  ships none (installers must then fall back to opening [releaseUrl]). */
    data class ReleaseInfo(
        val versionName: String,
        val notes: String,
        val releaseUrl: String,
        val apkUrl: String? = null
    )

    data class UpdateCheck(
        val status: UpdateStatus,
        val currentVersion: String,
        val latest: ReleaseInfo?
    )

    const val CACHE_TTL_MS = 15 * 60 * 1000L

    @Volatile
    private var cached: Pair<Long, UpdateCheck>? = null

    /** Pure: whether a check cached at [cachedAtMs] is still current at [nowMs]. A cachedAt
     *  in the future (wall-clock reset) counts as stale rather than fresh-forever. */
    fun isCacheFresh(cachedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= cachedAtMs && nowMs - cachedAtMs < CACHE_TTL_MS

    /** Pure: maps a GitHub `releases/latest` JSON body to a [ReleaseInfo], or null if unusable. */
    fun parseRelease(json: String): ReleaseInfo? {
        return try {
            val root = JSONObject(json)
            val tag = root.optString("tag_name").trim()
            if (tag.isEmpty()) return null
            // Display without the conventional leading `v` (e.g. "v1.2.0" → "1.2.0").
            val version = tag.removePrefix("v").removePrefix("V")
            val notes = cleanNotes(root.optString("body"))
            val url = root.optString("html_url").trim().ifEmpty { RELEASES_URL }
            ReleaseInfo(versionName = version, notes = notes, releaseUrl = url, apkUrl = apkAssetUrl(root))
        } catch (e: Exception) {
            null
        }
    }

    /** First asset named `*.apk` (release.yml uploads exactly one, `rusty-vX.Y.Z.apk`). */
    private fun apkAssetUrl(root: JSONObject): String? {
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            return asset.optString("browser_download_url").trim().ifEmpty { null }
        }
        return null
    }

    private val mdHeader = Regex("""^#{1,6}\s+""")
    private val mdBullet = Regex("""^(\s*)[-*]\s+""")

    /**
     * Pure: tidies a release `body` for plain-text display in the app, which has no
     * Markdown renderer. Drops the trailing "**Full Changelog**: …compare…" footer that
     * GitHub's auto-generated notes append, strips leading `#` header markers
     * ("### Added" → "Added"), and turns `-`/`*` bullets into "• ". Returns a trimmed
     * string (possibly empty).
     */
    fun cleanNotes(body: String): String {
        return body.lines()
            .filterNot { it.trimStart().startsWith("**Full Changelog**") }
            .map { line ->
                line.replaceFirst(mdHeader, "").replaceFirst(mdBullet, "$1• ")
            }
            .joinToString("\n")
            .trim()
    }

    /**
     * Pure: true when [latest] is a strictly higher semantic version than [current].
     * Tolerates a leading `v`, surrounding whitespace, and differing segment counts
     * (`1.2` vs `1.2.0`). Any non-numeric/garbage segment makes the comparison return
     * false so we never falsely prompt for an update.
     */
    fun isNewer(current: String, latest: String): Boolean {
        val c = parseVersion(current) ?: return false
        val l = parseVersion(latest) ?: return false
        val len = maxOf(c.size, l.size)
        for (i in 0 until len) {
            val cv = c.getOrElse(i) { 0 }
            val lv = l.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    private fun parseVersion(raw: String): List<Int>? {
        val trimmed = raw.trim().removePrefix("v").removePrefix("V")
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split(".")
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    /**
     * I/O: fetches the latest release and compares it to [currentVersion]. Network or
     * parse failures yield [UpdateStatus.ERROR] — never throws. Definitive answers are
     * cached for [CACHE_TTL_MS]; errors are never cached so the next call retries.
     */
    fun check(currentVersion: String): UpdateCheck {
        cached?.let { (at, check) ->
            if (isCacheFresh(at, System.currentTimeMillis())) return check
        }
        var conn: HttpURLConnection? = null
        val result = try {
            conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "rusty-android")
                connectTimeout = 8000
                readTimeout = 8000
            }
            when (val code = conn.responseCode) {
                200 -> {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val release = parseRelease(body)
                    if (release == null) {
                        UpdateCheck(UpdateStatus.ERROR, currentVersion, null)
                    } else {
                        val status = if (isNewer(currentVersion, release.versionName))
                            UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE
                        UpdateCheck(status, currentVersion, release)
                    }
                }
                else -> {
                    Log.w(TAG, "update check HTTP $code")
                    UpdateCheck(UpdateStatus.ERROR, currentVersion, null)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            UpdateCheck(UpdateStatus.ERROR, currentVersion, null)
        } finally {
            conn?.disconnect()
        }
        // Only cache a definitive answer; let transient errors retry on next open.
        if (result.status != UpdateStatus.ERROR) cached = System.currentTimeMillis() to result
        return result
    }
}
