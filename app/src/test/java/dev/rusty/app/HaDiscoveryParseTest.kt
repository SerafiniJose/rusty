package dev.rusty.app

import dev.rusty.app.HomeAssistantDashboards.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaDiscoveryParseTest {

    private fun payload(panels: String, dashboards: String = "null", user: String = "null") =
        """{"panels":$panels,"dashboards":$dashboards,"user":$user}"""

    private val panels = """
        {
          "lovelace": {"component_name":"lovelace","title":null,"url_path":"lovelace"},
          "kitchen": {"component_name":"lovelace","title":"Kitchen","icon":"mdi:fridge","url_path":"kitchen"},
          "map": {"component_name":"map","title":null,"url_path":"map"},
          "babybuddy": {"component_name":"iframe","title":"Baby Buddy","icon":"mdi:baby-carriage","url_path":"babybuddy"},
          "config": {"component_name":"config","title":null,"url_path":"config"}
        }
    """.trimIndent()

    @Test fun nullOnMissingOrMalformedPanels() {
        assertNull(HomeAssistantDashboards.parseDiscoveryOrNull(null))
        assertNull(HomeAssistantDashboards.parseDiscoveryOrNull(""))
        assertNull(HomeAssistantDashboards.parseDiscoveryOrNull("not json"))
        assertNull(HomeAssistantDashboards.parseDiscoveryOrNull("""{"dashboards":[],"user":null}"""))
    }

    @Test fun parsesPanelsSkipsSystemReadsComponentAndKind() {
        val r = HomeAssistantDashboards.parseDiscoveryOrNull(payload(panels))!!
        val byPath = r.dashboards.associateBy { it.urlPath }
        assertFalse(byPath.containsKey("lovelace"))
        assertFalse(byPath.containsKey("config"))
        assertEquals(Kind.DASHBOARD, byPath.getValue("kitchen").kind)
        assertEquals(Kind.APP, byPath.getValue("map").kind)
        assertEquals(Kind.APP, byPath.getValue("babybuddy").kind)
    }

    @Test fun cleansBuiltinTitles() {
        val r = HomeAssistantDashboards.parseDiscoveryOrNull(payload(panels))!!
        assertEquals("Map", r.dashboards.first { it.urlPath == "map" }.title)   // was raw "map"
    }

    @Test fun unionAddsSidebarHiddenDashboardsFromList() {
        // "secret" is NOT in get_panels (hidden from sidebar) but IS in dashboards/list.
        val dashboards = """[{"url_path":"secret","title":"Secret","require_admin":false}]"""
        val r = HomeAssistantDashboards.parseDiscoveryOrNull(payload(panels, dashboards))!!
        assertTrue(r.dashboards.any { it.urlPath == "secret" && it.kind == Kind.DASHBOARD })
    }

    @Test fun listTitleAndIconWinForEntriesInBoth() {
        val dashboards = """[{"url_path":"kitchen","title":"Kitchen Pro","icon":"mdi:chef-hat"}]"""
        val r = HomeAssistantDashboards.parseDiscoveryOrNull(payload(panels, dashboards))!!
        val kitchen = r.dashboards.first { it.urlPath == "kitchen" }
        assertEquals("Kitchen Pro", kitchen.title)
        assertEquals("mdi:chef-hat", kitchen.icon)
    }

    @Test fun adminOnlyListEntryHiddenForNonAdminAndUnknown() {
        val dashboards = """[{"url_path":"secret","title":"Secret","require_admin":true}]"""
        // user null (unknown) → drop require_admin LIST entry
        assertFalse(HomeAssistantDashboards.parseDiscoveryOrNull(payload(panels, dashboards))!!
            .dashboards.any { it.urlPath == "secret" })
        // non-admin → drop
        assertFalse(HomeAssistantDashboards.parseDiscoveryOrNull(
            payload(panels, dashboards, """{"name":"Guest","is_admin":false}"""))!!
            .dashboards.any { it.urlPath == "secret" })
        // admin → keep
        assertTrue(HomeAssistantDashboards.parseDiscoveryOrNull(
            payload(panels, dashboards, """{"name":"Jose","is_admin":true}"""))!!
            .dashboards.any { it.urlPath == "secret" })
    }

    @Test fun parsesAccount() {
        val r = HomeAssistantDashboards.parseDiscoveryOrNull(
            payload(panels, "null", """{"name":"Jose","is_admin":true}"""))!!
        assertEquals("Jose", r.account!!.name)
        assertTrue(r.account!!.isAdmin)
        assertNull(HomeAssistantDashboards.parseDiscoveryOrNull(payload(panels))!!.account)
    }

    @Test fun emptyPanelsObjectIsNonNullEmpty() {
        val r = HomeAssistantDashboards.parseDiscoveryOrNull("""{"panels":{}}""")
        assertNotNull(r); assertTrue(r!!.dashboards.isEmpty()); assertNull(r.account)
    }

    @Test fun excludesNullNamedInternalPanelsButKeepsBuiltinsAndTitled() {
        // Real HA: internal panels (notfound, _my_redirect, the companion "app") send title=null.
        // On Android, org.json.optString() yields the literal string "null" for a JSON null (the JVM
        // org.json here yields ""), so we test BOTH: JSON null AND the literal "null" artifact.
        val p = """
            {
              "kitchen": {"component_name":"lovelace","title":"Kitchen","url_path":"kitchen"},
              "map": {"component_name":"map","title":null,"url_path":"map"},
              "notfound": {"component_name":"notfound","title":null,"url_path":"notfound"},
              "_my_redirect": {"component_name":"my","title":null,"url_path":"_my_redirect"},
              "app": {"component_name":"app","title":"null","icon":"null","url_path":"app"}
            }
        """.trimIndent()
        val paths = HomeAssistantDashboards.parseDiscoveryOrNull("""{"panels":$p}""")!!
            .dashboards.map { it.urlPath }.toSet()
        assertTrue("real-titled panel kept", paths.contains("kitchen"))
        assertTrue("builtin (null title → Map) kept", paths.contains("map"))
        assertFalse("JSON-null title internal panel excluded", paths.contains("notfound"))
        assertFalse("JSON-null title redirect excluded", paths.contains("_my_redirect"))
        assertFalse("literal \"null\" title (Android artifact) excluded", paths.contains("app"))
    }
}
