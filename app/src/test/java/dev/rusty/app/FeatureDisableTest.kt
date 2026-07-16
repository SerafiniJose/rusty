package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FeatureDisableTest {
    @Test fun noSwitchWhenDisabledFeatureWasNotActive() {
        assertNull(FeatureDisable.switchTargetOnDisable(
            FeatureId.DLNA, activeId = FeatureId.SPOTIFY,
            stillEnabled = listOf(FeatureId.SPOTIFY)))
    }

    @Test fun switchesToFirstStillEnabledWhenActiveDisabled() {
        assertEquals(FeatureId.SPOTIFY, FeatureDisable.switchTargetOnDisable(
            FeatureId.DLNA, activeId = FeatureId.DLNA,
            stillEnabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT)))
    }

    @Test fun fallsBackToSpotifyWhenNothingEnabled() {
        assertEquals(FeatureId.SPOTIFY, FeatureDisable.switchTargetOnDisable(
            FeatureId.DLNA, activeId = FeatureId.DLNA, stillEnabled = emptyList()))
    }
}
