package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
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
 * @param btnInfo  The info button in the shell chrome (hidden when not on Spotify).
 * @param haChipBar  The full chip-bar container (visibility toggled by the controller).
 * @param haChipGroup  The ChipGroup inside the bar (chips inflated here).
 * @param toggle   The launcher toggle ImageButton.
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
    private val haChipBar: View,
    private val haChipGroup: com.google.android.material.chip.ChipGroup,
    toggle: android.widget.ImageButton,
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
        // Info button is only relevant over Spotify.
        btnInfo.visibility = if (id == FeatureId.SPOTIFY) View.VISIBLE else View.GONE
        // D-pad routing for the clock lives in SpotifyFragment; over other features remove it from
        // the focus graph so it cannot trap D-pad navigation (it remains touch-tappable).
        if (id != FeatureId.SPOTIFY) tvClock.isFocusable = false
        launcher.refresh()
        refreshDashboardChips()
        updateClock(id, animate = animate)
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
            ?: HomeAssistantDashboards.OVERVIEW_PATH
        val inflater = android.view.LayoutInflater.from(haChipGroup.context)
        selected.forEach { dashboard ->
            val chip = inflater.inflate(R.layout.view_dashboard_chip, haChipGroup, false)
                as com.google.android.material.chip.Chip
            chip.text = dashboard.title
            // Render the dashboard icon from the bundled full MDI font (falls back to a vector for
            // non-MDI/brand icons). Size to the chip's chipIconSize (16dp) so it matches the layout.
            val iconSizePx = (16f * haChipGroup.resources.displayMetrics.density).toInt()
            chip.chipIcon = HaIcons.iconDrawable(haChipGroup.context, dashboard.icon, iconSizePx)
            chip.isChecked = dashboard.urlPath == activePath
            chip.setOnClickListener {
                (currentFragment() as? ShellContribution)?.showDashboard(dashboard)
                refreshDashboardChips()
            }
            haChipGroup.addView(chip)
        }
        haChipBar.visibility = View.VISIBLE
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
