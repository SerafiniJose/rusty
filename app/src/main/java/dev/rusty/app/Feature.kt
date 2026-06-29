package dev.rusty.app

import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment

/**
 * The shell-facing surface a feature's fragment optionally exposes, replacing concrete-type casts
 * (`as? SpotifyFragment` / `as? HomeAssistantFragment`) in the shell layer.
 *
 * Every method has a safe default so the shell can call through without checking which feature is
 * active. Features implement only the capabilities they actually support.
 *
 *  - [showInfo]             — Spotify: opens the Info bottom sheet. No-op on other features.
 *  - [applyTimeFormat]      — Spotify: re-renders the clock immediately after a 24h toggle.
 *                             No-op on features that don't own a clock view.
 *  - [activeDashboardPath]  — Home Assistant: the url_path currently shown in the WebView, used
 *                             to mark the active chip in the shell bottom bar. Null on others.
 *  - [showDashboard]        — Home Assistant: navigates the WebView to [dashboard]. No-op on others.
 *  - [runDiscovery]         — Home Assistant: triggers JS-bridge dashboard discovery. No-op on others.
 *  - [reloadUrl]            — Home Assistant: reloads the WebView at the configured URL. No-op on others.
 */
interface ShellContribution {
    fun showInfo() {}
    fun applyTimeFormat(is24Hour: Boolean) {}
    val activeDashboardPath: String? get() = null
    fun showDashboard(dashboard: HomeAssistantDashboards.HaDashboard) {}
    fun runDiscovery() {}
    fun reloadUrl() {}
}

/** Registry descriptor for a top-level feature. Pure metadata + a fragment factory. */
interface Feature {
    val id: FeatureId
    val title: String
    /** Drawable shown on this feature's launcher pill. */
    val iconRes: Int
    /** Spotify is always on; HA/Camera read their enable pref. */
    fun isEnabled(prefs: SharedPreferences): Boolean
    fun createFragment(): Fragment
    val settingsTab: SettingsTabKey

    /**
     * Returns this feature's [ShellContribution] by consulting the current fragment. The shell
     * calls this instead of casting `currentFragment as? SpotifyFragment` / `as? HomeAssistantFragment`.
     *
     * The seam: the feature's fragment implements [ShellContribution] directly, so the default
     * implementation here is sufficient for all current features — override only if needed.
     *
     * Returns null when no fragment is live (cold start before the first commit, or fragment removed).
     */
    fun shellContribution(fragment: Fragment?): ShellContribution? = fragment as? ShellContribution

    /**
     * Returns this feature's settings panel provider, or null if the feature has no feature-specific
     * settings tab (General and Screensaver are shell-owned and return null from the default here;
     * non-Feature shell tabs never call this method). Feature-specific panels (Spotify, HA) return a
     * non-null [SettingsPanelProvider] so [SettingsSheet] can delegate panel binding to the feature.
     *
     * The [ctx] carries shell-side objects (activity, host, state snapshot) that the provider's
     * [SettingsPanelProvider.bind] method needs; it is purposely opaque to Feature implementations
     * that don't need it (they receive null or ignore it).
     */
    fun settingsPanel(ctx: SettingsPanelContext): SettingsPanelProvider? = null

    /**
     * Hidden-lifecycle policy for the retained-fragment shell (Task 18). When a feature's fragment
     * is hidden (another feature is shown), the [FeatureNavigator] caps it at `CREATED` by default,
     * so its `onStop` runs and any `onStart`-registered work (store listeners, ticks, ambient mesh)
     * is torn down. A feature that genuinely needs to keep running in the background while hidden —
     * e.g. a future live camera preview — can opt into staying `STARTED` by overriding this to true.
     *
     * Default `false`. No current feature opts in: Spotify must tear down (so its listeners don't
     * leak/double-register), and HA's WebView is paused via its own `onPause` at the `CREATED` cap.
     */
    val retainStartedWhenHidden: Boolean get() = false
}

/** A fragment that wants the shell's window insets forwarded so it can pad its own content. */
interface InsetAware {
    fun onInsets(insets: WindowInsetsCompat)
}

/** A fragment that wants first crack at a key event (e.g. dedicated transport keys). */
interface KeyEventTarget {
    /** Return true if the event was consumed. */
    fun onKeyEvent(event: KeyEvent): Boolean
}

/** A fragment that replays its entrance animation when the screensaver dismisses into it. */
interface ScreensaverExitTarget {
    /** [showMesh] = false when the exiting theme paints no mesh (OLED), so the replayed bloom
     *  keeps its ambient mesh hidden and the saver's dark transition doesn't flash mesh colors. */
    fun onReturnFromScreensaver(showMesh: Boolean)
}

/** A fragment that renders receiver state and wants a nudge when the shell changes it. */
interface ReceiverStateAware {
    fun onShellStateChanged()
}

/**
 * A retained fragment (Task 18) that knows where D-pad focus should land when it is SHOWN after a
 * switch. With add/hide/show the fragment is not recreated, so `onViewCreated`'s initial focus does
 * not re-run — the shell calls [restoreFocus] after each switch so focus is never stranded on a
 * now-hidden control. No-op in touch mode (phones/tablets are unaffected).
 */
interface FocusRestorable {
    fun restoreFocus()
}

/** The narrow surface the shell exposes to fragments and settings panels. Implemented by HomeActivity. */
interface ShellHost {
    fun openSettings(tab: SettingsTabKey? = null)
    /** Enters the screensaver immediately (e.g. the clock tap). */
    fun showScreensaver()
    /** The single shell-owned clock, handed to the Spotify fragment so its bloom can morph it. */
    fun sharedClock(): android.widget.TextView
    fun startReceiver()
    fun stopReceiver()
    fun applyReceiverName(newName: String)
    fun applyBitrate(newKbps: Int)
    val currentDeviceName: String
    val currentBitrateKbps: Int
}
