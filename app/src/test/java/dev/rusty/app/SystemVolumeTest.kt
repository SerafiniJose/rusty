package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemVolumeTest {
    @Test
    fun mapsStreamIndexOfAFifteenStepDevice() {
        assertEquals(0, SystemVolume.toPercent(0, 15))
        assertEquals(47, SystemVolume.toPercent(7, 15))
        assertEquals(100, SystemVolume.toPercent(15, 15))
        assertEquals(7, SystemVolume.toIndex(47, 15))
    }

    @Test
    fun everyIndexRoundTripsOnCommonStepCounts() {
        for (max in listOf(15, 25, 30, 100)) {
            for (index in 0..max) {
                assertEquals(index, SystemVolume.toIndex(SystemVolume.toPercent(index, max)!!, max))
            }
        }
    }

    @Test
    fun clampsOutOfRangePercent() {
        assertEquals(0, SystemVolume.toIndex(-20, 15))
        assertEquals(15, SystemVolume.toIndex(400, 15))
        assertEquals(0, SystemVolume.toIndex(50, 0))
    }

    @Test
    fun rejectsABogusMaximum() {
        assertNull(SystemVolume.toPercent(3, 0))
        assertNull(SystemVolume.toPercent(3, -1))
    }
}
