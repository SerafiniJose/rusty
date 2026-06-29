package dev.rusty.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.RadioGroup
import android.widget.TextView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.tabs.TabLayout

/**
 * The shell-owned tabbed settings card (General · Screensaver · Spotify · Home Assistant).
 *
 * Replaces the old flat per-feature sheet. The shell ([HomeActivity]) opens this and lands it on
 * the active feature's tab; each tab inflates its own panel layout and binds the controls that used
 * to live in the flat sheet.
 *
 * App-wide tabs (General/Screensaver) are bound inline here because they are not feature-specific.
 * Feature-specific tabs (Spotify, Home Assistant) delegate to the feature's [SettingsPanelProvider]
 * returned from [Feature.settingsPanel] — the shell merely assembles + hosts the panel; it no
 * longer knows each feature's internal controls.
 *
 * Cleanup lifecycle: [currentPanelCleanup] is invoked on BOTH tab-switch AND dialog dismiss so
 * all panel teardown (including the HA repo-listener from Task 15) fires in both cases.
 */
object SettingsSheet {

    /** A tab: its key, its visible label, and the panel layout it inflates. */
    private data class Tab(val key: SettingsTabKey, val label: String, val layoutRes: Int)

    /** Resolves a shell-owned tab key to its (label, panel layout). Feature tabs use their provider's layoutRes. */
    private fun shellTabSpecFor(key: SettingsTabKey): Tab = when (key) {
        SettingsTabKey.GENERAL -> Tab(key, "General", R.layout.settings_panel_general)
        SettingsTabKey.SCREENSAVER -> Tab(key, "Screensaver", R.layout.settings_panel_screensaver)
        SettingsTabKey.SPOTIFY -> Tab(key, "Spotify", R.layout.settings_panel_spotify)
        SettingsTabKey.HOME_ASSISTANT -> Tab(key, "Home Assistant", R.layout.settings_panel_home_assistant)
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
        val featureTabs = FeatureRegistry.enabledIds(prefs).map { FeatureRegistry.byId(it).settingsTab }
        val specs = settingsTabsFor(featureTabs).map { shellTabSpecFor(it) }

        // Shell context bundle passed to feature panel providers.
        val panelCtx = SettingsPanelContext(activity, host, state)

        specs.forEach { spec -> tabs.addTab(tabs.newTab().setText(spec.label).setTag(spec.key)) }

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
                SettingsTabKey.GENERAL, SettingsTabKey.SCREENSAVER -> null
            }

            val layoutRes = provider?.layoutRes ?: spec.layoutRes
            val panel = activity.layoutInflater.inflate(layoutRes, container, false)
            container.addView(panel)

            currentPanelCleanup = if (provider != null) {
                provider.bind(panel)
            } else {
                bindShellPanel(spec.key, activity, panel)
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
    ): () -> Unit = when (key) {
        SettingsTabKey.GENERAL -> bindGeneral(activity, panel)
        SettingsTabKey.SCREENSAVER -> bindScreensaver(activity, panel)
        else -> ({ })  // Feature tabs handled via SettingsPanelProvider; should not reach here.
    }

    // ---- General binder -----------------------------------------------------

    private fun bindGeneral(activity: HomeActivity, panel: View): () -> Unit {
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
        }
        return {}
    }

    // ---- Screensaver binder -------------------------------------------------

    private fun bindScreensaver(activity: HomeActivity, panel: View): () -> Unit {
        // Theme selector — two side-by-side radio buttons (Clock / OLED).
        val themeGroup = panel.findViewById<RadioGroup>(R.id.rgScreensaverTheme)
        themeGroup.check(
            when (activity.currentScreensaverThemeId) {
                ScreensaverThemeId.OLED -> R.id.rbThemeOled
                ScreensaverThemeId.CANVAS -> R.id.rbThemeCanvas
                ScreensaverThemeId.CLOCK -> R.id.rbThemeClock
            }
        )
        themeGroup.setOnCheckedChangeListener { _, checkedId ->
            val id = when (checkedId) {
                R.id.rbThemeOled -> ScreensaverThemeId.OLED
                R.id.rbThemeCanvas -> ScreensaverThemeId.CANVAS
                else -> ScreensaverThemeId.CLOCK
            }
            activity.applyScreensaverTheme(id)
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
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (activity.resources.displayMetrics.widthPixels * 0.72f).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private const val PREFS_NAME = "spotify_receiver_prefs"
}
