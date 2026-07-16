package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class RendererPortPickerTest {

    /** Simulates a machine where [occupied] ports fail to bind; port 0 = ephemeral -> 55555. */
    private fun binder(occupied: Set<Int>, attempts: MutableList<Int> = mutableListOf()): (Int) -> Int? =
        { port ->
            attempts.add(port)
            when {
                port == 0 -> 55555
                port in occupied -> null
                else -> port
            }
        }

    @Test
    fun `uses the persisted port when it is free`() {
        assertEquals(49155, RendererPortPicker.choose(preferred = 49155, bind = binder(emptySet())))
    }

    @Test
    fun `uses 49152 when nothing is persisted`() {
        assertEquals(49152, RendererPortPicker.choose(preferred = null, bind = binder(emptySet())))
    }

    @Test
    fun `walks to 49153 when 49152 is held`() {
        assertEquals(49153, RendererPortPicker.choose(preferred = null, bind = binder(setOf(49152))))
    }

    @Test
    fun `an occupied persisted port falls through to the full ladder, not past it`() {
        val attempts = mutableListOf<Int>()
        val port = RendererPortPicker.choose(49155, binder(setOf(49155), attempts))
        assertEquals(49152, port)
        assertEquals(listOf(49155, 49152), attempts)
    }

    @Test
    fun `falls back to an ephemeral port when the whole ladder is held`() {
        val occupied = (49152..49161).toSet()
        assertEquals(55555, RendererPortPicker.choose(preferred = null, bind = binder(occupied)))
    }

    @Test
    fun `falls back to ephemeral when persisted and ladder are all held`() {
        val occupied = (49152..49161).toSet() + 50000
        assertEquals(55555, RendererPortPicker.choose(preferred = 50000, bind = binder(occupied)))
    }

    @Test
    fun `never retries the persisted port inside the ladder`() {
        val attempts = mutableListOf<Int>()
        RendererPortPicker.choose(49153, binder(setOf(49153, 49152), attempts))
        assertEquals(listOf(49153, 49152, 49154), attempts)
    }

    @Test
    fun `a corrupt persisted port is ignored, never attempted`() {
        val attempts = mutableListOf<Int>()
        assertEquals(49152, RendererPortPicker.choose(preferred = 70000, bind = binder(emptySet(), attempts)))
        assertEquals(listOf(49152), attempts)

        val attempts2 = mutableListOf<Int>()
        assertEquals(49152, RendererPortPicker.choose(preferred = -1, bind = binder(emptySet(), attempts2)))
        assertEquals(listOf(49152), attempts2)
    }
}
