package dev.rusty.app.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure-JVM tests for the ring that remembers the newest samples handed to the AudioTrack. */
class ReplayTailTest {

    private fun floats(vararg v: Int): FloatArray = FloatArray(v.size) { v[it].toFloat() }

    @Test
    fun lastReturnsTheNewestSamplesOldestFirst() {
        val tail = ReplayTail(capacity = 8)
        tail.push(floats(1, 2, 3), 3)
        assertArrayEquals(floats(2, 3), tail.last(2), 0f)
        assertArrayEquals(floats(1, 2, 3), tail.last(3), 0f)
    }

    @Test
    fun lastIsClampedToWhatWasPushed() {
        val tail = ReplayTail(capacity = 8)
        tail.push(floats(1, 2), 2)
        assertArrayEquals(floats(1, 2), tail.last(5), 0f)
        assertEquals(0, tail.last(0).size)
    }

    @Test
    fun wrapsAroundTheRing() {
        val tail = ReplayTail(capacity = 4)
        tail.push(floats(1, 2, 3), 3)
        tail.push(floats(4, 5, 6), 3)
        assertArrayEquals(floats(3, 4, 5, 6), tail.last(4), 0f)
        assertArrayEquals(floats(5, 6), tail.last(2), 0f)
    }

    @Test
    fun aPushLargerThanTheCapacityKeepsOnlyTheNewestSamples() {
        val tail = ReplayTail(capacity = 4)
        tail.push(floats(1, 2, 3, 4, 5, 6, 7), 7)
        assertArrayEquals(floats(4, 5, 6, 7), tail.last(4), 0f)
        tail.push(floats(8), 1)
        assertArrayEquals(floats(5, 6, 7, 8), tail.last(4), 0f)
    }

    @Test
    fun pushHonoursCountNotArrayLength() {
        val tail = ReplayTail(capacity = 8)
        tail.push(floats(1, 2, 3, 99, 99), 3)
        assertArrayEquals(floats(1, 2, 3), tail.last(8), 0f)
    }

    @Test
    fun clearForgetsEverything() {
        val tail = ReplayTail(capacity = 8)
        tail.push(floats(1, 2, 3), 3)
        tail.clear()
        assertEquals(0, tail.last(3).size)
        tail.push(floats(4), 1)
        assertArrayEquals(floats(4), tail.last(3), 0f)
    }
}
