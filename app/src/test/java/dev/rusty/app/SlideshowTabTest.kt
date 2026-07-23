package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SlideshowTabTest {

    @Test fun tabAppearsAfterScreensaverBeforeFeatureTabsWhenEnabled() {
        val tabs = settingsTabsFor(
            enabledFeatureTabs = listOf(SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT),
            slideshowEnabled = true,
        )
        assertEquals(listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER,
            SettingsTabKey.SLIDESHOW, SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT), tabs)
    }

    @Test fun tabAbsentWhenDisabled() {
        val tabs = settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = false)
        assertEquals(listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.SPOTIFY), tabs)
    }

    @Test fun toggleSyncOpsInsertAndRemoveAtStablePosition() {
        val off = settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = false)
        val on = settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = true)
        val enableOps = settingsTabSyncOps(off, on)
        assertEquals(listOf(SettingsTabKey.SLIDESHOW to 2), enableOps.insertions)
        assertEquals(emptyList<Int>(), enableOps.removals)
        val disableOps = settingsTabSyncOps(on, off)
        assertEquals(listOf(2), disableOps.removals)
    }

    /** The Immich tab is never a feature default — the feature has no launcher entry. */
    @Test fun immichIsNeverADefaultTab() {
        assertEquals(SettingsTabKey.GENERAL, defaultSettingsTab(null))
        FeatureId.values().forEach {
            assert(defaultSettingsTab(it) != SettingsTabKey.SLIDESHOW)
        }
    }
}
