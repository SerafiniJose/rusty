package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePaintCacheTest {
    @Test
    fun `reports a change only when state or text differ`() {
        val c = TilePaintCache()
        assertTrue(c.update("a", TileState.OK, null))
        assertFalse(c.update("a", TileState.OK, null))
        assertTrue(c.update("a", TileState.UNREACHABLE, "45 s"))
        assertFalse(c.update("a", TileState.UNREACHABLE, "45 s"))
        assertTrue(c.update("a", TileState.UNREACHABLE, "46 s"))
    }

    @Test
    fun `prune forgets removed cameras so a re-added one repaints`() {
        val c = TilePaintCache()
        c.update("a", TileState.OK, null)
        c.prune(setOf("b"))
        assertTrue(c.update("a", TileState.OK, null))
    }
}
