package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/** One displayable photo. [isPortrait] is post-orientation (EXIF rotation applied). */
data class ImmichAsset(
    val id: String,
    val isPortrait: Boolean,
    val takenAt: String?,          // raw ISO timestamp from exifInfo.dateTimeOriginal
    val place: String?,            // "City, Country" (either part optional)
    val people: List<String>,
)

/** One selectable filter entry (album / person / tag). [thumbAssetId]/[count] are album-only
 *  (Immich's people/tag list payloads carry neither); both tolerate absent/null JSON. */
data class ImmichPickerItem(
    val id: String,
    val label: String,
    val thumbAssetId: String? = null,
    val count: Int? = null,
)

/** One time bucket (a month) from GET /api/timeline/buckets. */
data class ImmichBucket(val timeBucket: String, val count: Int)

data class ImmichPeoplePage(val items: List<ImmichPickerItem>, val hasNextPage: Boolean)

/** The authenticated account from GET /api/users/me. */
data class ImmichUser(val id: String, val name: String, val email: String)

/**
 * Pure request-building + response-parsing for the Immich REST API (JVM-unit-testable; mirrors
 * the CanvasRepository encode/parse split). Field names pinned against the Immich OpenAPI spec:
 * POST /api/search/random (RandomSearchDto -> AssetResponseDto[]), GET /api/albums,
 * GET /api/people?page&size&withHidden, GET /api/tags.
 */
object ImmichApi {

    /** EXIF orientations that swap displayed width/height (transpose family: 5–8). */
    private val SWAPPED_ORIENTATIONS = setOf("5", "6", "7", "8")

    fun randomSearchBody(filters: ImmichFilters, size: Int): String {
        val body = JSONObject()
            .put("size", size)
            .put("type", "IMAGE")
            .put("withExif", true)
            .put("withPeople", true)
        // Empty categories are omitted entirely: an empty array would mean "match nothing".
        if (filters.albumIds.isNotEmpty()) body.put("albumIds", JSONArray(filters.albumIds))
        if (filters.personIds.isNotEmpty()) body.put("personIds", JSONArray(filters.personIds))
        if (filters.tagIds.isNotEmpty()) body.put("tagIds", JSONArray(filters.tagIds))
        return body.toString()
    }

    /** Any decode failure -> empty list (the controller treats it as an empty batch). */
    fun parseAssets(json: String): List<ImmichAsset> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i -> parseAsset(arr.getJSONObject(i)) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun parseAsset(o: JSONObject): ImmichAsset? {
        val id = o.stringOrNull("id") ?: return null
        val exif = if (!o.isNull("exifInfo")) o.getJSONObject("exifInfo") else null
        val w = exif?.intOrNull("exifImageWidth")
        val h = exif?.intOrNull("exifImageHeight")
        val swapped = exif?.stringOrNull("orientation") in SWAPPED_ORIENTATIONS
        val isPortrait = if (w != null && h != null && w > 0 && h > 0) {
            if (swapped) w > h else h > w
        } else {
            false  // unknown dimensions -> treat as landscape (render solo)
        }
        val city = exif?.stringOrNull("city")
        val country = exif?.stringOrNull("country")
        val place = listOfNotNull(city, country).takeIf { it.isNotEmpty() }?.joinToString(", ")
        val people = if (!o.isNull("people")) {
            val parr = o.getJSONArray("people")
            (0 until parr.length()).mapNotNull {
                parr.getJSONObject(it).stringOrNull("name")?.takeIf { name -> name.isNotBlank() }
            }
        } else emptyList()
        return ImmichAsset(id, isPortrait, exif?.stringOrNull("dateTimeOriginal"), place, people)
    }

    fun parseAlbums(json: String): List<ImmichPickerItem> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.stringOrNull("id") ?: return@mapNotNull null
            val name = o.stringOrNull("albumName") ?: return@mapNotNull null
            val count = if (o.isNull("assetCount")) null else o.optInt("assetCount", -1).takeIf { it >= 0 }
            ImmichPickerItem(id, name, o.stringOrNull("albumThumbnailAssetId"), count)
        }
    } catch (e: Exception) {
        emptyList()
    }

    fun parsePeoplePage(json: String): ImmichPeoplePage = try {
        val o = JSONObject(json)
        val arr = o.getJSONArray("people")
        val items = (0 until arr.length()).mapNotNull { i ->
            val p = arr.getJSONObject(i)
            if (p.optBoolean("isHidden", false)) return@mapNotNull null
            val id = p.stringOrNull("id") ?: return@mapNotNull null
            val name = p.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ImmichPickerItem(id, name)
        }
        ImmichPeoplePage(items, o.optBoolean("hasNextPage", false))
    } catch (e: Exception) {
        ImmichPeoplePage(emptyList(), hasNextPage = false)
    }

    /** Tags labeled by `value` (the full hierarchical path) — leaf `name`s can collide. */
    fun parseTags(json: String): List<ImmichPickerItem> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val id = o.stringOrNull("id") ?: return@mapNotNull null
            val label = o.stringOrNull("value") ?: o.stringOrNull("name") ?: return@mapNotNull null
            ImmichPickerItem(id, label)
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** GET /api/users/me → the current account. null on missing id / malformed body. */
    fun parseUser(json: String): ImmichUser? = try {
        val o = JSONObject(json)
        val id = o.stringOrNull("id") ?: return null
        ImmichUser(id, o.stringOrNull("name").orEmpty(), o.stringOrNull("email").orEmpty())
    } catch (e: Exception) {
        null
    }

    fun parseTimelineBuckets(json: String): List<ImmichBucket> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val bucket = o.stringOrNull("timeBucket") ?: return@mapNotNull null
            ImmichBucket(bucket, o.optInt("count", 0))
        }
    } catch (e: Exception) {
        emptyList()
    }

    /**
     * Parses GET /api/timeline/bucket, which answers COLUMNAR — parallel arrays keyed by field,
     * not the array-of-objects every other endpoint returns. The `id` array alone defines the row
     * count; every other column is read defensively by index, so a short or absent column degrades
     * that one field instead of dropping the photo.
     *
     * Two fields are structurally unavailable here and the caller must live without them:
     * `people` (no face data in the payload at all) and true EXIF `dateTimeOriginal` (`fileCreatedAt`
     * stands in). Orientation comes from `ratio` (width/height), which the server has already
     * resolved through EXIF — so unlike [parseAsset] there is no orientation swap to undo.
     */
    fun parseTimelineAssets(json: String): List<ImmichAsset> = try {
        val o = JSONObject(json)
        val ids = o.getJSONArray("id")
        val ratio = o.optJSONArray("ratio")
        val isImage = o.optJSONArray("isImage")
        val isTrashed = o.optJSONArray("isTrashed")
        val city = o.optJSONArray("city")
        val country = o.optJSONArray("country")
        val takenAt = o.optJSONArray("fileCreatedAt")
        (0 until ids.length()).mapNotNull { i ->
            val id = ids.stringOrNullAt(i) ?: return@mapNotNull null
            // The timeline has no type filter, so videos and trashed assets arrive mixed in.
            if (isImage != null && i < isImage.length() && !isImage.optBoolean(i, true)) return@mapNotNull null
            if (isTrashed != null && i < isTrashed.length() && isTrashed.optBoolean(i, false)) return@mapNotNull null
            val r = if (ratio != null && i < ratio.length()) ratio.optDouble(i, 0.0) else 0.0
            val place = listOfNotNull(city?.stringOrNullAt(i), country?.stringOrNullAt(i))
                .takeIf { it.isNotEmpty() }?.joinToString(", ")
            ImmichAsset(
                id = id,
                isPortrait = r > 0.0 && r < 1.0,
                takenAt = takenAt?.stringOrNullAt(i),
                place = place,
                people = emptyList(),
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    /** "12 Aug 2023 · Turin, Italy · Ana, Bo" — null when no part is available. */
    fun captionFor(asset: ImmichAsset, includePeople: Boolean): String? {
        val date = asset.takenAt?.take(10)?.let { iso ->
            try {
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)
                if (parsed != null) SimpleDateFormat("d MMM yyyy", Locale.US).format(parsed) else null
            } catch (e: Exception) {
                null
            }
        }
        val people = asset.people.takeIf { includePeople && it.isNotEmpty() }?.joinToString(", ")
        val parts = listOfNotNull(date, asset.place, people)
        return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
    }

    // Null-safe accessors: NEVER optString for nullables — Android's org.json returns the literal
    // string "null" for JSON null (the "dashboards named null" bug); isNull() behaves identically
    // on both runtimes.
    private fun JSONObject.stringOrNull(key: String): String? =
        if (isNull(key)) null else getString(key)

    private fun JSONObject.intOrNull(key: String): Int? =
        if (isNull(key)) null else optInt(key).takeIf { has(key) }

    /** Same null-vs-"null" trap as [stringOrNull], plus an out-of-range guard for ragged columns. */
    private fun JSONArray.stringOrNullAt(index: Int): String? =
        if (index >= length() || isNull(index)) null else optString(index).takeIf { it.isNotEmpty() }
}
