package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure in-app (SPA) dashboard-navigation logic: the [HomeAssistantUrl.pathWithQuery]
 * helper and the [HomeAssistantNav] decision + script builder.
 */
class HomeAssistantNavTest {

    // ---- HomeAssistantUrl.pathWithQuery -------------------------------------

    @Test fun pathWithQuery_root_yieldsSlash() {
        assertEquals("/", HomeAssistantUrl.pathWithQuery("http://192.168.2.78:8123"))
        assertEquals("/", HomeAssistantUrl.pathWithQuery("http://192.168.2.78:8123/"))
    }

    @Test fun pathWithQuery_dashboardPath() {
        assertEquals(
            "/b1039b78_baby-buddy-dashboard",
            HomeAssistantUrl.pathWithQuery("http://192.168.2.78:8123/b1039b78_baby-buddy-dashboard"),
        )
    }

    @Test fun pathWithQuery_preservesQueryAndFragment() {
        assertEquals(
            "/lovelace/0?edit=1#view",
            HomeAssistantUrl.pathWithQuery("http://ha.local:8123/lovelace/0?edit=1#view"),
        )
    }

    @Test fun pathWithQuery_invalidUrl_null() {
        assertEquals(null, HomeAssistantUrl.pathWithQuery(null))
        assertEquals(null, HomeAssistantUrl.pathWithQuery("   "))
    }

    // ---- HomeAssistantNav.shouldSpaNavigate ---------------------------------

    private val origin = "http://192.168.2.78:8123"

    @Test fun spa_whenReadyAndOnOrigin() {
        assertTrue(HomeAssistantNav.shouldSpaNavigate(true, "$origin/lovelace/0", origin))
    }

    @Test fun noSpa_whenFrontendNotReady() {
        // Cold load / just after a full reload: must hard-load, not pushState.
        assertFalse(HomeAssistantNav.shouldSpaNavigate(false, "$origin/lovelace/0", origin))
    }

    @Test fun noSpa_whenOffOrigin() {
        // Login redirect / foreign page: pushState can't route HA's frontend.
        assertFalse(HomeAssistantNav.shouldSpaNavigate(true, "https://login.example.com/auth", origin))
        assertFalse(HomeAssistantNav.shouldSpaNavigate(true, null, origin))
    }

    // ---- HomeAssistantNav.isAuthPath ----------------------------------------

    @Test fun isAuthPath_loginAndOnboarding() {
        assertTrue(HomeAssistantNav.isAuthPath("$origin/auth/authorize?response_type=code"))
        assertTrue(HomeAssistantNav.isAuthPath("$origin/onboarding.html"))
    }

    @Test fun isAuthPath_normalDashboardsAreNot() {
        assertFalse(HomeAssistantNav.isAuthPath("$origin/lovelace/0"))
        assertFalse(HomeAssistantNav.isAuthPath("$origin/b1039b78_baby-buddy-dashboard"))
        assertFalse(HomeAssistantNav.isAuthPath(origin))
    }

    // ---- HomeAssistantNav.navigateScript ------------------------------------

    @Test fun navigateScript_embedsPathAndDispatchesEvent() {
        val js = HomeAssistantNav.navigateScript("/b1039b78_baby-buddy-dashboard")
        assertTrue(js.contains("history.pushState(null,'','/b1039b78_baby-buddy-dashboard')"))
        assertTrue(js.contains("location-changed"))
    }

    @Test fun navigateScript_escapesSingleQuotes() {
        // A path can't normally contain a raw quote, but the builder must never break out of the string.
        val js = HomeAssistantNav.navigateScript("/a'b")
        assertTrue(js.contains("""/a\'b"""))
    }

    // ---- HomeAssistantNav.selectedThemeJs -----------------------------------

    @Test fun selectedThemeJsRemovesWhenDefaultAuto() {
        // No theme + auto mode = truly backend-selected → remove the override entirely.
        val remove = "try{localStorage.removeItem('selectedTheme');}catch(e){}"
        assertEquals(remove, HomeAssistantNav.selectedThemeJs(null, null))
        assertEquals(remove, HomeAssistantNav.selectedThemeJs("   ", HomeAssistantNav.MODE_AUTO))
    }

    @Test fun selectedThemeJsSetsThemeObjectForCustomName() {
        // HA reads selectedTheme as a {theme,dark} object (bare names still work via HA back-compat,
        // but the object form is what lets us also carry the light/dark flag).
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"Noctis\"}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs("Noctis", HomeAssistantNav.MODE_AUTO))
    }

    @Test fun selectedThemeJsDefaultThemeForcedDark() {
        // "Default" theme (no name) with Dark mode forces HA's built-in default theme dark.
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"default\",\"dark\":true}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs(null, HomeAssistantNav.MODE_DARK))
    }

    @Test fun selectedThemeJsDefaultThemeForcedLight() {
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"default\",\"dark\":false}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs(null, HomeAssistantNav.MODE_LIGHT))
    }

    @Test fun selectedThemeJsCustomThemeForcedDark() {
        assertEquals(
            "try{localStorage.setItem('selectedTheme',JSON.stringify({\"theme\":\"Noctis\",\"dark\":true}));}catch(e){}",
            HomeAssistantNav.selectedThemeJs("Noctis", HomeAssistantNav.MODE_DARK))
    }

    @Test fun selectedThemeJsEscapesQuotesInName() {
        // A name containing a quote must stay valid JS (JSONObject.quote escapes it).
        val js = HomeAssistantNav.selectedThemeJs("My \"Dark\" Theme", HomeAssistantNav.MODE_AUTO)
        assertTrue(js.contains("{\"theme\":\"My \\\"Dark\\\" Theme\"}"))
    }

    // ---- parseCssColorToArgb ------------------------------------------------

    @Test fun parseCssColor_rgb() {
        // 0x0B0A0C — HA's near-black default background reported as rgb by the WebView.
        assertEquals(0xFF0B0A0C.toInt(), HomeAssistantNav.parseCssColorToArgb("rgb(11, 10, 12)"))
    }

    @Test fun parseCssColor_rgbNoSpaces() {
        assertEquals(0xFFFFFFFF.toInt(), HomeAssistantNav.parseCssColorToArgb("rgb(255,255,255)"))
    }

    @Test fun parseCssColor_rgbaDropsAlphaToOpaque() {
        // A translucent report must still yield a fully opaque strip (alpha forced to FF).
        assertEquals(0xFF102030.toInt(), HomeAssistantNav.parseCssColorToArgb("rgba(16, 32, 48, 0.5)"))
    }

    @Test fun parseCssColor_hexLong() {
        assertEquals(0xFF1C1C1E.toInt(), HomeAssistantNav.parseCssColorToArgb("#1c1c1e"))
    }

    @Test fun parseCssColor_hexShortExpands() {
        assertEquals(0xFFFFFFFF.toInt(), HomeAssistantNav.parseCssColorToArgb("#fff"))
        assertEquals(0xFF112233.toInt(), HomeAssistantNav.parseCssColorToArgb("#123"))
    }

    @Test fun parseCssColor_rejectsGarbage() {
        assertNull(HomeAssistantNav.parseCssColorToArgb(null))
        assertNull(HomeAssistantNav.parseCssColorToArgb(""))
        assertNull(HomeAssistantNav.parseCssColorToArgb("   "))
        assertNull(HomeAssistantNav.parseCssColorToArgb("transparent"))
        assertNull(HomeAssistantNav.parseCssColorToArgb("#12"))
        assertNull(HomeAssistantNav.parseCssColorToArgb("rgb(1, 2)"))
    }

    // ---- reportThemeColorsJs ------------------------------------------------

    @Test fun reportThemeColorsJs_reportsBothColorsAndIsSelfContained() {
        val js = HomeAssistantNav.reportThemeColorsJs()
        // Reports background + text through the discovery bridge, from HA's theme tokens.
        assertTrue(js.contains("RustyHaBridge.onBackgroundColor"))
        assertTrue(js.contains("RustyHaBridge.onTextColor"))
        assertTrue(js.contains("--primary-background-color"))
        assertTrue(js.contains("--primary-text-color"))
        // Never throws into the bridge.
        assertTrue(js.contains("catch"))
    }
}
