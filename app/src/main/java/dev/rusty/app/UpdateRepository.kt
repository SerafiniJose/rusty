package dev.rusty.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer release than the running build. Parsing, version comparison and the
 * caching policy are pure (split from I/O) so they're unit-testable on the JVM, mirroring
 * [LyricsRepository].
 *
 * Checks run at most once a day: a definitive answer is kept for [CACHE_TTL_MS] and persisted
 * through [store], so restarting the app does not check again, and every reader (the info-button
 * dot, the Info and About sheets, the remote-control page) shares that one answer. A failed check
 * is retried after [ERROR_RETRY_MS], not on every read, and keeps serving the last good answer.
 * [check] with `force = true` ("Check now") skips both waits.
 */
object UpdateRepository {
    private const val TAG = "UpdateRepository"

    /** Public repo whose Releases drive in-app update prompts. */
    const val RELEASES_URL = "https://github.com/SerafiniJose/rusty/releases/latest"
    const val REPO_URL = "https://github.com/SerafiniJose/rusty"
    const val ISSUES_URL = "$REPO_URL/issues"
    private const val API_URL =
        "https://api.github.com/repos/SerafiniJose/rusty/releases/latest"

    enum class UpdateStatus { UP_TO_DATE, UPDATE_AVAILABLE, ERROR }

    /** [apkUrl]: direct download URL of the release's APK asset, or null when the release
     *  ships none (installers must then fall back to opening [releaseUrl]). [publishedAt] is
     *  GitHub's ISO-8601 `published_at`; [apkSizeBytes] the APK asset's size. */
    data class ReleaseInfo(
        val versionName: String,
        val notes: String,
        val releaseUrl: String,
        val apkUrl: String? = null,
        val publishedAt: String? = null,
        val apkSizeBytes: Long? = null,
    )

    /**
     * [checkedAtMs]: when [latest] was fetched (null when nothing ever was). [lastCheckFailed]:
     * the most recent attempt failed, so a non-ERROR [status] comes from the last good answer.
     */
    data class UpdateCheck(
        val status: UpdateStatus,
        val currentVersion: String,
        val latest: ReleaseInfo?,
        val checkedAtMs: Long? = null,
        val lastCheckFailed: Boolean = false,
    )

    /** Where the last good answer survives a restart (a SharedPreferences string in the app). */
    interface CacheStore {
        fun load(): String?
        fun save(json: String)
    }

    sealed class FetchResult {
        data class Ok(val release: ReleaseInfo) : FetchResult()
        object Failed : FetchResult()
    }

    data class Cached(val release: ReleaseInfo, val checkedAtMs: Long)

    const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    const val ERROR_RETRY_MS = 60 * 60 * 1000L

    /** Set once by [RustyApp] before anything checks. */
    @Volatile
    var store: CacheStore? = null

    /** Called with every [check] result, on the checking thread. [RustyApp] hands it to the main
     *  thread for the info-button dot. */
    @Volatile
    var onResult: ((UpdateCheck) -> Unit)? = null

    private val checker by lazy {
        Checker(storeProvider = { store }, fetch = ::fetchLatest, clock = System::currentTimeMillis)
    }

    /** Blocking — call off the main thread. See the class comment for when it hits the network. */
    fun check(currentVersion: String, force: Boolean = false): UpdateCheck =
        checker.check(currentVersion, force).also { result -> onResult?.invoke(result) }

    /** The last answer this process has, without touching disk or network; null before one. */
    fun peek(currentVersion: String): UpdateCheck? = checker.peek(currentVersion)

    /**
     * The caching policy, separated from the singleton so tests can drive it with a fake fetch
     * and clock. Synchronized as a whole: a second caller during a fetch waits for it and is then
     * answered from the fresh cache rather than fetching again.
     */
    class Checker(
        private val storeProvider: () -> CacheStore?,
        private val fetch: () -> FetchResult,
        private val clock: () -> Long,
    ) {
        private val lock = Any()
        private var loaded = false
        private var cached: Cached? = null
        private var failedAtMs: Long? = null

        fun check(currentVersion: String, force: Boolean): UpdateCheck = synchronized(lock) {
            loadOnce()
            val now = clock()
            val known = cached
            if (!force) {
                if (known != null && isCacheFresh(known.checkedAtMs, now)) return resultFor(currentVersion, known, false)
                val failedAt = failedAtMs
                if (failedAt != null && isRetryWaiting(failedAt, now)) return failureFor(currentVersion, known)
            }
            when (val fetched = fetch()) {
                is FetchResult.Ok -> {
                    val fresh = Cached(fetched.release, now)
                    cached = fresh
                    failedAtMs = null
                    runCatching { storeProvider()?.save(encodeCache(fresh)) }
                    resultFor(currentVersion, fresh, false)
                }
                FetchResult.Failed -> {
                    failedAtMs = now
                    failureFor(currentVersion, known)
                }
            }
        }

        fun peek(currentVersion: String): UpdateCheck? = synchronized(lock) {
            val known = cached ?: return null
            resultFor(currentVersion, known, failedAtMs != null)
        }

        private fun loadOnce() {
            if (loaded) return
            loaded = true
            cached = decodeCache(runCatching { storeProvider()?.load() }.getOrNull())
        }

        private fun failureFor(currentVersion: String, known: Cached?): UpdateCheck =
            known?.let { resultFor(currentVersion, it, true) }
                ?: UpdateCheck(UpdateStatus.ERROR, currentVersion, null, null, lastCheckFailed = true)

        /** The status is worked out against the RUNNING version on every read, so a cached
         *  "2.7.0 available" turns into "up to date" the moment 2.7.0 is installed. */
        private fun resultFor(currentVersion: String, known: Cached, failed: Boolean): UpdateCheck {
            val status = if (isNewer(currentVersion, known.release.versionName))
                UpdateStatus.UPDATE_AVAILABLE else UpdateStatus.UP_TO_DATE
            return UpdateCheck(status, currentVersion, known.release, known.checkedAtMs, failed)
        }
    }

    /** Pure: the info-button dot shows while an update is available whose version the user has
     *  not opened in About yet ([seenVersion]). */
    fun shouldShowDot(check: UpdateCheck?, seenVersion: String?): Boolean =
        check?.status == UpdateStatus.UPDATE_AVAILABLE && check.latest?.versionName != seenVersion

    /** Pure: whether a check cached at [cachedAtMs] is still current at [nowMs]. A cachedAt
     *  in the future (wall-clock reset) counts as stale rather than fresh-forever. */
    fun isCacheFresh(cachedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= cachedAtMs && nowMs - cachedAtMs < CACHE_TTL_MS

    /** Pure: whether a check that failed at [failedAtMs] should still wait before retrying. */
    fun isRetryWaiting(failedAtMs: Long, nowMs: Long): Boolean =
        nowMs >= failedAtMs && nowMs - failedAtMs < ERROR_RETRY_MS

    /** Pure: the persisted form of the last good answer. */
    fun encodeCache(cached: Cached): String {
        val release = JSONObject()
            .put("version", cached.release.versionName)
            .put("notes", cached.release.notes)
            .put("url", cached.release.releaseUrl)
        cached.release.apkUrl?.let { release.put("apkUrl", it) }
        cached.release.publishedAt?.let { release.put("publishedAt", it) }
        cached.release.apkSizeBytes?.let { release.put("apkSize", it) }
        return JSONObject().put("checkedAt", cached.checkedAtMs).put("release", release).toString()
    }

    /** Pure: reads [encodeCache]'s output; null for anything missing or malformed. */
    fun decodeCache(json: String?): Cached? {
        if (json.isNullOrBlank()) return null
        return try {
            val root = JSONObject(json)
            val release = root.optJSONObject("release") ?: return null
            val version = release.optString("version").trim()
            val checkedAt = root.optLong("checkedAt", -1)
            if (version.isEmpty() || checkedAt < 0) return null
            Cached(
                ReleaseInfo(
                    versionName = version,
                    notes = release.optString("notes"),
                    releaseUrl = release.optString("url").ifBlank { RELEASES_URL },
                    apkUrl = release.optString("apkUrl").ifBlank { null },
                    publishedAt = release.optString("publishedAt").ifBlank { null },
                    apkSizeBytes = release.optLong("apkSize", 0).takeIf { it > 0 },
                ),
                checkedAt,
            )
        } catch (e: Exception) {
            null
        }
    }

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
            val apk = apkAsset(root)
            ReleaseInfo(
                versionName = version,
                notes = notes,
                releaseUrl = url,
                apkUrl = apk?.optString("browser_download_url")?.trim()?.ifEmpty { null },
                publishedAt = root.optString("published_at").trim().ifEmpty { null },
                apkSizeBytes = apk?.optLong("size", 0)?.takeIf { it > 0 },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** First asset named `*.apk` (release.yml uploads exactly one, `rusty-vX.Y.Z.apk`). */
    private fun apkAsset(root: JSONObject): JSONObject? {
        val assets = root.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (!asset.optString("name").endsWith(".apk", ignoreCase = true)) continue
            return asset
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
     * string (possibly empty). [ReleaseNotes] reads the structure back out of this.
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

    /** I/O: one GitHub fetch. Never throws; any failure is [FetchResult.Failed]. */
    private fun fetchLatest(): FetchResult {
        var conn: HttpURLConnection? = null
        return try {
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
                    parseRelease(body)?.let { FetchResult.Ok(it) } ?: FetchResult.Failed
                }
                else -> {
                    Log.w(TAG, "update check HTTP $code")
                    FetchResult.Failed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "update check failed: ${e.message}")
            FetchResult.Failed
        } finally {
            conn?.disconnect()
        }
    }
}
