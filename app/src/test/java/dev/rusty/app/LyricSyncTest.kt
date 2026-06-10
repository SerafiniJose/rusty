package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricSyncTest {
    private val starts = listOf(0L, 1000L, 2500L, 4000L)

    @Test
    fun beforeFirstLineReturnsMinusOneWhenFirstIsNonZero() {
        assertEquals(-1, LyricSync.activeIndex(listOf(500L, 1000L), positionMs = 200L))
    }

    @Test
    fun exactBoundaryIsActive() {
        assertEquals(1, LyricSync.activeIndex(starts, positionMs = 1000L))
    }

    @Test
    fun betweenLinesPicksTheEarlierLine() {
        assertEquals(2, LyricSync.activeIndex(starts, positionMs = 3999L))
    }

    @Test
    fun afterLastLineReturnsLastIndex() {
        assertEquals(3, LyricSync.activeIndex(starts, positionMs = 99999L))
    }

    @Test
    fun emptyListReturnsMinusOne() {
        assertEquals(-1, LyricSync.activeIndex(emptyList(), positionMs = 1000L))
    }
}
