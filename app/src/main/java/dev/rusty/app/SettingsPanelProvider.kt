package dev.rusty.app

import android.view.View

/**
 * Shell-side context objects passed into [Feature.settingsPanel] so the returned
 * [SettingsPanelProvider] has everything it needs when [SettingsPanelProvider.bind] is called.
 *
 * This keeps [Feature.settingsPanel] pure (no Android imports required on the Feature interface)
 * while still giving feature panel implementations access to the activity, shell host, and
 * receiver state.
 */
data class SettingsPanelContext(
    val activity: HomeActivity,
    val host: ShellHost,
    val state: () -> ReceiverDashboardState,
)

/**
 * A feature-owned settings panel contributor.
 *
 * Each feature that owns its own settings panel returns one of these from
 * [Feature.settingsPanel]. [SettingsSheet] inflates [layoutRes], calls [bind] on the result,
 * and calls the returned cleanup lambda when the panel is torn down (tab-switch or dialog dismiss).
 *
 * App-wide panels (General, Screensaver) remain shell-owned and are NOT routed through this
 * interface; only feature-specific panels (Spotify, Home Assistant) implement it.
 */
interface SettingsPanelProvider {
    /** The layout resource to inflate into the panel container. */
    val layoutRes: Int

    /**
     * Binds all controls in [panel] (the freshly-inflated [layoutRes] view).
     *
     * Returns a cleanup lambda that **must** be invoked:
     * - when the user switches to a different settings tab, AND
     * - when the settings dialog is dismissed.
     *
     * The lambda must release any listeners or subscriptions registered during binding
     * (e.g. [HomeAssistantDashboardRepository] listener from Task 15).
     * Returns an empty lambda if no cleanup is needed.
     */
    fun bind(panel: View): () -> Unit
}
