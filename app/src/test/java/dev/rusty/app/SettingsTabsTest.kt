package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTabsTest {

    @Test fun leadsWithAppWideTabs() {
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER),
            settingsTabsFor(emptyList())
        )
    }

    @Test fun appendsFeatureTabsInOrder() {
        assertEquals(
            listOf(
                SettingsTabKey.GENERAL,
                SettingsTabKey.SCREENSAVER,
                SettingsTabKey.SPOTIFY,
                SettingsTabKey.HOME_ASSISTANT,
            ),
            settingsTabsFor(listOf(SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT))
        )
    }
}
