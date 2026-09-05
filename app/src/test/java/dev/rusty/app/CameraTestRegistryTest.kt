package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [CameraTestRegistry] is the seam that lets a settings Test button and the mounted
 * [CameraFragment]'s scheduler agree on which camera id is currently under test (I2: the
 * scheduler's `testRunning` skip logic was already wired and tested, but nothing fed it).
 */
class CameraTestRegistryTest {

    @Before
    fun reset() = CameraTestRegistry.resetForTest()

    @Test
    fun `a marked camera id is reported by current`() {
        CameraTestRegistry.markTesting("cam-1")
        assertEquals(setOf("cam-1"), CameraTestRegistry.current())
    }

    @Test
    fun `clearing removes only that camera id`() {
        CameraTestRegistry.markTesting("cam-1")
        CameraTestRegistry.markTesting("cam-2")

        CameraTestRegistry.clearTesting("cam-1")

        assertEquals(setOf("cam-2"), CameraTestRegistry.current())
    }

    @Test
    fun `clearing an id that was never marked is a safe no-op`() {
        CameraTestRegistry.clearTesting("never-marked")   // must not throw
        assertTrue(CameraTestRegistry.current().isEmpty())
    }

    @Test
    fun `marking notifies listeners so the scheduler can re-apply suspension immediately`() {
        var calls = 0
        CameraTestRegistry.addListener { calls++ }

        CameraTestRegistry.markTesting("cam-1")
        assertEquals(1, calls)

        CameraTestRegistry.clearTesting("cam-1")
        assertEquals(2, calls)
    }

    @Test
    fun `marking an already-marked id does not notify again`() {
        var calls = 0
        CameraTestRegistry.addListener { calls++ }

        CameraTestRegistry.markTesting("cam-1")
        CameraTestRegistry.markTesting("cam-1")

        assertEquals("a duplicate mark changes nothing, so listeners shouldn't be re-run", 1, calls)
    }

    @Test
    fun `clearing an id that was never marked does not notify`() {
        var calls = 0
        CameraTestRegistry.addListener { calls++ }

        CameraTestRegistry.clearTesting("never-marked")

        assertEquals(0, calls)
    }

    @Test
    fun `a removed listener stops being called`() {
        var calls = 0
        val l: () -> Unit = { calls++ }
        CameraTestRegistry.addListener(l)
        CameraTestRegistry.removeListener(l)

        CameraTestRegistry.markTesting("cam-1")

        assertEquals(0, calls)
    }

    @Test
    fun `current returns an independent snapshot`() {
        CameraTestRegistry.markTesting("cam-1")
        val snapshot = CameraTestRegistry.current()

        CameraTestRegistry.markTesting("cam-2")

        assertEquals(setOf("cam-1"), snapshot)
        assertEquals(setOf("cam-1", "cam-2"), CameraTestRegistry.current())
    }
}
