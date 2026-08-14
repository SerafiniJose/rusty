package dev.rusty.app

import dev.rusty.app.HomeAssistantDashboards.HaDashboard
import dev.rusty.app.HomeAssistantDashboards.OVERVIEW_PATH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantDashboardsTest {

    private val sample = """
        [
          {"id":"a","url_path":"kitchen","title":"Kitchen","require_admin":false,"icon":"mdi:fridge"},
          {"id":"b","url_path":"energy","title":"","show_in_sidebar":true},
          {"id":"c","title":"No Path"}
        ]
    """.trimIndent()

    @Test fun parseSkipsEntriesWithoutUrlPath_andFallsBackTitleToPath() {
        val parsed = HomeAssistantDashboards.parseCachedDashboards(sample)
        assertEquals(listOf("kitchen", "energy"), parsed.map { it.urlPath })
        assertEquals("Kitchen", parsed[0].title)
        assertEquals("energy", parsed[1].title) // empty title → url_path
        assertEquals("mdi:fridge", parsed[0].icon)
    }

    @Test fun parseHandlesNullBlankAndMalformed() {
        assertTrue(HomeAssistantDashboards.parseCachedDashboards(null).isEmpty())
        assertTrue(HomeAssistantDashboards.parseCachedDashboards("").isEmpty())
        assertTrue(HomeAssistantDashboards.parseCachedDashboards("not json").isEmpty())
    }

    @Test fun serializeRoundTrips() {
        val list = listOf(HaDashboard("Kitchen", "kitchen", "mdi:fridge"), HaDashboard("Energy", "energy"))
        val reparsed = HomeAssistantDashboards.parseCachedDashboards(HomeAssistantDashboards.serializeDashboards(list))
        assertEquals(list, reparsed)
    }

    @Test fun cacheRoundTripPreservesNewFields() {
        val list = listOf(
            HaDashboard("Kitchen", "kitchen", "mdi:fridge", componentName = "lovelace",
                requireAdmin = true, kind = HomeAssistantDashboards.Kind.DASHBOARD),
            HaDashboard("Map", "map", null, componentName = "map",
                requireAdmin = false, kind = HomeAssistantDashboards.Kind.APP),
        )
        val reparsed = HomeAssistantDashboards.parseCachedDashboards(
            HomeAssistantDashboards.serializeDashboards(list))
        assertEquals(list, reparsed)
    }

    @Test fun legacyCacheWithoutKindParsesAsUnknown() {
        val legacy = """[{"title":"Kitchen","url_path":"kitchen","icon":"mdi:fridge"}]"""
        val d = HomeAssistantDashboards.parseCachedDashboards(legacy).single()
        assertEquals(HomeAssistantDashboards.Kind.UNKNOWN, d.kind)
        assertEquals(false, d.requireAdmin)
        assertNull(d.componentName)
    }

    @Test fun availableAlwaysLeadsWithOverview() {
        val available = HomeAssistantDashboards.availableFrom(sample)
        assertEquals(OVERVIEW_PATH, available.first().urlPath)
        assertEquals(listOf(OVERVIEW_PATH, "kitchen", "energy"), available.map { it.urlPath })
        assertEquals(listOf(OVERVIEW_PATH), HomeAssistantDashboards.availableFrom(null).map { it.urlPath })
    }

    @Test fun selectedPathsMissingDefaultsToOverviewButEmptyArrayStays() {
        assertEquals(listOf(OVERVIEW_PATH), HomeAssistantDashboards.parseSelectedPaths(null))
        assertEquals(listOf(OVERVIEW_PATH), HomeAssistantDashboards.parseSelectedPaths(""))
        assertEquals(listOf(OVERVIEW_PATH), HomeAssistantDashboards.parseSelectedPaths("garbage"))
        assertEquals(emptyList<String>(), HomeAssistantDashboards.parseSelectedPaths("[]"))   // deliberate clear
    }

    @Test fun selectedPathsRoundTrip() {
        val paths = listOf(OVERVIEW_PATH, "kitchen")
        val json = HomeAssistantDashboards.serializeSelectedPaths(paths)
        assertEquals(paths, HomeAssistantDashboards.parseSelectedPaths(json))
    }

    @Test fun normalizePrunesUnknownAndDedupsWithoutForcingOverview() {
        val available = HomeAssistantDashboards.availableFrom(sample)
        assertEquals(
            listOf("kitchen", "energy"),
            HomeAssistantDashboards.normalizeSelection(listOf("kitchen", "ghost", "kitchen", "energy"), available)
        )
    }

    @Test fun normalizeKeepsOverviewWhenSelectedAndAllowsEmpty() {
        val available = HomeAssistantDashboards.availableFrom(sample)
        assertEquals(listOf(OVERVIEW_PATH, "kitchen"),
            HomeAssistantDashboards.normalizeSelection(listOf(OVERVIEW_PATH, "kitchen"), available))
        assertEquals(emptyList<String>(), HomeAssistantDashboards.normalizeSelection(emptyList(), available))
    }

    @Test fun selectedFromResolvesPathsInOrder() {
        val sel = HomeAssistantDashboards.serializeSelectedPaths(listOf(OVERVIEW_PATH, "energy"))
        val resolved = HomeAssistantDashboards.selectedFrom(sample, sel)
        assertEquals(listOf("Overview", "energy"), resolved.map { it.title })
    }

    @Test fun urlForOverviewIsBaseRoot() {
        assertEquals("http://ha.local:8123",
            HomeAssistantDashboards.urlFor("http://ha.local:8123", HomeAssistantDashboards.OVERVIEW))
        assertEquals("http://ha.local:8123",
            HomeAssistantDashboards.urlFor("http://ha.local:8123/", HomeAssistantDashboards.OVERVIEW))
    }

    @Test fun urlForDashboardJoinsPath() {
        assertEquals("http://ha.local:8123/kitchen",
            HomeAssistantDashboards.urlFor("http://ha.local:8123", HaDashboard("Kitchen", "kitchen")))
        assertEquals("http://ha.local:8123/kitchen",
            HomeAssistantDashboards.urlFor("http://ha.local:8123/", HaDashboard("Kitchen", "/kitchen")))
    }

    @Test fun cacheFreshOnlyWhenOriginMatchesCurrentBase() {
        assertTrue(HomeAssistantDashboards.isCacheFresh("http://ha.local:8123", "http://ha.local:8123"))
        assertFalse(HomeAssistantDashboards.isCacheFresh("http://ha.local:8123", "http://other:8123"))
        assertFalse(HomeAssistantDashboards.isCacheFresh("http://ha.local:8123", null))
        assertFalse(HomeAssistantDashboards.isCacheFresh(null, "http://ha.local:8123"))
    }

    // ---- resolveActiveDashboard tests ----------------------------------------

    private val availableForResolve = listOf(
        HomeAssistantDashboards.OVERVIEW,
        HaDashboard("Kitchen", "kitchen"),
        HaDashboard("Energy", "energy"),
    )

    @Test fun resolveActiveDashboard_matchingOriginAndValidPath_returnsStoredPath() {
        val result = HomeAssistantDashboards.resolveActiveDashboard(
            storedOrigin = "http://ha.local:8123",
            storedPath = "kitchen",
            currentOrigin = "http://ha.local:8123",
            available = availableForResolve,
        )
        assertEquals("kitchen", result)
    }

    @Test fun resolveActiveDashboard_mismatchedOrigin_returnsOverview() {
        val result = HomeAssistantDashboards.resolveActiveDashboard(
            storedOrigin = "http://other.local:8123",
            storedPath = "kitchen",
            currentOrigin = "http://ha.local:8123",
            available = availableForResolve,
        )
        assertEquals(OVERVIEW_PATH, result)
    }

    @Test fun resolveActiveDashboard_matchingOriginButStalePath_returnsOverview() {
        val result = HomeAssistantDashboards.resolveActiveDashboard(
            storedOrigin = "http://ha.local:8123",
            storedPath = "removed-dashboard",
            currentOrigin = "http://ha.local:8123",
            available = availableForResolve,
        )
        assertEquals(OVERVIEW_PATH, result)
    }

    @Test fun resolveActiveDashboard_nullStored_returnsOverview() {
        val result = HomeAssistantDashboards.resolveActiveDashboard(
            storedOrigin = null,
            storedPath = null,
            currentOrigin = "http://ha.local:8123",
            available = availableForResolve,
        )
        assertEquals(OVERVIEW_PATH, result)
    }

    @Test fun glyphForMapsKnownMdiNamesAndFallsBack() {
        assertEquals(HomeAssistantDashboards.HaGlyph.MAP, HomeAssistantDashboards.glyphFor("mdi:map"))
        assertEquals(HomeAssistantDashboards.HaGlyph.ENERGY, HomeAssistantDashboards.glyphFor("mdi:lightning-bolt"))
        assertEquals(HomeAssistantDashboards.HaGlyph.TODO, HomeAssistantDashboards.glyphFor("mdi:format-list-checks"))
        assertEquals(HomeAssistantDashboards.HaGlyph.DASHBOARD, HomeAssistantDashboards.glyphFor("mdi:view-dashboard"))
        assertEquals(HomeAssistantDashboards.HaGlyph.GENERIC, HomeAssistantDashboards.glyphFor("mdi:some-unknown-thing"))
        assertEquals(HomeAssistantDashboards.HaGlyph.GENERIC, HomeAssistantDashboards.glyphFor(null))
    }

    @Test fun overviewIsADashboardKind() {
        assertEquals(HomeAssistantDashboards.Kind.DASHBOARD, HomeAssistantDashboards.OVERVIEW.kind)
    }

    @Test fun friendlyErrorMapsAuthAndReadinessToLoginCopy() {
        val login = "Log in to Home Assistant, then tap Refresh."
        assertEquals(login, HomeAssistantDashboards.friendlyError("frontend-not-ready"))
        assertEquals(login, HomeAssistantDashboards.friendlyError("unauthorized"))
        assertEquals(login, HomeAssistantDashboards.friendlyError("Unauthorized"))
        // Unknown reasons fall back to a generic message (non-empty).
        assertTrue(HomeAssistantDashboards.friendlyError("ECONNRESET").isNotEmpty())
    }

    @Test fun defaultUrlIsTheHaHint() {
        assertEquals("http://homeassistant.local:8123", HomeAssistantDashboards.DEFAULT_URL)
    }

    @Test fun shouldRunDiscoveryForcesOriginChangeAndThrottles() {
        val t = HomeAssistantDashboards.DISCOVERY_THROTTLE_MS
        // force always runs
        assertTrue(HomeAssistantDashboards.shouldRunDiscovery(true, "o", "o", 1000L, 1000L, t))
        // origin change always runs
        assertTrue(HomeAssistantDashboards.shouldRunDiscovery(false, "o2", "o", 1000L, 1100L, t))
        // same origin within throttle window → skip
        assertFalse(HomeAssistantDashboards.shouldRunDiscovery(false, "o", "o", 1000L, 1000L + t - 1, t))
        // same origin past throttle window → run
        assertTrue(HomeAssistantDashboards.shouldRunDiscovery(false, "o", "o", 1000L, 1000L + t, t))
        // no prior fire (lastOrigin null) → run
        assertTrue(HomeAssistantDashboards.shouldRunDiscovery(false, "o", null, 0L, 0L, t))
    }

    // ---- activePathFor ------------------------------------------------------

    private val avail = listOf(
        HomeAssistantDashboards.OVERVIEW,
        HomeAssistantDashboards.HaDashboard(title = "Mapa", urlPath = "map"),
        HomeAssistantDashboards.HaDashboard(title = "security", urlPath = "security"),
        HomeAssistantDashboards.HaDashboard(title = "home", urlPath = "home"),
    )

    @Test fun activePath_rootIsOverview() {
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH,
            HomeAssistantDashboards.activePathFor("/", "home", avail))
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH,
            HomeAssistantDashboards.activePathFor("", "home", avail))
    }

    @Test fun activePath_theLearntOverviewPanelIsOverview() {
        // HA redirects the root to its default panel, so landing there IS the app's Overview.
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH,
            HomeAssistantDashboards.activePathFor("/home/overview", "home", avail))
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH,
            HomeAssistantDashboards.activePathFor("/home", "home", avail))
    }

    @Test fun activePath_knownDashboardMatchesOnFirstSegment() {
        assertEquals("security", HomeAssistantDashboards.activePathFor("/security", "home", avail))
        assertEquals("map", HomeAssistantDashboards.activePathFor("/map/0", "home", avail))
    }

    @Test fun activePath_unknownPathIsNull() {
        // Somewhere the chips cannot represent — no chip should be marked active.
        assertNull(HomeAssistantDashboards.activePathFor("/config/system", "home", avail))
        assertNull(HomeAssistantDashboards.activePathFor(null, "home", avail))
    }

    @Test fun activePath_ignoresQueryAndFragment() {
        assertEquals("security",
            HomeAssistantDashboards.activePathFor("/security?edit=1#top", "home", avail))
    }

    @Test fun activePath_unlearntOverviewPanelStillMatchesRealDashboards() {
        // The Overview panel is learnt from a landing, so it is null until one happens; that must
        // not swallow every path, and the root is Overview whether or not it has been learnt.
        assertEquals("security", HomeAssistantDashboards.activePathFor("/security", "", avail))
        assertEquals(HomeAssistantDashboards.OVERVIEW_PATH,
            HomeAssistantDashboards.activePathFor("/", null, avail))
    }

    @Test fun activePath_unlearntOverviewPanelReadsAsThatPanelNotOverview() {
        // Honest rather than guessed: before the app has seen where its Overview lands, `home` is
        // just a panel. Guessing it (the shipped bug guessed 'lovelace') lit the wrong chip.
        assertEquals("home", HomeAssistantDashboards.activePathFor("/home/overview", null, avail))
    }
}
