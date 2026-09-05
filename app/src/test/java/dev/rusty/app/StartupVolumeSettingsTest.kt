package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Spotify "Startup volume" model (issue #10). */
class StartupVolumeSettingsTest {

    @Test
    fun `defaults to full volume`() {
        assertEquals(100, StartupVolumeSettings.DEFAULT_PERCENT)
    }

    @Test
    fun `clamp keeps in-range step values untouched`() {
        assertEquals(10, StartupVolumeSettings.clamp(10))
        assertEquals(55, StartupVolumeSettings.clamp(55))
        assertEquals(100, StartupVolumeSettings.clamp(100))
    }

    @Test
    fun `clamp snaps off-step values to the nearest step`() {
        assertEquals(55, StartupVolumeSettings.clamp(56))
        assertEquals(60, StartupVolumeSettings.clamp(58))
        assertEquals(95, StartupVolumeSettings.clamp(97))
    }

    @Test
    fun `clamp pulls out-of-range values back into range`() {
        // A silent receiver reads as broken, so the floor is 10% and not 0.
        assertEquals(10, StartupVolumeSettings.clamp(0))
        assertEquals(10, StartupVolumeSettings.clamp(-40))
        assertEquals(100, StartupVolumeSettings.clamp(400))
    }

    @Test
    fun `label spells out only the full-volume end`() {
        assertEquals("100% · full", StartupVolumeSettings.label(100))
        assertEquals("65%", StartupVolumeSettings.label(65))
        // A stale pref outside the range still labels something sane.
        assertEquals("10%", StartupVolumeSettings.label(-5))
    }
}
