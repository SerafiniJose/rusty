package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsSectionModelTest {

    // ---- Slideshow · Server ----
    @Test fun serverUnconfiguredWhenNoUrl() {
        val s = SlideshowSummaries.server(null, keySaved = true, verified = true, accountName = null)
        assertEquals("Not configured", s.text); assertFalse(s.active)
    }
    @Test fun serverUnconfiguredWhenNoKey() {
        val s = SlideshowSummaries.server("http://192.168.1.10:2283", keySaved = false, verified = false, accountName = null)
        assertEquals("Not configured", s.text); assertFalse(s.active)
    }
    @Test fun serverConfiguredShowsHostWithoutScheme() {
        val s = SlideshowSummaries.server("http://192.168.1.10:2283/", keySaved = true, verified = true, accountName = null)
        assertEquals("192.168.1.10:2283 · key saved", s.text); assertTrue(s.active)
    }
    @Test fun serverSignedInShowsNameAndHost() {
        val s = SlideshowSummaries.server("http://192.168.1.10:2283/", keySaved = true, verified = true, accountName = "Jose")
        assertEquals("Jose · 192.168.1.10:2283", s.text); assertTrue(s.active)
    }
    // A stored-but-not-yet-verified key (fresh save, or one whose verification FAILED) must never
    // read as the green "key saved" — otherwise a wrong key looks connected. It stays muted until a
    // Save actually authenticates.
    @Test fun serverKeySavedButUnverifiedIsMuted() {
        val s = SlideshowSummaries.server("http://192.168.1.10:2283/", keySaved = true, verified = false, accountName = null)
        assertEquals("Key not verified", s.text); assertFalse(s.active)
    }
    // Even a leftover account name can't promote an unverified key back to "signed in".
    @Test fun serverUnverifiedIgnoresStaleAccountName() {
        val s = SlideshowSummaries.server("http://192.168.1.10:2283/", keySaved = true, verified = false, accountName = "Jose")
        assertEquals("Key not verified", s.text); assertFalse(s.active)
    }

    // ---- Slideshow · Filters ----
    @Test fun filtersUnconfigured() {
        val s = SlideshowSummaries.filters(configured = false, albums = 2, people = 0, tags = 0)
        assertEquals("Set up the server first", s.text); assertFalse(s.active)
    }
    @Test fun filtersWholeLibraryWhenNothingSelected() {
        val s = SlideshowSummaries.filters(configured = true, albums = 0, people = 0, tags = 0)
        assertEquals("Whole library", s.text); assertFalse(s.active)
    }
    @Test fun filtersListsOnlyNonZeroCategories() {
        val s = SlideshowSummaries.filters(configured = true, albums = 2, people = 1, tags = 0)
        assertEquals("2 albums · 1 person", s.text); assertTrue(s.active)
    }

    // ---- Slideshow · Display ----
    @Test fun displayListsEnabledOverlays() {
        val s = SlideshowSummaries.display(45, clock = true, info = true, zoom = false, split = false)
        assertEquals("Every 45 seconds · Clock, Photo info", s.text); assertFalse(s.active)
    }
    @Test fun displayNoOverlays() {
        val s = SlideshowSummaries.display(120, clock = false, info = false, zoom = false, split = false)
        assertEquals("Every 2 minutes · no overlays", s.text); assertFalse(s.active)
    }

    // ---- HA · Server ----
    @Test fun haServerSignedOut() {
        val s = HaSummaries.server(signedIn = false, accountName = "Jose", host = "homeassistant.local:8123")
        assertEquals("Not signed in", s.text); assertFalse(s.active)
    }
    @Test fun haServerSignedInWithName() {
        val s = HaSummaries.server(signedIn = true, accountName = "Jose", host = "homeassistant.local:8123")
        assertEquals("Jose · homeassistant.local:8123", s.text); assertTrue(s.active)
    }
    @Test fun haServerSignedInWithoutName() {
        val s = HaSummaries.server(signedIn = true, accountName = null, host = null)
        assertEquals("Signed in · Home Assistant", s.text); assertTrue(s.active)
    }

    // ---- HA · Dashboards / Apps ----
    @Test fun haItemsSignedOut() {
        val s = HaSummaries.items(signedIn = false, selected = 3, total = 6)
        assertEquals("Sign in first", s.text); assertFalse(s.active)
    }
    @Test fun haItemsNoneFound() {
        val s = HaSummaries.items(signedIn = true, selected = 0, total = 0)
        assertEquals("None found — tap Refresh", s.text); assertFalse(s.active)
    }
    @Test fun haItemsCounts() {
        val s = HaSummaries.items(signedIn = true, selected = 2, total = 6)
        assertEquals("2 of 6 shown in Rusty", s.text); assertTrue(s.active)
    }
    @Test fun haItemsZeroSelectedIsInactive() {
        val s = HaSummaries.items(signedIn = true, selected = 0, total = 6)
        assertEquals("0 of 6 shown in Rusty", s.text); assertFalse(s.active)
    }

    // ---- HA · Appearance (Theme) ----
    @Test fun haThemeNotSignedIn() {
        val s = HaSummaries.theme(signedIn = false, selectedName = null, mode = HomeAssistantNav.MODE_AUTO)
        assertEquals("Sign in first", s.text); assertFalse(s.active)
    }
    @Test fun haThemeDefaultAutoIsInactive() {
        // Default theme + Auto = no override → shown as the neutral default.
        val s = HaSummaries.theme(signedIn = true, selectedName = null, mode = HomeAssistantNav.MODE_AUTO)
        assertEquals("Default", s.text); assertFalse(s.active)
    }
    @Test fun haThemeDefaultDarkIsActive() {
        val s = HaSummaries.theme(signedIn = true, selectedName = null, mode = HomeAssistantNav.MODE_DARK)
        assertEquals("Default · Dark", s.text); assertTrue(s.active)
    }
    @Test fun haThemeDefaultLightIsActive() {
        val s = HaSummaries.theme(signedIn = true, selectedName = null, mode = HomeAssistantNav.MODE_LIGHT)
        assertEquals("Default · Light", s.text); assertTrue(s.active)
    }
    @Test fun haThemeNamedAutoIsActive() {
        val s = HaSummaries.theme(signedIn = true, selectedName = "Noctis", mode = HomeAssistantNav.MODE_AUTO)
        assertEquals("Noctis", s.text); assertTrue(s.active)
    }
    @Test fun haThemeNamedDarkShowsMode() {
        val s = HaSummaries.theme(signedIn = true, selectedName = "Noctis", mode = HomeAssistantNav.MODE_DARK)
        assertEquals("Noctis · Dark", s.text); assertTrue(s.active)
    }
}
