package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverThemeIdTest {

    @Test fun defaultsToClockForNullOrUnknown() {
        assertEquals(ScreensaverThemeId.CLOCK, ScreensaverThemeId.fromPrefValue(null))
        assertEquals(ScreensaverThemeId.CLOCK, ScreensaverThemeId.fromPrefValue("bogus"))
    }

    @Test fun legacyPartyStillFallsBackToClock() {
        assertEquals(ScreensaverThemeId.CLOCK, ScreensaverThemeId.fromPrefValue("PARTY"))
    }

    @Test fun roundTripsPrefValue() {
        for (id in ScreensaverThemeId.values()) {
            assertEquals(id, ScreensaverThemeId.fromPrefValue(id.prefValue))
        }
    }

    @Test fun canvasRoundTrips() {
        assertEquals(ScreensaverThemeId.CANVAS, ScreensaverThemeId.fromPrefValue("CANVAS"))
    }
}
