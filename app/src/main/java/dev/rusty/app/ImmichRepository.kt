package dev.rusty.app

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

/** One HTTP exchange. Seam so [ImmichRepository] is unit-testable without a network. */
fun interface ImmichHttp {
    fun request(method: String, url: String, apiKey: String, body: String?): ImmichHttpResponse
}

data class ImmichHttpResponse(val code: Int, val body: String?)

sealed interface ImmichResult<out T> {
    data class Ok<T>(val value: T) : ImmichResult<T>
    data class Error(val kind: ImmichErrorKind) : ImmichResult<Nothing>
}

enum class ImmichErrorKind { AUTH, UNREACHABLE }

/** One capability probe result from the Save-time capability check in settings. */
data class ImmichProbe(val label: String, val ok: Boolean)

/**
 * Immich REST client (auth = `x-api-key` header, never a query param). Blocking I/O — callers run
 * it on Dispatchers.IO. Never throws; failures map to [ImmichResult.Error]. Mirrors
 * [CanvasRepository]'s seam/default-http structure.
 */
class ImmichRepository(private val http: ImmichHttp = defaultHttp()) {

    fun fetchRandomAssets(cfg: ImmichConfig, filters: ImmichFilters, size: Int): ImmichResult<List<ImmichAsset>> =
        exchange(cfg, "POST", "${cfg.baseUrl}/api/search/random", ImmichApi.randomSearchBody(filters, size)) {
            ImmichApi.parseAssets(it)
        }

    fun fetchAlbums(cfg: ImmichConfig): ImmichResult<List<ImmichPickerItem>> =
        exchange(cfg, "GET", "${cfg.baseUrl}/api/albums", null) { ImmichApi.parseAlbums(it) }

    /**
     * Albums shared WITH this user (owned by somebody else). `isOwned=false` maps server-side to
     * `album_user.role != 'owner'`, i.e. exactly the albums whose assets [fetchRandomAssets] can
     * never return — see [fetchSlideshowAssets].
     */
    fun fetchSharedAlbumIds(cfg: ImmichConfig): ImmichResult<Set<String>> =
        exchange(cfg, "GET", "${cfg.baseUrl}/api/albums?isOwned=false", null) {
            ImmichApi.parseAlbums(it).map { album -> album.id }.toSet()
        }

    fun fetchTimelineBuckets(
        cfg: ImmichConfig, albumId: String, personId: String? = null, tagId: String? = null,
    ): ImmichResult<List<ImmichBucket>> =
        exchange(cfg, "GET", timelineUrl(cfg, "buckets", albumId, personId, tagId), null) {
            ImmichApi.parseTimelineBuckets(it)
        }

    fun fetchTimelineBucket(
        cfg: ImmichConfig, albumId: String, bucket: String,
        personId: String? = null, tagId: String? = null,
    ): ImmichResult<List<ImmichAsset>> =
        exchange(cfg, "GET", timelineUrl(cfg, "bucket", albumId, personId, tagId) + "&timeBucket=$bucket", null) {
            ImmichApi.parseTimelineAssets(it)
        }

    private fun timelineUrl(
        cfg: ImmichConfig, endpoint: String, albumId: String, personId: String?, tagId: String?,
    ): String = buildString {
        append(cfg.baseUrl).append("/api/timeline/").append(endpoint)
        append("?albumId=").append(albumId)
        // Keep archived/trashed off the frame; the timeline, unlike /search/random, includes them.
        append("&visibility=timeline&isTrashed=false")
        personId?.let { append("&personId=").append(it) }
        tagId?.let { append("&tagId=").append(it) }
    }

    /**
     * The slideshow's batch source, and the ONLY fetch the frame should call. Two Immich server
     * behaviours make a plain `/search/random` wrong once albums are selected:
     *
     *  1. `searchAssetBuilder` applies `asset.ownerId IN (you, ...your partners)` IN ADDITION to
     *     `albumIds`. Every asset in an album shared with you is owned by the person who shared it,
     *     so that filter strips the album empty — no API-key scope changes this. Shared albums are
     *     therefore read through `/api/timeline/bucket?albumId=`, which authorises on ALBUM_READ and
     *     deliberately applies no owner filter.
     *  2. `inAlbums` applies `having count(distinct albumId) = albumIds.length`, so sending several
     *     albums in one request asks for their INTERSECTION — almost always empty. Albums are
     *     therefore queried one at a time and unioned, which is what selecting several albums on a
     *     photo frame plainly means.
     *
     * Degradation is deliberate at every step: an unresolvable ownership lookup falls back to the
     * old single-endpoint behaviour, and a per-album failure drops that album rather than the batch.
     * Only when EVERY source fails does this report an error, so one broken album cannot blank a
     * frame the rest of the library could still fill.
     */
    fun fetchSlideshowAssets(
        cfg: ImmichConfig, filters: ImmichFilters, size: Int, random: Random = Random.Default,
    ): ImmichResult<List<ImmichAsset>> {
        // No album filter: nothing to split, and /search/random already covers people/tags.
        if (filters.albumIds.isEmpty()) return fetchRandomAssets(cfg, filters, size)

        val shared = sharedAlbumIds(cfg)
        // Over-fetch per album so that albums which come back short still leave a full batch.
        val perAlbum = ((size + filters.albumIds.size - 1) / filters.albumIds.size).coerceAtLeast(1)
        val collected = mutableListOf<ImmichAsset>()
        var anyOk = false
        var lastError: ImmichErrorKind? = null

        for (albumId in filters.albumIds) {
            val result = if (albumId in shared) {
                fetchSharedAlbumAssets(cfg, filters, albumId, perAlbum, random)
            } else {
                fetchRandomAssets(cfg, filters.copy(albumIds = listOf(albumId)), perAlbum)
            }
            when (result) {
                is ImmichResult.Ok -> { anyOk = true; collected.addAll(result.value) }
                is ImmichResult.Error -> lastError = result.kind
            }
        }
        if (!anyOk) return ImmichResult.Error(lastError ?: ImmichErrorKind.UNREACHABLE)
        // distinctBy: one photo can sit in two selected albums, and the queue's dedupe ring is
        // shallower than a batch.
        return ImmichResult.Ok(collected.distinctBy { it.id }.shuffled(random).take(size))
    }

    /**
     * One shared album's slice of a batch: pick a month weighted by its photo count, then sample it.
     * Two requests per album per batch (~every 30 slides), not per slide.
     *
     * People and tags are only representable here one at a time (`/timeline` takes a single
     * `personId`/`tagId`, while `/search/random` takes lists and ANDs them). With several people or
     * tags selected the album is skipped rather than queried without them: showing photos that
     * ignore an active filter would be worse than showing none.
     */
    private fun fetchSharedAlbumAssets(
        cfg: ImmichConfig, filters: ImmichFilters, albumId: String, want: Int, random: Random,
    ): ImmichResult<List<ImmichAsset>> {
        if (filters.personIds.size > 1 || filters.tagIds.size > 1) return ImmichResult.Ok(emptyList())
        val personId = filters.personIds.singleOrNull()
        val tagId = filters.tagIds.singleOrNull()

        val buckets = when (val r = fetchTimelineBuckets(cfg, albumId, personId, tagId)) {
            is ImmichResult.Error -> return r
            is ImmichResult.Ok -> r.value.filter { it.count > 0 }
        }
        if (buckets.isEmpty()) return ImmichResult.Ok(emptyList())
        val bucket = pickWeighted(buckets, random)
        return when (val r = fetchTimelineBucket(cfg, albumId, bucket, personId, tagId)) {
            is ImmichResult.Error -> r
            is ImmichResult.Ok -> ImmichResult.Ok(r.value.shuffled(random).take(want))
        }
    }

    /** Weighted by count so a 900-photo month isn't sampled as often as a 3-photo one. */
    private fun pickWeighted(buckets: List<ImmichBucket>, random: Random): String {
        val total = buckets.sumOf { it.count }
        if (total <= 0) return buckets.first().timeBucket
        var roll = random.nextInt(total)
        for (b in buckets) {
            roll -= b.count
            if (roll < 0) return b.timeBucket
        }
        return buckets.last().timeBucket
    }

    /**
     * Album ownership, resolved once per config rather than once per batch — an album's owner is
     * fixed at creation, so this cannot go stale within a session, and a changed connection brings
     * a different [ImmichConfig] key. A failed lookup is NOT cached: it degrades this batch to the
     * old behaviour and is retried on the next one.
     */
    @Synchronized
    private fun sharedAlbumIds(cfg: ImmichConfig): Set<String> {
        if (sharedIdsCfg == cfg) sharedIdsCache?.let { return it }
        return when (val result = fetchSharedAlbumIds(cfg)) {
            is ImmichResult.Ok -> result.value.also { sharedIdsCfg = cfg; sharedIdsCache = it }
            is ImmichResult.Error -> emptySet()
        }
    }

    private var sharedIdsCfg: ImmichConfig? = null
    private var sharedIdsCache: Set<String>? = null

    /** People are paginated: fetch pages until hasNextPage=false, capped at [PEOPLE_PAGE_CAP]
     *  pages so a server that never flips hasNextPage to false can't cause unbounded requests /
     *  unbounded accumulator growth -- whatever was collected so far is returned instead. Hidden
     *  /unnamed already dropped. */
    fun fetchPeople(cfg: ImmichConfig): ImmichResult<List<ImmichPickerItem>> {
        val all = mutableListOf<ImmichPickerItem>()
        var page = 1
        while (page <= PEOPLE_PAGE_CAP) {
            val result = fetchPeoplePage(cfg, page)
            when (result) {
                is ImmichResult.Error -> return result
                is ImmichResult.Ok -> {
                    all.addAll(result.value.items)
                    if (!result.value.hasNextPage) return ImmichResult.Ok(all)
                    page++
                }
            }
        }
        return ImmichResult.Ok(all)
    }

    /** Single people page. Shared by [fetchPeople]'s pagination loop and [testConnection]'s probe
     *  so the URL shape is written once and stays consistent. */
    private fun fetchPeoplePage(cfg: ImmichConfig, page: Int): ImmichResult<ImmichPeoplePage> {
        val url = "${cfg.baseUrl}/api/people?page=$page&size=$PEOPLE_PAGE_SIZE&withHidden=false"
        return exchange(cfg, "GET", url, null) { ImmichApi.parsePeoplePage(it) }
    }

    fun fetchTags(cfg: ImmichConfig): ImmichResult<List<ImmichPickerItem>> =
        exchange(cfg, "GET", "${cfg.baseUrl}/api/tags", null) { ImmichApi.parseTags(it) }

    /**
     * Probes each capability the feature actually needs (an API key can authenticate yet lack
     * scopes — asset.read / album.read / person.read / tag.read), so Save's capability check
     * cannot report a false success. Order is stable: Photos, Albums, People, Tags. The People probe
     * checks only the FIRST page — fetchPeople's full pagination would mean N sequential HTTP
     * round trips behind a settings button on a large library.
     */
    fun testConnection(cfg: ImmichConfig, filters: ImmichFilters): List<ImmichProbe> = listOf(
        ImmichProbe("Photos", fetchRandomAssets(cfg, filters, 1) is ImmichResult.Ok),
        ImmichProbe("Albums", fetchAlbums(cfg) is ImmichResult.Ok),
        ImmichProbe("People", fetchPeoplePage(cfg, 1) is ImmichResult.Ok),
        ImmichProbe("Tags", fetchTags(cfg) is ImmichResult.Ok),
    )

    /**
     * The authenticated account. Used by settings to show "{name} · {host}" and to make Save a real
     * sign-in check. A 200 whose body doesn't parse is treated as UNREACHABLE (not a silent success)
     * so a non-Immich host answering 200 can't fake a signed-in state.
     */
    fun fetchCurrentUser(cfg: ImmichConfig): ImmichResult<ImmichUser> =
        when (val r = exchange(cfg, "GET", "${cfg.baseUrl}/api/users/me", null) { ImmichApi.parseUser(it) }) {
            is ImmichResult.Ok -> r.value?.let { ImmichResult.Ok(it) } ?: ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
            is ImmichResult.Error -> r
        }

    /** Preview rendition (server-configured resolution, typically 1440px). Loaded via Coil with
     *  the x-api-key header set on the request — this URL carries no credential. */
    fun previewUrl(cfg: ImmichConfig, assetId: String): String =
        "${cfg.baseUrl}/api/assets/$assetId/thumbnail?size=preview"

    /** Face crop for the People picker. Like [previewUrl], carries no credential. */
    fun personThumbUrl(cfg: ImmichConfig, personId: String): String =
        "${cfg.baseUrl}/api/people/$personId/thumbnail"

    /**
     * `internal` (not `private`) rather than a visibility statement of intent: this is the single
     * choke point for the class's "never throws" guarantee, so [ImmichRepositoryTest] exercises
     * this exact shipped function directly with a synthetic throwing `parse`, instead of depending
     * on the incidental fact that every real `ImmichApi.parseXxx` happens to self-catch.
     *
     * The whole exchange -- both the request and the parse of its body -- runs under one catch, so
     * a throw from `parse` maps to [ImmichErrorKind.UNREACHABLE] exactly like a throw from
     * `http.request`. The AUTH-vs-UNREACHABLE status mapping is unaffected: 401/403 never reach
     * `parse` in the first place.
     */
    internal fun <T> exchange(
        cfg: ImmichConfig, method: String, url: String, body: String?, parse: (String) -> T,
    ): ImmichResult<T> = try {
        val resp = http.request(method, url, cfg.apiKey, body)
        when (resp.code) {
            in 200..299 -> ImmichResult.Ok(parse(resp.body ?: ""))
            401, 403 -> ImmichResult.Error(ImmichErrorKind.AUTH)
            else -> ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
        }
    } catch (e: Exception) {
        try { Log.w(TAG, "immich $method $url failed: ${e.message}") } catch (ignored: Exception) {}
        ImmichResult.Error(ImmichErrorKind.UNREACHABLE)
    }

    companion object {
        private const val TAG = "ImmichRepository"
        private const val PEOPLE_PAGE_SIZE = 500

        /** `internal` so the pagination-cap test can assert the exact bound without duplicating
         *  the literal. 200 pages * 500/page = 100k people -- generous for any real library while
         *  still bounding a server that never terminates the hasNextPage loop. */
        internal const val PEOPLE_PAGE_CAP = 200

        /** Process-wide instance (theme + settings panel share it). Tests build their own. */
        val shared: ImmichRepository by lazy { ImmichRepository() }

        private fun defaultHttp(): ImmichHttp = ImmichHttp { method, url, apiKey, body ->
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    setRequestProperty("x-api-key", apiKey)
                    setRequestProperty("Accept", "application/json")
                    connectTimeout = 8000
                    readTimeout = 8000
                    if (body != null) {
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json")
                    }
                }
                if (body != null) conn.outputStream.use { it.write(body.toByteArray()) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ImmichHttpResponse(code, stream?.use { it.readBytes().decodeToString() })
            } finally {
                conn?.disconnect()
            }
        }
    }
}
