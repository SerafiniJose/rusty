package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat

/**
 * Encapsulates the mutable shell chrome that changes when the active feature changes:
 *  - the shared clock's corner-park animation ([parkClockInCorner]),
 *  - the HA dashboard switcher chips ([refreshDashboardChips]),
 *  - the feature launcher state (entries, active marking, visibility).
 *
 * [HomeActivity] constructs this once in [onCreate], wires the chip listener in [onStart]/[onStop]
 * via [chipListener], and calls [onFeatureChanged] from the navigator's `onSwitched` callback.
 *
 * @param context  Used for color resolution and layout inflation (Activity context is fine).
 * @param prefs    The shared prefs instance that stores HA URL/dashboards/selection.
 * @param tvClock  The shared clock TextView that floats above every feature.
 * @param btnInfo  The info button in the shell chrome (app-wide: Services & status is not
 *                 feature-specific, so it stays visible on every feature and is HA-tinted).
 * @param btnSettings  The settings (gear) button in the shell chrome — HA-tinted with the clock/toggle.
 * @param haChipBar  The full chip-bar container (visibility toggled by the controller).
 * @param haChipGroup  The ChipGroup inside the bar (chips inflated here).
 * @param toggle   The launcher toggle (app-selector) ImageButton.
 * @param launcherMenu  The launcher pill column LinearLayout.
 * @param launcherScrim The tap-catching scrim behind the open launcher.
 * @param currentFeatureId  Lambda that returns the currently-visible [FeatureId] (delegated to
 *   [FeatureNavigator.current]).
 * @param currentFragment  Lambda returning the current fragment (for HA chip active-path + showDashboard).
 * @param haSignedIn  Whether HA discovery reports a signed-in session — drives whether the shell clock
 *   shows over Home Assistant (hidden on the login page, shown over a dashboard).
 * @param showScreensaver  Called when the Lock pill or the clock is tapped.
 * @param switchTo  Called when a feature pill is tapped.
 */
class ShellChromeController(
    context: Context,
    private val prefs: SharedPreferences,
    private val tvClock: android.widget.TextView,
    private val btnInfo: android.widget.ImageButton,
    private val btnSettings: android.widget.ImageButton,
    private val haChipBar: View,
    private val haChipGroup: com.google.android.material.chip.ChipGroup,
    private val toggle: android.widget.ImageButton,
    launcherMenu: android.widget.LinearLayout,
    launcherScrim: View,
    private val currentFeatureId: () -> FeatureId,
    private val currentFragment: () -> androidx.fragment.app.Fragment?,
    private val haSignedIn: () -> Boolean,
    private val showScreensaver: () -> Unit,
    private val switchTo: (FeatureId) -> Unit,
) {
    companion object {
        /** Duration of the clock center→corner bloom animation (matches BloomController ACTIVE morph). */
        private const val CLOCK_BLOOM_MS = 900L
    }

    /** Inflate-once colors for the launcher. */
    private val activeTint = ContextCompat.getColor(context, R.color.accent_fallback)
    private val inactiveTint = ContextCompat.getColor(context, R.color.ink)

    // ---- HA chrome tint -----------------------------------------------------
    // The floating chrome (clock, settings, app-selector) is tinted to HA's theme text colour while HA
    // is foreground so it stays legible over the themed strips, and restored to these captured defaults
    // on switch-away. Captured now, before any HA tint is ever applied.
    private val defaultClockColors: ColorStateList = tvClock.textColors
    private val defaultSettingsTint: ColorStateList? = btnSettings.imageTintList
    private val defaultToggleTint: ColorStateList? = toggle.imageTintList
    // btnInfo carries no XML tint (its vector bakes its own grey), so the captured default is null —
    // restoring null puts the icon back to exactly the look it has everywhere else.
    private val defaultInfoTint: ColorStateList? = btnInfo.imageTintList
    /** Last HA text colour reported by the frontend; re-applied when HA returns to the foreground. */
    private var haChromeColor: Int? = null

    val launcher: FeatureLauncher = FeatureLauncher(
        toggle = toggle,
        menu = launcherMenu,
        scrim = launcherScrim,
        activeTint = activeTint,
        inactiveTint = inactiveTint,
        itemLayoutRes = R.layout.view_launcher_item,
        minEntriesToShow = 1,
    ) { activityLauncherEntries() }

    /**
     * Listener that HomeActivity registers in [onStart] and removes in [onStop]. The controller owns
     * the implementation — HomeActivity only manages the lifecycle attachment to avoid leaks.
     */
    val chipListener = HomeAssistantDashboardRepository.Listener { _ ->
        refreshDashboardChips()
        // Sign-in state may have changed (login ⇄ dashboard) → re-evaluate the clock over HA.
        updateClock(currentFeatureId())
    }

    // ---- Public API ----------------------------------------------------------

    /**
     * Called from the navigator's `onSwitched` callback whenever the active feature changes.
     * Updates chip bar, info button, launcher active marking, and (when needed) parks the clock.
     *
     * @param id        The feature that just became visible.
     * @param animate   Whether to animate the clock park (true = explicit user switch via [switchTo],
     *                  false = cold start / config-change restore).
     */
    fun onFeatureChanged(id: FeatureId, animate: Boolean = false) {
        // Services & status reports all three foreground services and both integrations, so it is
        // relevant on every feature — no longer Spotify-only.
        btnInfo.visibility = View.VISIBLE
        // D-pad routing for the clock lives in SpotifyFragment; over other features remove it from
        // the focus graph so it cannot trap D-pad navigation (it remains touch-tappable).
        if (id != FeatureId.SPOTIFY) tvClock.isFocusable = false
        launcher.refresh()
        refreshDashboardChips()
        updateClock(id, animate = animate)
        // Tint the floating chrome to HA's text colour over HA; restore defaults everywhere else.
        if (id == FeatureId.HOME_ASSISTANT) haChromeColor?.let(::tintChrome) else restoreChrome()
    }

    // ---- HA chrome tint -----------------------------------------------------

    /**
     * Records HA's reported theme text colour and, when HA is the foreground feature, tints the floating
     * chrome (clock, settings, app-selector) to it so it stays legible over the themed strips. Reported
     * asynchronously by the HA frontend (see [HomeAssistantNav.reportThemeColorsJs]); stored so a later
     * return to HA re-applies it immediately, before the frontend re-reports.
     */
    fun applyHaTextColor(color: Int) {
        haChromeColor = color
        if (currentFeatureId() == FeatureId.HOME_ASSISTANT) tintChrome(color)
    }

    private fun tintChrome(color: Int) {
        val tint = ColorStateList.valueOf(color)
        tvClock.setTextColor(color)
        btnSettings.imageTintList = tint
        btnInfo.imageTintList = tint
        toggle.imageTintList = tint
        // The dashboard chips share the floating chrome's surface, so they follow the same ink.
        // (No restore counterpart: the chip row is cleared on switch-away and rebuilt on return.)
        tintChips(color)
    }

    private fun restoreChrome() {
        tvClock.setTextColor(defaultClockColors)
        btnSettings.imageTintList = defaultSettingsTint
        btnInfo.imageTintList = defaultInfoTint
        toggle.imageTintList = defaultToggleTint
    }

    // ---- Dashboard chips ----------------------------------------------------

    /**
     * Rebuilds the HA dashboard switcher chips in the shell bottom bar. Visible only over Home
     * Assistant with 2+ selected dashboards; the active chip is marked from the fragment's current
     * dashboard. A chip click navigates the HA WebView and re-marks the row.
     */
    fun refreshDashboardChips() {
        if (currentFeatureId() != FeatureId.HOME_ASSISTANT) {
            haChipGroup.removeAllViews()
            haChipBar.visibility = View.GONE
            return
        }
        val base = HomeAssistantUrl.normalize(prefs.getString(HomeAssistantFeature.KEY_URL, null))
        val origin = prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_ORIGIN, null)
        // Ignore a cache captured against a different HA server (URL changed).
        val cacheJson = if (HomeAssistantDashboards.isCacheFresh(base, origin))
            prefs.getString(HomeAssistantFeature.KEY_DASHBOARDS_CACHE, null) else null
        val selectedJson = prefs.getString(HomeAssistantFeature.KEY_SELECTED_DASHBOARDS, null)
        val selected = HomeAssistantDashboards.selectedFrom(cacheJson, selectedJson)
        haChipGroup.removeAllViews()
        if (selected.size < 2) {
            haChipBar.visibility = View.GONE
            return
        }
        val activePath = (currentFragment() as? ShellContribution)?.activeDashboardPath
        val inflater = android.view.LayoutInflater.from(haChipGroup.context)
        selected.forEach { dashboard ->
            val chip = inflater.inflate(R.layout.view_dashboard_chip, haChipGroup, false)
                as com.google.android.material.chip.Chip
            // Render the dashboard icon from the bundled full MDI font (falls back to a vector for
            // non-MDI/brand icons). Size to the chip's chipIconSize (22dp) so it matches the layout.
            val iconSizePx = (22f * haChipGroup.resources.displayMetrics.density).toInt()
            chip.chipIcon = HaIcons.iconDrawable(haChipGroup.context, dashboard.icon, iconSizePx)
            chip.isChecked = dashboard.urlPath == activePath
            // Icon-only pill by default; the active chip carries its title, and a D-pad-focused one
            // shows it too so a TV user can read what a click would select. The title always stays
            // available to accessibility.
            chip.contentDescription = dashboard.title
            styleChipLabel(chip, dashboard.title)
            chip.setOnFocusChangeListener { _, _ -> styleChipLabel(chip, dashboard.title) }
            // showDashboard() re-marks the row itself (it owns the optimistic active-path write), so
            // a follow-up refresh here would only rebuild every chip a second time per tap.
            chip.setOnClickListener {
                (currentFragment() as? ShellContribution)?.showDashboard(dashboard)
            }
            haChipGroup.addView(chip)
        }
        // A rebuild resets every chip to its XML colors, so the HA theme ink (if reported) has to be
        // re-applied here — refreshes arrive from chip taps and repo events, not just onFeatureChanged.
        haChromeColor?.let(::tintChips)
        haChipBar.visibility = View.VISIBLE
    }

    /** Applies [DashboardChipStyle.label] to [chip] and collapses/expands the text paddings with it,
     *  so a label-less chip is a circular 44dp icon pill rather than a lopsided one. */
    private fun styleChipLabel(chip: com.google.android.material.chip.Chip, title: String) {
        val label = DashboardChipStyle.label(title, active = chip.isChecked, focused = chip.isFocused)
        chip.text = label
        val density = chip.resources.displayMetrics.density
        chip.textStartPadding = if (label.isEmpty()) 0f else 6f * density
        chip.textEndPadding = if (label.isEmpty()) 0f else 2f * density
    }

    /** Tints the chips' icon + label ink to HA's reported theme text colour (the active chip keeps
     *  its accent), matching what [tintChrome] does for the clock/settings/app-selector — a fixed
     *  near-white ink was unreadable over a light HA theme. */
    private fun tintChips(color: Int) {
        val tint = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(activeTint, color),
        )
        for (i in 0 until haChipGroup.childCount) {
            val chip = haChipGroup.getChildAt(i) as? com.google.android.material.chip.Chip ?: continue
            chip.chipIconTint = tint
            chip.setTextColor(tint)
        }
    }

    // ---- Clock visibility + parking -----------------------------------------

    /**
     * Shows/positions the shared shell clock for [id]. Hidden only on the HA login page
     * ([hideShellClock]); over an HA dashboard or any other non-Spotify feature it parks in the corner,
     * and Spotify blooms it (no park here). Re-invoked from [chipListener] so the login⇄dashboard edge
     * updates the clock live.
     */
    private fun updateClock(id: FeatureId, animate: Boolean = false) {
        val hidden = hideShellClock(id, haSignedIn())
        tvClock.visibility = if (hidden) View.GONE else View.VISIBLE
        if (!hidden && id != FeatureId.SPOTIFY) parkClockInCorner(animate = animate)
    }

    // ---- Clock parking ------------------------------------------------------

    /**
     * Statically or animatedly parks the shared clock in the top-right corner — used when a
     * non-Spotify feature is foreground (no Spotify bloom to position it). Uses [BloomGeometry] so
     * the static park and the bloom's ACTIVE corner agree exactly.
     *
     * @param animate  false → snap immediately (cold start / restore); true → bloom animation
     *   (explicit feature switch), mirroring the Spotify bloom exit.
     */
    fun parkClockInCorner(animate: Boolean = false) {
        tvClock.animate().cancel()
        tvClock.post {
            val parent = tvClock.parent as View
            val margin = 24f * tvClock.resources.displayMetrics.density
            val (tx, ty) = BloomGeometry.cornerTranslation(
                parentWidth = parent.width,
                parentPaddingRight = parent.paddingRight,
                parentPaddingTop = parent.paddingTop,
                clockX = tvClock.left.toFloat(), clockY = tvClock.top.toFloat(),
                clockWidth = tvClock.width, clockHeight = tvClock.height,
                cornerScale = BloomGeometry.CORNER_SCALE, marginPx = margin,
            )
            if (animate) {
                tvClock.animate()
                    .translationX(tx).translationY(ty)
                    .scaleX(BloomGeometry.CORNER_SCALE).scaleY(BloomGeometry.CORNER_SCALE)
                    .setDuration(CLOCK_BLOOM_MS)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            } else {
                tvClock.translationX = tx
                tvClock.translationY = ty
                tvClock.scaleX = BloomGeometry.CORNER_SCALE
                tvClock.scaleY = BloomGeometry.CORNER_SCALE
            }
        }
    }

    // ---- Launcher entries ---------------------------------------------------

    /**
     * Top-to-bottom launcher entries for the shell chrome: Lock on top, then enabled features in
     * reverse ring order (active feature accent-tinted; tapping it just collapses).
     */
    private fun activityLauncherEntries(): List<LauncherEntry> {
        val current = currentFeatureId()
        return LauncherMenu.items(FeatureRegistry.enabledIds(prefs)).map { item ->
            when (item.kind) {
                LauncherMenu.Kind.LOCK ->
                    LauncherEntry(R.drawable.ic_lock, "Lock", active = false) { showScreensaver() }
                LauncherMenu.Kind.FEATURE -> {
                    val feature = FeatureRegistry.byId(item.featureId!!)
                    val active = LauncherMenu.isActive(item, current)
                    LauncherEntry(feature.iconRes, feature.title, active) {
                        if (!active) switchTo(item.featureId)
                    }
                }
            }
        }
    }
}

/**
 * Whether the shared shell clock should be hidden for [id]. Hidden ONLY on the HA login page — Home
 * Assistant foreground while [signedIn] is false — because the floating clock clutters the login form;
 * over an HA dashboard (signed in) and over every other feature it stays visible. Pure, for unit tests.
 */
internal fun hideShellClock(id: FeatureId, signedIn: Boolean): Boolean =
    id == FeatureId.HOME_ASSISTANT && !signedIn
