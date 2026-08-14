package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaRendererControllerTest {
    @Test fun shouldRun_mirrorsToggleWhileTheFeatureIsOn() {
        assertTrue(MediaRendererController.shouldRun(enabled = true, featureEnabled = true))
        assertFalse(MediaRendererController.shouldRun(enabled = false, featureEnabled = true))
    }

    /**
     * The feature toggle owns the service. The Start/Stop button that governs [KEY_RENDERER_ENABLED]
     * lives in the DLNA Player settings tab, and that tab disappears with the feature — so a run-state
     * left on behind a disabled feature is unreachable, not a headless mode. Enforced here rather than
     * only at the toggle's call site so it also holds for the BOOT_COMPLETED sync.
     */
    @Test fun shouldRun_isFalseWheneverTheFeatureIsOff() {
        assertFalse(MediaRendererController.shouldRun(enabled = true, featureEnabled = false))
        assertFalse(MediaRendererController.shouldRun(enabled = false, featureEnabled = false))
    }

    @Test fun prefKey_isStable() {
        assertEquals("dlna_renderer_enabled", MediaRendererController.KEY_RENDERER_ENABLED)
    }

    /** The controller spells the feature key out instead of importing it, to keep this package free
     *  of `dev.rusty.app`. That is only safe while the two constants agree. */
    @Test fun featureKeyMatchesTheFeatureItGatesOn() {
        assertEquals(
            dev.rusty.app.DlnaPlayerFeature.KEY_ENABLED,
            MediaRendererController.KEY_FEATURE_ENABLED,
        )
    }
}
