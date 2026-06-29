package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureRingTest {
    private val all = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT)

    @Test fun enabledKeepsDeclarationOrder() {
        val enabled = FeatureRing.enabled(all) { it != FeatureId.HOME_ASSISTANT }
        assertEquals(listOf(FeatureId.SPOTIFY), enabled)
    }

    @Test fun nextWrapsAround() {
        assertEquals(FeatureId.HOME_ASSISTANT, FeatureRing.next(all, FeatureId.SPOTIFY))
        assertEquals(FeatureId.SPOTIFY, FeatureRing.next(all, FeatureId.HOME_ASSISTANT))
    }

    @Test fun nextIsNoOpForSingletonRing() {
        assertEquals(FeatureId.SPOTIFY, FeatureRing.next(listOf(FeatureId.SPOTIFY), FeatureId.SPOTIFY))
    }

    @Test fun nextIsNoOpWhenCurrentAbsent() {
        val ring = listOf(FeatureId.SPOTIFY)
        assertEquals(FeatureId.HOME_ASSISTANT, FeatureRing.next(ring, FeatureId.HOME_ASSISTANT))
    }
}
