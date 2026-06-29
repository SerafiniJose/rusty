package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsTabTest {
    @Test fun spotifyFeatureOpensSpotifyTab() {
        assertEquals(SettingsTabKey.SPOTIFY, defaultSettingsTab(FeatureId.SPOTIFY))
    }

    @Test fun homeAssistantFeatureOpensHaTab() {
        assertEquals(SettingsTabKey.HOME_ASSISTANT, defaultSettingsTab(FeatureId.HOME_ASSISTANT))
    }

    @Test fun nullFeatureOpensGeneral() {
        assertEquals(SettingsTabKey.GENERAL, defaultSettingsTab(null))
    }

    @Test fun noCameraPlaceholders() {
        assertFalse(FeatureId.entries.any { it.name == "CAMERA" })
        assertFalse(SettingsTabKey.entries.any { it.name == "CAMERA" })
    }
}
