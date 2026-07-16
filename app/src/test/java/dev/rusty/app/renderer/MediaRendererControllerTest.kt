package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRendererControllerTest {
    @Test fun shouldRun_mirrorsToggle() {
        assertTrue(MediaRendererController.shouldRun(enabled = true))
        assertFalse(MediaRendererController.shouldRun(enabled = false))
    }
    @Test fun prefKey_isStable() {
        assertEquals("dlna_renderer_enabled", MediaRendererController.KEY_RENDERER_ENABLED)
    }
}
