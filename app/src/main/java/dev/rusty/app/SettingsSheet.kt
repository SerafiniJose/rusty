package dev.rusty.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout

/**
 * The shell-owned tabbed settings card
 * (General · Screensaver · Slideshow · DLNA Player · Spotify · Home Assistant).
 *
 * Replaces the old flat per-feature sheet. The shell ([HomeActivity]) opens this and lands it on
 * the active feature's tab; each tab inflates its own panel layout and binds the controls that used
 * to live in the flat sheet.
 *
 * App-wide tabs (General/Screensaver) are bound inline here because they are not feature-specific.
 * Feature-specific tabs (Spotify, Home Assistant) delegate to the feature's [SettingsPanelProvider]
 * returned from [Feature.settingsPanel] — the shell merely assembles + hosts the panel; it no
 * longer knows each feature's internal controls. DLNA Player is app-wide but panel-owned: the
 * renderer has no fragment, so it has no [Feature] to ask — the shell constructs its provider
 * ([DlnaPlayerSettingsPanel]) directly.
 *
 * Cleanup lifecycle: [currentPanelCleanup] is invoked on BOTH tab-switch AND dialog dismiss so
 * all panel teardown (the HA repo-listener, the DLNA status listener) fires in both cases.
 */
object SettingsSheet {

    /** A tab: its key, its visible label, its icon, and the panel layout it inflates. */
    private data class Tab(val key: SettingsTabKey, val label: String, val iconRes: Int, val layoutRes: Int)

    /** Resolves a tab key to its (label, icon, panel layout). Feature tabs use their provider's layoutRes. */
    private fun shellTabSpecFor(key: SettingsTabKey): Tab = when (key) {
        SettingsTabKey.GENERAL -> Tab(key, "General", R.drawable.ic_mdi_cog, R.layout.settings_panel_general)
        SettingsTabKey.SCREENSAVER -> Tab(key, "Screensaver", R.drawable.ic_mdi_weather_night, R.layout.settings_panel_screensaver)
        SettingsTabKey.SLIDESHOW -> Tab(key, "Slideshow", R.drawable.ic_mdi_image, R.layout.settings_panel_slideshow)
        SettingsTabKey.DLNA_PLAYER -> Tab(key, "DLNA Player", R.drawable.ic_mdi_dlna, R.layout.settings_panel_dlna_player)
        SettingsTabKey.SPOTIFY -> Tab(key, "Spotify", R.drawable.ic_mdi_spotify, R.layout.settings_panel_spotify)
        SettingsTabKey.HOME_ASSISTANT -> Tab(key, "Home Assistant", R.drawable.ic_mdi_home_assistant, R.layout.settings_panel_home_assistant)
    }

    fun show(
        activity: HomeActivity,
        host: ShellHost,
        initialTab: SettingsTabKey,
        state: () -> ReceiverDashboardState,
    ) {
        val root = activity.layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        val dialog = createCardDialog(activity, root)

        val tabs = root.findViewById<TabLayout>(R.id.settingsTabs)
        val container = root.findViewById<android.widget.FrameLayout>(R.id.settingsPanelContainer)

        // The displayed tabs: two app-wide tabs + one per ENABLED feature (ring order).
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        fun currentSpecs(): List<Tab> {
            val featureTabs = FeatureRegistry.enabledIds(prefs).map { FeatureRegistry.byId(it).settingsTab }
            return settingsTabsFor(featureTabs, SlideshowSettings.isEnabled(prefs))
                .map { shellTabSpecFor(it) }
        }
        var specs = currentSpecs()

        fun tabFor(spec: Tab): TabLayout.Tab =
            tabs.newTab().setText(spec.label).setIcon(spec.iconRes).setTag(spec.key)

        // Re-syncs the tab strip after a feature toggle on the General tab, so the feature's tab
        // appears/disappears immediately instead of on next open. Incremental (no removeAllTabs):
        // the selected tab — General, where the toggles live — keeps its selection and panel.
        fun resyncTabs() {
            val newSpecs = currentSpecs()
            val ops = settingsTabSyncOps(specs.map { it.key }, newSpecs.map { it.key })
            ops.removals.forEach { tabs.removeTabAt(it) }
            ops.insertions.forEach { (key, position) ->
                tabs.addTab(tabFor(newSpecs.first { it.key == key }), position)
            }
            specs = newSpecs
        }

        // Shell context bundle passed to feature panel providers.
        val panelCtx = SettingsPanelContext(activity, host, state)

        specs.forEach { spec -> tabs.addTab(tabFor(spec)) }

        var currentPanelCleanup: (() -> Unit)? = null

        fun showPanel(spec: Tab) {
            // Invoke cleanup on tab-switch BEFORE swapping the panel.
            currentPanelCleanup?.invoke()
            currentPanelCleanup = null
            container.removeAllViews()

            // Ask the feature for its provider; fall back to shell binders for General/Screensaver.
            val provider: SettingsPanelProvider? = when (spec.key) {
                SettingsTabKey.SPOTIFY -> SpotifyFeature.settingsPanel(panelCtx)
                SettingsTabKey.HOME_ASSISTANT -> HomeAssistantFeature.settingsPanel(panelCtx)
                SettingsTabKey.DLNA_PLAYER -> DlnaPlayerSettingsPanel(panelCtx)
                SettingsTabKey.SLIDESHOW -> SlideshowSettingsPanel(panelCtx)
                SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER -> null
            }

            val layoutRes = provider?.layoutRes ?: spec.layoutRes
            val panel = activity.layoutInflater.inflate(layoutRes, container, false)
            container.addView(panel)

            currentPanelCleanup = if (provider != null) {
                provider.bind(panel)
            } else {
                bindShellPanel(spec.key, activity, panel, onFeatureTabsChanged = { resyncTabs() })
            }
        }

        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                showPanel(specs.first { it.key == tab.tag })
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // Dismissing the card re-hides the system bars if fullscreen is enabled, and refreshes the
        // HA chip bar (the user may have toggled dashboard selections in the HA settings tab).
        dialog.setOnDismissListener {
            // Invoke cleanup on dismiss so the HA repo-listener (Task 15) is removed.
            currentPanelCleanup?.invoke()
            currentPanelCleanup = null
            activity.reassertImmersiveIfEnabled()
            activity.refreshDashboardChips()
        }
        dialog.show()

        val index = specs.indexOfFirst { it.key == initialTab }.takeIf { it >= 0 } ?: 0
        tabs.getTabAt(index)?.select()
        // getTabAt(0).select() is a no-op when index 0 is already selected, so bind it explicitly.
        if (index == 0 && container.childCount == 0) specs.firstOrNull()?.let { showPanel(it) }

        // Restore D-pad initial focus (posted so layout has completed before traversal).
        container.post {
            if (container.isInTouchMode) return@post
            val panel = if (container.childCount > 0) container.getChildAt(0) else null
            val target: View? = when (initialTab) {
                SettingsTabKey.SPOTIFY -> panel?.findViewById(R.id.btnToggleService)
                else -> panel?.focusSearch(View.FOCUS_DOWN)
            }
            target?.requestFocus()
        }
    }

    // ---- Shell-owned binders (General and Screensaver) ----------------------

    /** Binds a shell-owned (non-feature) panel. Returns a cleanup lambda (empty for shell panels). */
    private fun bindShellPanel(
        key: SettingsTabKey,
        activity: HomeActivity,
        panel: View,
        onFeatureTabsChanged: () -> Unit,
    ): () -> Unit = when (key) {
        SettingsTabKey.GENERAL -> bindGeneral(activity, panel, onFeatureTabsChanged)
        SettingsTabKey.SCREENSAVER -> bindScreensaver(activity, panel)
        else -> ({ })  // Feature tabs handled via SettingsPanelProvider; should not reach here.
    }

    // ---- General binder -----------------------------------------------------

    private fun bindGeneral(
        activity: HomeActivity,
        panel: View,
        onFeatureTabsChanged: () -> Unit,
    ): () -> Unit {
        val fullscreenSwitch = panel.findViewById<SwitchMaterial>(R.id.switchFullscreen)
        fullscreenSwitch.isChecked = activity.isFullscreenEnabled
        fullscreenSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.setFullscreen(isChecked)
        }

        val keepScreenOnSwitch = panel.findViewById<SwitchMaterial>(R.id.switchKeepScreenOn)
        keepScreenOnSwitch.isChecked = activity.isKeepScreenOnEnabled
        keepScreenOnSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.setKeepScreenOn(isChecked)
        }

        val bootSwitch = panel.findViewById<SwitchMaterial>(R.id.switchStartOnBoot)
        val bootHelper = panel.findViewById<TextView>(R.id.tvBootSwitchHelper)
        if (!BootStartSupport.isReliable(android.os.Build.VERSION.SDK_INT)) {
            bootSwitch.isEnabled = false
            bootHelper.text = "May not run on Android 15+ (system restriction)"
        } else {
            bootSwitch.isChecked = activity.isStartOnBootEnabled
            bootSwitch.setOnCheckedChangeListener { _, isChecked ->
                activity.setStartOnBootEnabled(isChecked)
            }
        }

        val haSwitch = panel.findViewById<SwitchMaterial>(R.id.switchHomeAssistant)
        haSwitch.isChecked = activity.isHomeAssistantEnabled
        haSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.setHomeAssistantEnabled(isChecked)
            onFeatureTabsChanged()
        }

        val dlnaSwitch = panel.findViewById<SwitchMaterial>(R.id.switchDlnaPlayer)
        dlnaSwitch.isChecked = activity.isDlnaFeatureEnabled
        dlnaSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.setDlnaFeatureEnabled(isChecked)
            onFeatureTabsChanged()
        }

        val slideshowSwitch = panel.findViewById<SwitchMaterial>(R.id.switchSlideshow)
        slideshowSwitch.isChecked = activity.isSlideshowEnabled
        slideshowSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.setSlideshowEnabled(isChecked)
            onFeatureTabsChanged()
        }
        return {}
    }

    // ---- Screensaver binder -------------------------------------------------

    private fun bindScreensaver(
        activity: HomeActivity,
        panel: View,
    ): () -> Unit {
        // Theme selector — a reflowing row of radio buttons (Clock / OLED / Canvas / Slideshow),
        // positioned by a ConstraintLayout Flow that wraps only when the width runs out. They are
        // NOT inside a RadioGroup (it only auto-manages direct children, and Flow-positioned
        // children are not that), so exclusivity is enforced here:
        // checking one unchecks the others. [suppressThemeCallback] stops the programmatic
        // re-check below (and the sibling unchecks) from looping back into applyScreensaverTheme.
        val themeRadios: List<Pair<RadioButton, ScreensaverThemeId>> = listOf(
            R.id.rbThemeClock to ScreensaverThemeId.CLOCK,
            R.id.rbThemeOled to ScreensaverThemeId.OLED,
            R.id.rbThemeCanvas to ScreensaverThemeId.CANVAS,
            R.id.rbThemeSlideshow to ScreensaverThemeId.SLIDESHOW,
        ).map { (viewId, themeId) -> panel.findViewById<RadioButton>(viewId) to themeId }
        var suppressThemeCallback = false
        fun checkTheme(themeId: ScreensaverThemeId) {
            suppressThemeCallback = true
            themeRadios.forEach { (radio, id) -> radio.isChecked = id == themeId }
            suppressThemeCallback = false
        }

        val slideshowRadio = themeRadios.first { it.second == ScreensaverThemeId.SLIDESHOW }.first
        slideshowRadio.visibility = if (activity.isSlideshowEnabled) View.VISIBLE else View.GONE
        // A stored Slideshow theme with the feature off would check a GONE radio and leave the row
        // looking empty; heal it through the same disable policy so pref and picker always agree.
        val storedTheme = activity.currentScreensaverThemeId
        val initialTheme = SlideshowDisable.initialTheme(storedTheme, activity.isSlideshowEnabled)
        if (initialTheme != storedTheme) activity.applyScreensaverTheme(initialTheme)
        checkTheme(initialTheme)
        themeRadios.forEach { (radio, themeId) ->
            radio.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked || suppressThemeCallback) return@setOnCheckedChangeListener
                suppressThemeCallback = true
                themeRadios.forEach { (other, _) -> if (other !== radio) other.isChecked = false }
                suppressThemeCallback = false
                activity.applyScreensaverTheme(themeId)
            }
        }

        // Idle-timeout picker — a stepped slider mirroring the Spotify bitrate control. The slider
        // index is a position into ScreensaverTimeout.ordered; the sub-label echoes the choice.
        val timeoutSlider = panel.findViewById<Slider>(R.id.sliderScreensaverTimeout)
        val timeoutValue = panel.findViewById<TextView>(R.id.tvScreensaverTimeoutValue)
        val timeouts = ScreensaverTimeout.ordered
        timeoutSlider.value =
            timeouts.indexOf(activity.currentScreensaverTimeout).coerceAtLeast(0).toFloat()
        timeoutValue.text = activity.currentScreensaverTimeout.label
        timeoutSlider.addOnChangeListener { _, value, _ ->
            timeoutValue.text = timeouts[value.toInt()].label
        }
        timeoutSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val selected = timeouts[slider.value.toInt()]
                if (selected != activity.currentScreensaverTimeout) {
                    activity.applyScreensaverTimeout(selected)
                }
            }
        })

        // 24-hour time (unchanged)
        val timeFormatSwitch = panel.findViewById<SwitchMaterial>(R.id.switchTimeFormat)
        timeFormatSwitch.isChecked = activity.currentIs24HourClock
        timeFormatSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.applyTimeFormat(isChecked)
        }
        return {}
    }

    // ---- Shared helpers -----------------------------------------------------

    /** Builds a centered, rounded popup-card dialog hosting [view]. */
    private fun createCardDialog(activity: HomeActivity, view: View): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Rotating with the card open must re-size it: the shell absorbs configuration changes, so
        // nothing here is re-created and a landscape-width card would overflow a portrait screen.
        dialog.followDisplaySize(activity)
        return dialog
    }

    private const val PREFS_NAME = "spotify_receiver_prefs"
}
