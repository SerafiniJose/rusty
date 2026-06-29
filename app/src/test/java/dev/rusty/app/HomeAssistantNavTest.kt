package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
