package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowDisableTest {
    @Test fun disablingWhileImmichThemeSelectedFallsBackToClock() {
        assertEquals(
            ScreensaverThemeId.CLOCK,
            SlideshowDisable.themeAfterDisable(ScreensaverThemeId.SLIDESHOW)
        )
    }

    @Test fun disablingLeavesOtherThemesUntouched() {
        assertEquals(ScreensaverThemeId.OLED, SlideshowDisable.themeAfterDisable(ScreensaverThemeId.OLED))
        assertEquals(ScreensaverThemeId.CANVAS, SlideshowDisable.themeAfterDisable(ScreensaverThemeId.CANVAS))
        assertEquals(ScreensaverThemeId.CLOCK, SlideshowDisable.themeAfterDisable(ScreensaverThemeId.CLOCK))
    }

    @Test fun initialThemeEnabledWithImmichStoredIsUnchanged() {
        assertEquals(
            ScreensaverThemeId.SLIDESHOW,
            SlideshowDisable.initialTheme(ScreensaverThemeId.SLIDESHOW, enabled = true)
        )
    }

    @Test fun initialThemeDisabledWithImmichStoredFallsBackToClock() {
        assertEquals(
            ScreensaverThemeId.CLOCK,
            SlideshowDisable.initialTheme(ScreensaverThemeId.SLIDESHOW, enabled = false)
        )
    }

    @Test fun initialThemeDisabledWithOtherThemeStoredIsUnchanged() {
        assertEquals(
            ScreensaverThemeId.OLED,
            SlideshowDisable.initialTheme(ScreensaverThemeId.OLED, enabled = false)
        )
    }
}
