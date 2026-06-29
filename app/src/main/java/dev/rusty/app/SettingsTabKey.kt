package dev.rusty.app

/** Settings tabs. GENERAL and SCREENSAVER are app-wide; the rest map 1:1 to a [FeatureId]. */
enum class SettingsTabKey { GENERAL, SCREENSAVER, SPOTIFY, HOME_ASSISTANT }

/**
 * The tab to open when settings is launched from [activeFeature]. App-wide tabs
 * (General/Screensaver) are never a feature default, so a feature always lands on its own tab;
 * a null active feature falls back to General.
 */
fun defaultSettingsTab(activeFeature: FeatureId?): SettingsTabKey = when (activeFeature) {
    FeatureId.SPOTIFY -> SettingsTabKey.SPOTIFY
    FeatureId.HOME_ASSISTANT -> SettingsTabKey.HOME_ASSISTANT
    null -> SettingsTabKey.GENERAL
}

/**
 * The settings tab order for the current state: the two app-wide tabs first, then one tab per
 * enabled feature (in ring order). Disabled/not-yet-built features contribute no tab.
 */
fun settingsTabsFor(enabledFeatureTabs: List<SettingsTabKey>): List<SettingsTabKey> =
    listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER) + enabledFeatureTabs
