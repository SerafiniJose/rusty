package dev.rusty.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure, Android-free model for the user's Home Assistant dashboards: parsing the
 * `lovelace/dashboards/list` payload, the synthetic always-present Overview entry, the selection
 * set, and base→dashboard URL building. Free of SharedPreferences/Views so it is JVM-unit-testable
 * (org.json is on the test classpath); consumers do the prefs read/write.
 */
object HomeAssistantDashboards {

    enum class Kind { DASHBOARD, APP, UNKNOWN }

    data class HaDashboard(
        val title: String,
        val urlPath: String,
        val icon: String? = null,
        val componentName: String? = null,
        val requireAdmin: Boolean = false,
        val kind: Kind = Kind.UNKNOWN,
    )

    data class HaAccount(val name: String, val isAdmin: Boolean)

    enum class HaGlyph { DASHBOARD, MAP, LOGBOOK, HISTORY, ENERGY, CALENDAR, TODO, SETTINGS, CHART, GENERIC }

    /** Maps a small set of common `mdi:` names to a bundled glyph; unknown/null → GENERIC. Pure. */
    fun glyphFor(mdi: String?): HaGlyph = when (mdi?.removePrefix("mdi:")?.trim()) {
        "view-dashboard", "view-dashboard-outline", "home", "home-assistant" -> HaGlyph.DASHBOARD
        "map", "map-outline", "map-marker" -> HaGlyph.MAP
        "book-open", "book-open-variant", "format-list-bulleted", "notebook" -> HaGlyph.LOGBOOK
        "history", "clock", "clock-outline", "chart-timeline" -> HaGlyph.HISTORY
        "lightning-bolt", "flash", "transmission-tower", "solar-power" -> HaGlyph.ENERGY
        "calendar", "calendar-outline", "calendar-month" -> HaGlyph.CALENDAR
        "format-list-checks", "clipboard-list", "check-circle-outline" -> HaGlyph.TODO
        "cog", "cog-outline", "tune" -> HaGlyph.SETTINGS
        "chart-line", "chart-box", "finance" -> HaGlyph.CHART
        else -> HaGlyph.GENERIC
    }

    /** True when discovery has succeeded against the current server — i.e. a session exists. Drives
     *  both the Connection-section hide and the account-subtitle show in the HA settings panel. Pure. */
    fun isSignedIn(state: HaDiscovery): Boolean = state is HaDiscovery.Loaded

    /** The address pre-filled into an empty HA URL field (LAN default). */
    const val DEFAULT_URL = "http://homeassistant.local:8123"

    /** url_path sentinel for the synthetic Overview view (HA's default dashboard, never in the API list). */
    const val OVERVIEW_PATH = "__overview__"
    val OVERVIEW = HaDashboard(title = "Overview", urlPath = OVERVIEW_PATH, icon = "mdi:view-dashboard", kind = Kind.DASHBOARD)

    /** Sidebar panels excluded from discovery: HA's config/admin pages and the default Lovelace
     *  ("lovelace"), which is already represented by the synthetic [OVERVIEW]. */
    private val SYSTEM_PANELS = setOf("config", "developer-tools", "profile", "lovelace")

    /** Parses the flat-array cache (written by [serializeDashboards]). Skips entries with no url_path;
     *  empty title → url_path. Legacy rows without component_name/kind → kind = UNKNOWN. Empty on
     *  null/blank/malformed. */
    fun parseCachedDashboards(listJson: String?): List<HaDashboard> {
        if (listJson.isNullOrBlank()) return emptyList()
        return runCatching {
            val arr = JSONArray(listJson)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val path = o.optString("url_path").trim()
                if (path.isEmpty()) return@mapNotNull null
                val kind = runCatching { Kind.valueOf(o.optString("kind")) }.getOrDefault(Kind.UNKNOWN)
                HaDashboard(
                    title = o.optString("title").trim().ifEmpty { path },
                    urlPath = path,
                    icon = o.optString("icon").trim().ifEmpty { null },
                    componentName = o.optString("component_name").trim().ifEmpty { null },
                    requireAdmin = o.optBoolean("require_admin", false),
                    kind = kind,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Serializes discovered dashboards to a compact JSON array (the cache format). */
    fun serializeDashboards(dashboards: List<HaDashboard>): String {
        val arr = JSONArray()
        dashboards.forEach { d ->
            arr.put(JSONObject().apply {
                put("title", d.title)
                put("url_path", d.urlPath)
                if (d.icon != null) put("icon", d.icon)
                if (d.componentName != null) put("component_name", d.componentName)
                if (d.requireAdmin) put("require_admin", true)
                put("kind", d.kind.name)
            })
        }
        return arr.toString()
    }

    /** All selectable dashboards: Overview first, then the cached discovered list. */
    fun availableFrom(cacheJson: String?): List<HaDashboard> =
        listOf(OVERVIEW) + parseCachedDashboards(cacheJson)

    /** The selected url_paths in stored order. `null`/blank/malformed → just Overview (first run /
     *  corrupt). A valid empty array `[]` stays empty (the user deliberately cleared everything). */
    fun parseSelectedPaths(json: String?): List<String> {
        if (json.isNullOrBlank()) return listOf(OVERVIEW_PATH)
        val parsed = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrNull()
        return parsed ?: listOf(OVERVIEW_PATH)   // null only on malformed; valid [] passes through
    }

    fun serializeSelectedPaths(paths: List<String>): String {
        val arr = JSONArray()
        paths.forEach { arr.put(it) }
        return arr.toString()
    }

    /** Drops paths not in [available], de-dups, preserves order. Overview is NOT forced — it is a
     *  normal, deselectable entry. An empty input yields an empty selection. */
    fun normalizeSelection(paths: List<String>, available: List<HaDashboard>): List<String> {
        val known = available.map { it.urlPath }.toSet()
        val ordered = LinkedHashSet<String>()
        paths.forEach { if (it in known) ordered.add(it) }
        return ordered.toList()
    }

    /** Resolves the selected paths to dashboards (in selected order), Overview always first. */
    fun selectedFrom(cacheJson: String?, selectedJson: String?): List<HaDashboard> {
        val available = availableFrom(cacheJson)
        val byPath = available.associateBy { it.urlPath }
        return normalizeSelection(parseSelectedPaths(selectedJson), available).mapNotNull { byPath[it] }
    }

    /** base→full dashboard URL. Overview → base root; others → base + "/" + url_path. */
    fun urlFor(baseUrl: String, dashboard: HaDashboard): String {
        val root = baseUrl.trimEnd('/')
        return if (dashboard.urlPath == OVERVIEW_PATH) root
        else "$root/${dashboard.urlPath.trimStart('/')}"
    }

    /** The cache is usable only if it was captured against the current base URL. */
    fun isCacheFresh(currentBase: String?, cachedOrigin: String?): Boolean =
        !currentBase.isNullOrBlank() && currentBase == cachedOrigin

    data class HaDiscoveryResult(val dashboards: List<HaDashboard>, val account: HaAccount?)

    /** Friendly labels for HA's built-in panels whose API title is null/i18n-key. */
    private val BUILTIN_TITLES = mapOf(
        "map" to "Map", "logbook" to "Logbook", "history" to "History",
        "energy" to "Energy", "calendar" to "Calendar", "todo" to "To-do lists",
    )

    /**
     * Reads a string field as a usable value, or null. Returns null for JSON `null`, a missing/blank
     * value, or the literal `"null"`/`"undefined"` strings. The literal-string case matters because
     * Android's `org.json.JSONObject.optString()` yields the string `"null"` for a JSON `null` value
     * (the JVM `org.json` on the unit-test classpath yields `""` instead) — without this, HA's internal
     * panels (notfound, _my_redirect, the companion `app`), which send `title: null`, surface on-device
     * as dashboards literally named "null".
     */
    private fun JSONObject.stringOrNull(key: String): String? {
        if (isNull(key)) return null
        val v = optString(key).trim()
        return if (v.isEmpty() || v.equals("null", ignoreCase = true) || v.equals("undefined", ignoreCase = true)) null else v
    }

    private fun kindOf(componentName: String?): Kind = when (componentName) {
        null -> Kind.UNKNOWN
        "lovelace" -> Kind.DASHBOARD
        else -> Kind.APP
    }

    /**
     * Parses the enriched discovery payload `{panels, dashboards, user}`. `panels` (the `get_panels`
     * object, already server-side admin-filtered) is the must-have → `null` when it is missing/malformed.
     * `dashboards` (`lovelace/dashboards/list`, NOT admin-filtered) is UNIONed in by url_path; its
     * `require_admin` entries are dropped unless `user.is_admin == true`. `user` → [HaAccount].
     */
    fun parseDiscoveryOrNull(json: String?): HaDiscoveryResult? {
        if (json.isNullOrBlank()) return null
        return runCatching {
            val root = JSONObject(json)
            val panelsObj = root.optJSONObject("panels") ?: return null
            // 1. PANELS (always shown — server already admin-filtered).
            val merged = LinkedHashMap<String, HaDashboard>()
            panelsObj.keys().forEach { key ->
                val p = panelsObj.optJSONObject(key) ?: return@forEach
                val path = p.stringOrNull("url_path") ?: key.trim()
                if (path.isEmpty() || path in SYSTEM_PANELS) return@forEach
                val component = p.stringOrNull("component_name")
                // Exclude panels with no usable name — HA's internal panels (notfound, _my_redirect,
                // the companion `app`) carry title=null and are not user dashboards. Built-ins keep
                // their friendly label; everything else needs a real API title.
                val title = p.stringOrNull("title") ?: BUILTIN_TITLES[path] ?: return@forEach
                val icon = p.stringOrNull("icon")
                merged[path] = HaDashboard(
                    title = title, urlPath = path, icon = icon,
                    componentName = component,
                    requireAdmin = p.optBoolean("require_admin", false),
                    kind = kindOf(component),
                )
            }
            // 2. UNION with dashboards/list (LIST source — NOT admin-filtered).
            val isAdmin = root.optJSONObject("user")?.optBoolean("is_admin", false) ?: false
            root.optJSONArray("dashboards")?.let { arr ->
                (0 until arr.length()).forEach { i ->
                    val d = arr.optJSONObject(i) ?: return@forEach
                    val path = d.stringOrNull("url_path") ?: return@forEach
                    if (path in SYSTEM_PANELS) return@forEach
                    val reqAdmin = d.optBoolean("require_admin", false)
                    val listTitle = d.stringOrNull("title")
                    val listIcon = d.stringOrNull("icon")
                    val existing = merged[path]
                    if (existing != null) {
                        // present in both → LIST title/icon win, keep PANELS membership (always shown)
                        merged[path] = existing.copy(
                            title = listTitle ?: existing.title,
                            icon = listIcon ?: existing.icon,
                        )
                    } else {
                        // LIST-only (sidebar-hidden) → add, but drop admin-only for non-admin/unknown
                        // and drop entries with no usable name.
                        if (reqAdmin && !isAdmin) return@forEach
                        val title = listTitle ?: return@forEach
                        merged[path] = HaDashboard(
                            title = title, urlPath = path, icon = listIcon,
                            componentName = "lovelace", requireAdmin = reqAdmin, kind = Kind.DASHBOARD,
                        )
                    }
                }
            }
            // 3. Account.
            val account = root.optJSONObject("user")?.let { u ->
                val name = u.stringOrNull("name") ?: return@let null
                HaAccount(name = name, isAdmin = u.optBoolean("is_admin", false))
            }
            HaDiscoveryResult(merged.values.toList(), account)
        }.getOrNull()
    }

    const val DISCOVERY_THROTTLE_MS = 3_000L

    /** Maps a raw discovery error reason (JS message / WS code) to user-facing copy. */
    fun friendlyError(reason: String): String {
        val r = reason.lowercase()
        return when {
            "frontend-not-ready" in r || "unauthor" in r || "not_allowed" in r || "auth" in r ->
                "Log in to Home Assistant, then tap Refresh."
            else -> "Couldn't read dashboards from Home Assistant. Tap Refresh to try again."
        }
    }

    /** Throttle decision for re-firing discovery. Always runs on [force] or an origin change; otherwise
     *  suppresses repeats for the same origin within [throttleMs] of the last fire (login redirect chains
     *  + in-page navigation no longer thrash the chips/hint). */
    fun shouldRunDiscovery(
        force: Boolean,
        currentOrigin: String,
        lastOrigin: String?,
        lastFireAtMs: Long,
        nowMs: Long,
        throttleMs: Long,
    ): Boolean = force || currentOrigin != lastOrigin || (nowMs - lastFireAtMs) >= throttleMs

    /**
     * Resolves the active dashboard path to restore on fragment (re)creation.
     *
     * Returns [storedPath] only when ALL three conditions hold:
     *   1. [storedOrigin] is non-null and equals [currentOrigin] (origin-scoped — stale cache from a
     *      different HA server is never restored).
     *   2. [storedPath] is non-null.
     *   3. [storedPath] is present in [available] (path was removed/renamed → fall back to Overview).
     *
     * Otherwise returns [OVERVIEW_PATH]. Pure JVM — no Android imports; fully unit-testable.
     */
    fun resolveActiveDashboard(
        storedOrigin: String?,
        storedPath: String?,
        currentOrigin: String,
        available: List<HaDashboard>,
    ): String {
        if (storedOrigin == null || storedOrigin != currentOrigin) return OVERVIEW_PATH
        if (storedPath == null) return OVERVIEW_PATH
        val knownPaths = available.map { it.urlPath }.toSet()
        return if (storedPath in knownPaths) storedPath else OVERVIEW_PATH
    }
}
