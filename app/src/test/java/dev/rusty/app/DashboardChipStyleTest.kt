package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [DashboardChipStyle] — the pure half of the icon-only dashboard chip design: a
 * chip shows no text by default (a round icon pill, matching the settings / app-selector buttons
 * beside it) and expands to its full label only while it is the ACTIVE dashboard or holds D-pad
 * focus (so a TV user can read what they are about to select).
 */
class DashboardChipStyleTest {

    @Test fun label_hiddenByDefault() {
        assertEquals("", DashboardChipStyle.label("Security", active = false, focused = false))
    }

    @Test fun label_shownOnTheActiveChip() {
        assertEquals("Security", DashboardChipStyle.label("Security", active = true, focused = false))
    }

    @Test fun label_shownWhileFocused() {
        // D-pad users have no hover: focus is their preview of what a click would select.
        assertEquals("Mapa", DashboardChipStyle.label("Mapa", active = false, focused = true))
    }

    @Test fun label_activeAndFocusedIsStillJustTheTitle() {
        assertEquals("Overview", DashboardChipStyle.label("Overview", active = true, focused = true))
    }
}
