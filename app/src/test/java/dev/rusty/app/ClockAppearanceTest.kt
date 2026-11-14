package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockAppearanceTest {

    @Test fun `unknown backdrop falls back to ambient`() {
        assertEquals(ClockBackdrop.AMBIENT, ClockBackdrop.fromPrefValue(null))
        assertEquals(ClockBackdrop.AMBIENT, ClockBackdrop.fromPrefValue("bogus"))
    }

    @Test fun `backdrops round-trip through their pref value`() {
        for (backdrop in ClockBackdrop.values()) {
            assertEquals(backdrop, ClockBackdrop.fromPrefValue(backdrop.prefValue))
        }
    }

    @Test fun `paper is the only light backdrop`() {
        assertTrue(ClockBackdrop.PAPER.isLight)
        for (backdrop in ClockBackdrop.values().filter { it != ClockBackdrop.PAPER }) {
            assertFalse(backdrop.isLight)
        }
    }

    @Test fun `scrim snaps to the persisted five percent steps and clamps`() {
        assertEquals(0, ClockAppearance.snapScrimPercent(-20))
        assertEquals(40, ClockAppearance.snapScrimPercent(42))
        assertEquals(80, ClockAppearance.snapScrimPercent(100))
    }
}
