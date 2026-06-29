package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherMenuTest {

    @Test fun lockIsFirstThenFeaturesInReverseRingOrder() {
        val items = LauncherMenu.items(listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT))
        assertEquals(3, items.size)
        assertEquals(LauncherMenu.Kind.LOCK, items[0].kind)
        // Reverse ring order: HA above Spotify, so Spotify (ring-first) is the bottom pill.
        assertEquals(FeatureId.HOME_ASSISTANT, items[1].featureId)
        assertEquals(FeatureId.SPOTIFY, items[2].featureId)
    }

    @Test fun singleFeatureStillHasLock() {
        val items = LauncherMenu.items(listOf(FeatureId.SPOTIFY))
        assertEquals(2, items.size)
        assertEquals(LauncherMenu.Kind.LOCK, items[0].kind)
        assertEquals(FeatureId.SPOTIFY, items[1].featureId)
    }

    @Test fun activeMarksOnlyMatchingFeature() {
        val items = LauncherMenu.items(listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT))
        val lock = items[0]
        val ha = items.first { it.featureId == FeatureId.HOME_ASSISTANT }
        val spotify = items.first { it.featureId == FeatureId.SPOTIFY }
        assertTrue(LauncherMenu.isActive(ha, FeatureId.HOME_ASSISTANT))
        assertFalse(LauncherMenu.isActive(spotify, FeatureId.HOME_ASSISTANT))
        assertFalse(LauncherMenu.isActive(lock, FeatureId.HOME_ASSISTANT))
    }
}
