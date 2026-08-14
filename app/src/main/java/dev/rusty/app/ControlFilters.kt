package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the filter-selection request body posted by the off-device control page/API into
 * [ImmichFilters]. Storage is comma-joined (see [SlideshowSettings]), so a smuggled comma in an
 * ID would silently splice in extra entries on read-back — that is why input is validated
 * structurally against a UUID shape and rejected outright (`null`) rather than escaped or
 * truncated. Independent of whether the Immich server is reachable: this is a shape check on
 * untrusted JSON, not a check against the server's actual library.
 */
object ControlFilters {
    private val UUID_REGEX =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private const val MAX_PER_CATEGORY = 500

    /** Returns `null` on any structural violation: malformed JSON, a non-array category value, a
     *  non-string array element, a non-UUID string, or a category exceeding [MAX_PER_CATEGORY]
     *  entries. A category key absent from the body yields an empty list, not `null`. */
    fun parse(body: String): ImmichFilters? = try {
        val obj = JSONObject(body)
        val albumIds = parseCategory(obj, "albumIds") ?: return null
        val personIds = parseCategory(obj, "personIds") ?: return null
        val tagIds = parseCategory(obj, "tagIds") ?: return null
        ImmichFilters(albumIds, personIds, tagIds)
    } catch (e: Exception) {
        null
    }

    private fun parseCategory(obj: JSONObject, key: String): List<String>? {
        if (!obj.has(key)) return emptyList()
        val raw = obj.get(key) as? JSONArray ?: return null
        if (raw.length() > MAX_PER_CATEGORY) return null
        val result = LinkedHashSet<String>()
        for (i in 0 until raw.length()) {
            val value = raw.get(i) as? String ?: return null
            val trimmed = value.trim()
            if (!UUID_REGEX.matches(trimmed)) return null
            result.add(trimmed)
        }
        return result.toList()
    }
}
