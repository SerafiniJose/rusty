package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardDismissTest {
    // Field at [100,200)×[50,90) on screen.
    private fun outside(x: Float, y: Float) = KeyboardDismiss.tapLandsOutside(100, 50, 200, 90, x, y)

    @Test fun tapInsideFieldKeepsKeyboard() {
        assertFalse(outside(150f, 70f))
        assertFalse(outside(100f, 50f)) // top-left edge is inside
    }

    @Test fun tapOutsideFieldHidesKeyboard() {
        assertTrue(outside(99f, 70f))
        assertTrue(outside(200f, 70f)) // right edge is exclusive
        assertTrue(outside(150f, 49f))
        assertTrue(outside(150f, 90f))
    }
}
