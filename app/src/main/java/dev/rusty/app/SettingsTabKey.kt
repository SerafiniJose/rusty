package dev.rusty.app

/**
 * Settings tabs. GENERAL and SCREENSAVER are shell-owned app-wide tabs; the rest map 1:1 to a
 * [FeatureId]. DLNA_PLAYER is gated on its feature toggle like every other feature tab: it appears
 * only when [DlnaPlayerFeature] is enabled (which contributes it via [Feature.settingsTab]), so
 * enabling/disabling the DLNA Player toggle in General shows/hides the tab, consistent with Home
 * Assistant. The de-dup in [settingsTabsFor] is retained as a safety net.
 */
enum class SettingsTabKey { GENERAL, SCREENSAVER, SLIDESHOW, DLNA_PLAYER, SPOTIFY, HOME_ASSISTANT }

/**
 * The tab to open when settings is launched from [activeFeature]. App-wide tabs
 * (General/Screensaver) are never a feature default, so a feature always lands on its own tab;
 * a null active feature falls back to General.
 */
fun defaultSettingsTab(activeFeature: FeatureId?): SettingsTabKey = when (activeFeature) {
    FeatureId.SPOTIFY -> SettingsTabKey.SPOTIFY
    FeatureId.HOME_ASSISTANT -> SettingsTabKey.HOME_ASSISTANT
    FeatureId.DLNA -> SettingsTabKey.DLNA_PLAYER
    null -> SettingsTabKey.GENERAL
}

/**
 * The settings tab order for the current state: the app-wide tabs (General, Screensaver) first,
 * then the Slideshow tab when that screensaver feature is enabled (it is NOT a [FeatureRegistry]
 * feature — it has no launcher entry and contributes no [Feature.settingsTab] — so it is threaded
 * explicitly through [slideshowEnabled]), then one tab per enabled feature (in ring order).
 * Disabled features contribute no tab — so DLNA_PLAYER appears only when [DlnaPlayerFeature] is
 * enabled, exactly like Home Assistant. The `.distinct()` is a safety net against any feature
 * contributing a duplicate tab key.
 */
fun settingsTabsFor(
    enabledFeatureTabs: List<SettingsTabKey>,
    slideshowEnabled: Boolean,
): List<SettingsTabKey> =
    (listOf(SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER) +
        (if (slideshowEnabled) listOf(SettingsTabKey.SLIDESHOW) else emptyList()) +
        enabledFeatureTabs).distinct()

/**
 * Edit script that turns one tab list into another: [removals] are positions into the CURRENT
 * list (descending, so earlier removals don't shift later ones), [insertions] are (key, position)
 * pairs into the resulting list (ascending). Used to sync the open settings dialog's tab strip
 * when a feature is enabled/disabled from the General tab, without rebuilding the whole strip
 * (which would drop the current selection and re-inflate the panel mid-toggle).
 *
 * Correct because both lists are subsequences of the same master order (General, Screensaver,
 * then feature ring order): removing the keys absent from [target] leaves a subsequence of
 * [target], and inserting each missing key at its [target] position restores it exactly.
 */
data class SettingsTabSyncOps(
    val removals: List<Int>,
    val insertions: List<Pair<SettingsTabKey, Int>>,
)

fun settingsTabSyncOps(
    current: List<SettingsTabKey>,
    target: List<SettingsTabKey>,
): SettingsTabSyncOps = SettingsTabSyncOps(
    removals = current.withIndex()
        .filter { (_, key) -> key !in target }
        .map { it.index }
        .sortedDescending(),
    insertions = target.withIndex()
        .filter { (_, key) -> key !in current }
        .map { (index, key) -> key to index },
)
