package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTabTest {
    @Test fun spotifyFeatureOpensSpotifyTab() {
        assertEquals(SettingsTabKey.SPOTIFY, defaultSettingsTab(FeatureId.SPOTIFY))
    }

    @Test fun homeAssistantFeatureOpensHaTab() {
        assertEquals(SettingsTabKey.HOME_ASSISTANT, defaultSettingsTab(FeatureId.HOME_ASSISTANT))
    }

    @Test fun cameraFeatureOpensCameraTab() {
        assertEquals(SettingsTabKey.CAMERA, defaultSettingsTab(FeatureId.CAMERA))
    }

    @Test fun nullFeatureOpensGeneral() {
        assertEquals(SettingsTabKey.GENERAL, defaultSettingsTab(null))
    }

    @Test fun cameraSettingsTabExists() {
        // Task 12 gives Camera its own dedicated settings tab (no more General placeholder).
        assertTrue(SettingsTabKey.entries.any { it.name == "CAMERA" })
    }
}
