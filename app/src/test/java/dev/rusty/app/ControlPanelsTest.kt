package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the published wire vocabulary of `GET /api/state` / `POST /api/panel`. These strings are
 * spoken by every control page already loaded in someone's browser, so a rename that changes one
 * must fail here rather than ship.
 */
class ControlPanelsTest {

    @Test
    fun `panel wire values are the published contract`() {
        assertEquals("spotify", ControlPanelId.SPOTIFY.wire)
        assertEquals("home_assistant", ControlPanelId.HOME_ASSISTANT.wire)
        assertEquals("dlna", ControlPanelId.DLNA.wire)
        assertEquals("lockscreen", ControlPanelId.LOCKSCREEN.wire)
    }

    @Test
    fun `every panel round-trips through its wire value`() {
        for (panel in ControlPanelId.values()) {
            assertEquals(panel, ControlPanelId.fromWire(panel.wire))
        }
    }

    @Test
    fun `unknown and absent wire values parse to null`() {
        assertNull(ControlPanelId.fromWire("screensaver"))
        assertNull(ControlPanelId.fromWire("SPOTIFY"))
        assertNull(ControlPanelId.fromWire(""))
        assertNull(ControlPanelId.fromWire(null))
    }

    @Test
    fun `every feature has exactly one panel and maps back to it`() {
        for (feature in FeatureId.values()) {
            assertEquals(feature, ControlPanelId.of(feature).featureId)
        }
    }

    @Test
    fun `lockscreen is the only panel without a feature`() {
        assertNull(ControlPanelId.LOCKSCREEN.featureId)
        assertEquals(
            listOf(ControlPanelId.LOCKSCREEN),
            ControlPanelId.values().filter { it.featureId == null },
        )
    }

    @Test
    fun `lockscreen theme wire values are the published contract`() {
        assertEquals("clock", ControlLockscreenThemes.wire(ScreensaverThemeId.CLOCK))
        assertEquals("oled", ControlLockscreenThemes.wire(ScreensaverThemeId.OLED))
        assertEquals("canvas", ControlLockscreenThemes.wire(ScreensaverThemeId.CANVAS))
        assertEquals("slideshow", ControlLockscreenThemes.wire(ScreensaverThemeId.SLIDESHOW))
    }

    @Test
    fun `every theme round-trips through its wire value`() {
        for (theme in ScreensaverThemeId.values()) {
            assertEquals(theme, ControlLockscreenThemes.fromWire(ControlLockscreenThemes.wire(theme)))
        }
    }

    @Test
    fun `unknown and absent theme wire values parse to null`() {
        assertNull(ControlLockscreenThemes.fromWire("photos"))
        assertNull(ControlLockscreenThemes.fromWire("CLOCK"))
        assertNull(ControlLockscreenThemes.fromWire(null))
    }

    @Test
    fun `slideshow theme is selectable only while the feature is enabled`() {
        assertEquals(
            listOf(
                ScreensaverThemeId.CLOCK,
                ScreensaverThemeId.OLED,
                ScreensaverThemeId.CANVAS,
                ScreensaverThemeId.SLIDESHOW,
            ),
            ControlLockscreenThemes.selectable(slideshowEnabled = true),
        )
        assertEquals(
            listOf(ScreensaverThemeId.CLOCK, ScreensaverThemeId.OLED, ScreensaverThemeId.CANVAS),
            ControlLockscreenThemes.selectable(slideshowEnabled = false),
        )
    }

    /** The remote's selectable set must agree with the in-app disable policy, or the phone would
     *  offer a theme the device would immediately heal away from. */
    @Test
    fun `selectable set agrees with SlideshowDisable`() {
        for (theme in ControlLockscreenThemes.selectable(slideshowEnabled = false)) {
            assertEquals(theme, SlideshowDisable.themeAfterDisable(theme))
        }
    }
}
