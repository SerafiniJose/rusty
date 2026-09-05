package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraBehaviorPrefsTest {

    @Test
    fun `refresh steps run Off, 30 s to 10 min with a 30 s floor and default`() {
        assertEquals(listOf(0, 30, 60, 120, 300, 600), CameraBehaviorPrefs.REFRESH_STEPS_S)
        assertEquals(30, CameraBehaviorPrefs.DEFAULT_GRID_REFRESH_S)
    }

    @Test
    fun `refreshIndex maps a known value and snaps an unknown one to the default`() {
        assertEquals(3, CameraBehaviorPrefs.refreshIndex(120))
        assertEquals(1, CameraBehaviorPrefs.refreshIndex(7))
        assertEquals(120, CameraBehaviorPrefs.refreshSecondsAt(3))
    }

    @Test
    fun `snapRefresh keeps Off, rounds an old value up to the next stop, and clamps the top`() {
        assertEquals(0, CameraBehaviorPrefs.snapRefresh(0))
        assertEquals(0, CameraBehaviorPrefs.snapRefresh(-3))
        assertEquals(30, CameraBehaviorPrefs.snapRefresh(5))
        assertEquals(30, CameraBehaviorPrefs.snapRefresh(10))
        assertEquals(30, CameraBehaviorPrefs.snapRefresh(30))
        assertEquals(120, CameraBehaviorPrefs.snapRefresh(61))
        assertEquals(600, CameraBehaviorPrefs.snapRefresh(3600))
    }

    @Test
    fun `everyLabel reads as a cadence`() {
        assertEquals("Off", CameraBehaviorPrefs.everyLabel(0))
        assertEquals("Every 30 s", CameraBehaviorPrefs.everyLabel(30))
        assertEquals("Every 2 min", CameraBehaviorPrefs.everyLabel(120))
    }

    @Test
    fun `refresh label reads Off, seconds, or whole minutes`() {
        assertEquals("Off", CameraBehaviorPrefs.label(0))
        assertEquals("10 s", CameraBehaviorPrefs.label(10))
        assertEquals("1 min", CameraBehaviorPrefs.label(60))
        assertEquals("10 min", CameraBehaviorPrefs.label(600))
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
