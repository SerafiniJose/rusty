package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Spotify "Crossfade" model: whole seconds, 0 = gapless, capped like the official client. */
class CrossfadeSettingsTest {

    @Test
    fun `defaults to a 4 second crossfade so the feature is on out of the box`() {
        assertEquals(4, CrossfadeSettings.DEFAULT_SECONDS)
        assertEquals("crossfade_seconds", CrossfadeSettings.KEY_CROSSFADE_SECONDS)
    }

    @Test
    fun `clamp keeps in-range values untouched`() {
        assertEquals(0, CrossfadeSettings.clamp(0))
        assertEquals(6, CrossfadeSettings.clamp(6))
        assertEquals(12, CrossfadeSettings.clamp(12))
    }

    @Test
    fun `clamp pulls out-of-range values back into range`() {
        // The native player caps at 12 s too; the setting never asks for more than it can get.
        assertEquals(0, CrossfadeSettings.clamp(-5))
        assertEquals(12, CrossfadeSettings.clamp(13))
        assertEquals(12, CrossfadeSettings.clamp(400))
    }

    @Test
    fun `label names the off state and pluralises seconds`() {
        assertEquals("Off · gapless", CrossfadeSettings.label(0))
        assertEquals("1 second", CrossfadeSettings.label(1))
        assertEquals("6 seconds", CrossfadeSettings.label(6))
        assertEquals("12 seconds", CrossfadeSettings.label(12))
        // A stale or hand-edited pref still labels something sane.
        assertEquals("12 seconds", CrossfadeSettings.label(99))
    }
}
