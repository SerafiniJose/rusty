package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SettingsTabsTest {

    @Test fun leadsWithAppWideTabs() {
        // Only General + Screensaver are unconditionally app-wide. DLNA_PLAYER is now feature-gated
        // (contributed via DlnaPlayerFeature.settingsTab only when the feature is enabled).
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER),
            settingsTabsFor(emptyList(), slideshowEnabled = false, remoteControlEnabled = false)
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
            settingsTabsFor(listOf(SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT), slideshowEnabled = false, remoteControlEnabled = false)
        )
    }

    @Test fun remoteControlTabFollowsItsToggle() {
        // Remote Control is a Slideshow-style tab: app-wide, no launcher entry, present exactly
        // while the control API is enabled — after the app-wide tabs, before the feature ring.
        assertEquals(
            listOf(
                SettingsTabKey.GENERAL,
                SettingsTabKey.SCREENSAVER,
                SettingsTabKey.SLIDESHOW,
                SettingsTabKey.REMOTE_CONTROL,
                SettingsTabKey.VOICE,
                SettingsTabKey.SPOTIFY,
            ),
            settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = true, remoteControlEnabled = true)
        )
        assertFalse(
            SettingsTabKey.REMOTE_CONTROL in
                settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = true, remoteControlEnabled = false)
        )
    }

    @Test fun dlnaPlayerTabHiddenWhenItsFeatureIsDisabled() {
        // Feature off -> its tab isn't in the contributed list -> absent (gated like Home Assistant).
        val tabs = settingsTabsFor(listOf(SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT), slideshowEnabled = false, remoteControlEnabled = false)
        assertFalse(SettingsTabKey.DLNA_PLAYER in tabs)
    }

    @Test fun dlnaPlayerTabAppearsAsAFeatureTabWhenEnabled() {
        // Feature on contributes DLNA_PLAYER; it lands in ring order among the feature tabs, after the
        // app-wide General/Screensaver, exactly once.
        val tabs = settingsTabsFor(
            listOf(SettingsTabKey.DLNA_PLAYER, SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT),
            slideshowEnabled = false, remoteControlEnabled = false)
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.DLNA_PLAYER,
                SettingsTabKey.SPOTIFY, SettingsTabKey.HOME_ASSISTANT),
            tabs,
        )
    }

    @Test fun voiceTabFollowsRemoteControlRightAfterIt() {
        // Voice holds the announcement voice, and announcements only arrive through the control
        // API — so it comes and goes with Remote Control and sits immediately after it.
        val on = settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = false, remoteControlEnabled = true)
        assertEquals(
            listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER, SettingsTabKey.REMOTE_CONTROL,
                SettingsTabKey.VOICE, SettingsTabKey.SPOTIFY),
            on,
        )
        val off = settingsTabsFor(listOf(SettingsTabKey.SPOTIFY), slideshowEnabled = false, remoteControlEnabled = false)
        assertFalse(SettingsTabKey.VOICE in off)
    }
}
