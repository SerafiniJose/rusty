package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaFeatureWiringTest {
    @Test fun defaultTabForDlnaIsDlnaPlayer() =
        assertEquals(SettingsTabKey.DLNA_PLAYER, defaultSettingsTab(FeatureId.DLNA))

    @Test fun dlnaTabHiddenWhenFeatureDisabled() {
        // Gated like Home Assistant: the DLNA feature off contributes no DLNA_PLAYER tab, so it is
        // absent — only the app-wide General/Screensaver tabs (+ any other enabled feature) show.
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER),
            settingsTabsFor(emptyList()))
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.HOME_ASSISTANT),
            settingsTabsFor(listOf(SettingsTabKey.HOME_ASSISTANT)))
    }

    @Test fun dlnaTabShownOnceWhenFeatureEnabled() {
        // Feature on contributes DLNA_PLAYER; it appears exactly once, after the app-wide tabs.
        val tabs = settingsTabsFor(listOf(SettingsTabKey.DLNA_PLAYER, SettingsTabKey.HOME_ASSISTANT))
        assertEquals(1, tabs.count { it == SettingsTabKey.DLNA_PLAYER })
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.DLNA_PLAYER,
                SettingsTabKey.HOME_ASSISTANT),
            tabs)
    }

    @Test fun featureIsInRegistryAndOffByDefault() {
        assertEquals(FeatureId.DLNA, DlnaPlayerFeature.id)
        assert(FeatureRegistry.all.any { it.id == FeatureId.DLNA })
        assertEquals(SettingsTabKey.DLNA_PLAYER, DlnaPlayerFeature.settingsTab)
    }
}
