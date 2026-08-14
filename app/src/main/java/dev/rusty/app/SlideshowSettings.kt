package dev.rusty.app

import android.content.SharedPreferences

/** Connection config: normalized base URL (no trailing slash) + API key. Null until both are set. */
data class ImmichConfig(val baseUrl: String, val apiKey: String)

/** Persisted filter selections (Immich UUIDs). Empty list in a category = no filter. */
data class ImmichFilters(val albumIds: List<String>, val personIds: List<String>, val tagIds: List<String>)

enum class ImmichConnectionSave { INVALID, SAVED, SAVED_CONFIG_CHANGED }

/**
 * Pref keys + typed accessors for the Slideshow screensaver feature. Filters are stored
 * comma-joined (Immich IDs are UUIDs — never contain commas). A connection change (URL origin or
 * API key) wipes the filter selections: they are IDs on the OLD server and would silently select
 * wrong content on the new one (mirrors HomeAssistantFeature.SERVER_RESET_KEYS).
 */
object SlideshowSettings {
    const val KEY_ENABLED = "immich_frame_enabled"
    const val KEY_URL = "immich_url"

    /**
     * Name of the API key entry. Despite sitting alongside the pref keys, this one addresses the
     * [SecretStore] — the key is never written to plaintext preferences. The `immich_` prefix is
     * kept only so the pre-[SecretStore] migration can find the value it must move and delete.
     */
    const val KEY_API_KEY = "immich_api_key"
    const val KEY_CONNECTION_GEN = "immich_connection_gen"
    const val KEY_ALBUM_IDS = "immich_album_ids"
    const val KEY_PERSON_IDS = "immich_person_ids"
    const val KEY_TAG_IDS = "immich_tag_ids"
    const val KEY_ACCOUNT_NAME = "immich_account_name"
    const val KEY_VERIFIED = "immich_verified"
    const val KEY_INTERVAL_SECONDS = "immich_interval_seconds"
    const val KEY_SHOW_CLOCK = "immich_show_clock"
    const val KEY_SHOW_INFO = "immich_show_info"
    const val KEY_ZOOM = "immich_zoom"
    const val KEY_SPLIT_VIEW = "immich_split_view"

    /** Stepped interval choices for the settings slider; 45 s is upstream ImmichFrame's default. */
    val INTERVAL_STEPS: List<Int> = listOf(10, 20, 30, 45, 60, 120, 300)
    const val DEFAULT_INTERVAL_SECONDS = 45

    fun intervalLabel(seconds: Int): String = when {
        seconds < 60 -> "$seconds seconds"
        seconds % 60 == 0 -> "${seconds / 60} minute" + if (seconds > 60) "s" else ""
        else -> "$seconds seconds"
    }

    /**
     * Slider index for a stored interval. A stored value that is not one of [INTERVAL_STEPS] (older
     * build, hand-edited pref) snaps to the nearest step instead of the -1 a raw `indexOf` returns
     * — -1 would both mis-place the thumb and blow up `INTERVAL_STEPS[index]`.
     */
    fun intervalIndexFor(seconds: Int): Int {
        val exact = INTERVAL_STEPS.indexOf(seconds)
        if (exact >= 0) return exact
        return INTERVAL_STEPS.indices.minByOrNull { kotlin.math.abs(INTERVAL_STEPS[it] - seconds) } ?: 0
    }

    fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) =
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun config(prefs: SharedPreferences, secrets: SecretStore): ImmichConfig? {
        val url = prefs.getString(KEY_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val key = secrets.get(KEY_API_KEY)?.takeIf { it.isNotBlank() } ?: return null
        return ImmichConfig(url, key)
    }

    /** The stored API key, for pre-filling the settings field. */
    fun apiKey(secrets: SecretStore): String = secrets.get(KEY_API_KEY).orEmpty()

    /** Persisted display name of the signed-in Immich account (name, or email fallback). Not a
     *  secret; cleared by [saveConnection] when the connection changes. */
    fun accountName(prefs: SharedPreferences): String? =
        prefs.getString(KEY_ACCOUNT_NAME, null)?.takeIf { it.isNotBlank() }

    fun setAccountName(prefs: SharedPreferences, name: String) =
        prefs.edit().putString(KEY_ACCOUNT_NAME, name).apply()

    /** True once a Save has actually authenticated the stored key against the server (identity read
     *  OR at least one capability probe succeeding). Persisted so the "connected" header survives a
     *  panel rebind, and — crucially — so a key that FAILED verification is never painted as
     *  "key saved". Cleared by [saveConnection] whenever the connection changes. */
    fun isVerified(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY_VERIFIED, false)

    fun setVerified(prefs: SharedPreferences, verified: Boolean) =
        prefs.edit().putBoolean(KEY_VERIFIED, verified).apply()

    /** True when the configured server is plain http, i.e. the API key crosses the LAN in the
     *  clear on every request. Surfaced as a warning in settings rather than blocked: self-hosted
     *  Immich on a LAN commonly has no certificate. */
    fun isCleartext(prefs: SharedPreferences): Boolean =
        prefs.getString(KEY_URL, null)?.startsWith("http://") == true

    /** Monotonic counter bumped on every effective connection change. Namespaces picker
     *  thumbnail cache keys and tags category states, so nothing fetched under one
     *  connection can be attributed to another — including API-key-only changes, where
     *  URLs (and therefore Coil's default cache keys) would be identical. */
    fun connectionGeneration(prefs: SharedPreferences): Int = prefs.getInt(KEY_CONNECTION_GEN, 0)

    /**
     * Normalizes + persists the connection. Returns [ImmichConnectionSave.SAVED_CONFIG_CHANGED]
     * when the effective (url, key) pair changed — callers then invalidate the image cache and
     * live-refresh a mounted saver. Filter wipe happens here so no caller can forget it.
     */
    fun saveConnection(
        prefs: SharedPreferences,
        secrets: SecretStore,
        rawUrl: String?,
        apiKey: String?,
    ): ImmichConnectionSave {
        val normalized = HomeAssistantUrl.normalize(rawUrl)?.trimEnd('/')
        val key = apiKey?.trim().orEmpty()
        if (normalized == null || key.isEmpty()) return ImmichConnectionSave.INVALID
        val old = config(prefs, secrets)
        val changed = old == null || old.baseUrl != normalized || old.apiKey != key
        secrets.put(KEY_API_KEY, key)
        val edit = prefs.edit().putString(KEY_URL, normalized)
        if (changed) {
            listOf(KEY_ALBUM_IDS, KEY_PERSON_IDS, KEY_TAG_IDS, KEY_ACCOUNT_NAME, KEY_VERIFIED).forEach { edit.remove(it) }
            edit.putInt(KEY_CONNECTION_GEN, connectionGeneration(prefs) + 1)
        }
        edit.apply()
        return if (changed) ImmichConnectionSave.SAVED_CONFIG_CHANGED else ImmichConnectionSave.SAVED
    }

    fun filters(prefs: SharedPreferences) = ImmichFilters(
        albumIds = readIds(prefs, KEY_ALBUM_IDS),
        personIds = readIds(prefs, KEY_PERSON_IDS),
        tagIds = readIds(prefs, KEY_TAG_IDS),
    )

    /**
     * Writes all three filter categories in ONE `edit()` transaction — the only way filters are
     * ever written. (Three per-category setters used to exist alongside this; once the settings
     * panel and the remote-control API both moved here they had no production callers left, and a
     * public API that only tests use is a public API that invites a non-atomic write back.) The
     * single transaction is what stops a reader — the slideshow fetch loop — ever observing a
     * half-updated selection where one category reflects the new value and the other two the old.
     */
    fun setFilters(prefs: SharedPreferences, filters: ImmichFilters) {
        prefs.edit()
            .putString(KEY_ALBUM_IDS, filters.albumIds.joinToString(","))
            .putString(KEY_PERSON_IDS, filters.personIds.joinToString(","))
            .putString(KEY_TAG_IDS, filters.tagIds.joinToString(","))
            .apply()
    }

    fun intervalSeconds(prefs: SharedPreferences) =
        prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL_SECONDS)
    fun setIntervalSeconds(prefs: SharedPreferences, seconds: Int) =
        prefs.edit().putInt(KEY_INTERVAL_SECONDS, seconds).apply()

    fun showClock(prefs: SharedPreferences) = prefs.getBoolean(KEY_SHOW_CLOCK, true)
    fun setShowClock(prefs: SharedPreferences, v: Boolean) = prefs.edit().putBoolean(KEY_SHOW_CLOCK, v).apply()
    fun showInfo(prefs: SharedPreferences) = prefs.getBoolean(KEY_SHOW_INFO, true)
    fun setShowInfo(prefs: SharedPreferences, v: Boolean) = prefs.edit().putBoolean(KEY_SHOW_INFO, v).apply()
    fun zoomEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ZOOM, true)
    fun setZoomEnabled(prefs: SharedPreferences, v: Boolean) = prefs.edit().putBoolean(KEY_ZOOM, v).apply()
    fun splitViewEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_SPLIT_VIEW, true)
    fun setSplitViewEnabled(prefs: SharedPreferences, v: Boolean) = prefs.edit().putBoolean(KEY_SPLIT_VIEW, v).apply()

    private fun readIds(prefs: SharedPreferences, key: String): List<String> =
        prefs.getString(key, null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private fun writeIds(prefs: SharedPreferences, key: String, ids: List<String>) =
        prefs.edit().putString(key, ids.joinToString(",")).apply()
}

/**
 * Disable policy: turning Slideshow off while its theme is selected rewrites to Clock
 * (re-enabling does NOT auto-restore — the user re-picks). Pure so it is JVM-testable.
 */
object SlideshowDisable {
    fun themeAfterDisable(current: ScreensaverThemeId): ScreensaverThemeId =
        if (current == ScreensaverThemeId.SLIDESHOW) ScreensaverThemeId.CLOCK else current

    /**
     * The theme to actually use for [stored] given the feature's current [enabled] flag: unchanged
     * while enabled, healed through [themeAfterDisable] while disabled. Single source of truth for
     * "never mount Slideshow while its feature is off" — called both where the screensaver
     * resolves its current theme and where the settings sheet initializes the radio picker.
     */
    fun initialTheme(stored: ScreensaverThemeId, enabled: Boolean): ScreensaverThemeId =
        if (enabled) stored else themeAfterDisable(stored)
}
