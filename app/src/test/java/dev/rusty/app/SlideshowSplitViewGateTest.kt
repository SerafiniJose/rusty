package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Split view pairs two portrait photos into a horizontal pair of half-width panes, so it is only
 * ever right in a landscape viewport. On the portrait test tablet — and any wall-mounted portrait
 * frame — pairing would shrink both photos into mostly blur.
 */
class ImmichSplitViewGateTest {

    @Test
    fun `landscape viewport with the pref on pairs`() {
        assertTrue(SlideshowStage.shouldPairPortraits(prefEnabled = true, width = 1920, height = 1080))
    }

    @Test
    fun `portrait viewport never pairs even with the pref on`() {
        assertFalse(SlideshowStage.shouldPairPortraits(prefEnabled = true, width = 1200, height = 1920))
    }

    @Test
    fun `a square viewport goes solo`() {
        // Strict greater-than: with nothing to gain from the split, prefer the larger single photo.
        assertFalse(SlideshowStage.shouldPairPortraits(prefEnabled = true, width = 1080, height = 1080))
    }

    @Test
    fun `before the first layout pass the stage is unmeasured and goes solo`() {
        // width == height == 0 until the stage is laid out; the first slide can arrive in that
        // window, and a 0x0 stage must not be read as "landscape".
        assertFalse(SlideshowStage.shouldPairPortraits(prefEnabled = true, width = 0, height = 0))
    }

    @Test
    fun `the pref still wins - a landscape viewport with the pref off goes solo`() {
        assertFalse(SlideshowStage.shouldPairPortraits(prefEnabled = false, width = 1920, height = 1080))
        assertFalse(SlideshowStage.shouldPairPortraits(prefEnabled = false, width = 1080, height = 1920))
    }
}
