package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraBehaviorPrefsTest {

    @Test
    fun `refresh steps are Off 5 10 15 30 60`() {
        assertEquals(listOf(0, 5, 10, 15, 30, 60), CameraBehaviorPrefs.REFRESH_STEPS_S)
    }

    @Test
    fun `refreshIndex maps a known value and snaps an unknown one to 10 s`() {
        assertEquals(3, CameraBehaviorPrefs.refreshIndex(15))
        assertEquals(2, CameraBehaviorPrefs.refreshIndex(7))
        assertEquals(15, CameraBehaviorPrefs.refreshSecondsAt(3))
    }

    @Test
    fun `refresh label reads Off or seconds with a space`() {
        assertEquals("Off", CameraBehaviorPrefs.label(0))
        assertEquals("10 s", CameraBehaviorPrefs.label(10))
    }

    @Test
    fun `grid layout parses its pref value and defaults to ALL`() {
        assertEquals(GridLayoutChoice.PAGES_6, CameraBehaviorPrefs.gridLayoutFrom("6"))
        assertEquals(GridLayoutChoice.ALL, CameraBehaviorPrefs.gridLayoutFrom(null))
        assertEquals(GridLayoutChoice.ALL, CameraBehaviorPrefs.gridLayoutFrom("banana"))
        assertEquals(8, GridLayoutChoice.PAGES_8.pageSize)
        assertEquals(null, GridLayoutChoice.ALL.pageSize)
    }

    @Test
    fun `rotation steps map both ways and default to Off`() {
        assertEquals(listOf(0, 10, 30, 60), CameraBehaviorPrefs.ROTATION_STEPS_S)
        assertEquals(2, CameraBehaviorPrefs.rotationIndex(30))
        assertEquals(0, CameraBehaviorPrefs.rotationIndex(45))
        assertEquals(60, CameraBehaviorPrefs.rotationSecondsAt(3))
        assertEquals("Off", CameraBehaviorPrefs.rotationLabel(0))
        assertEquals("30 s", CameraBehaviorPrefs.rotationLabel(30))
    }
}
