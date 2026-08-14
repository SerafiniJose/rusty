package dev.rusty.app

import android.content.Context
import android.view.View
import android.view.ViewGroup

/** Actions a screensaver theme can ask the shell to perform from its chrome. */
interface ScreensaverHost {
    fun openSettings()
    fun openInfo()
    /**
     * Entries for the saver's expandable launcher — one per enabled feature (no Lock; you're already
     * in the saver). Each entry's action commits the chosen feature and wakes the saver out into it.
     * Empty/one-entry means the saver chrome shows no launcher (nothing to navigate to).
     */
    fun launcherEntries(): List<LauncherEntry>

    /**
     * Leave the saver and return to the dashboard. For themes whose own surface owns the exit
     * gesture (Slideshow's clock/✕) — the controller's tap-anywhere wake path can't serve them,
     * because a clickable child consumes its touch before the overlay listener ever sees it.
     */
    fun requestExit()
}

/**
 * A screensaver theme. The controller calls [createView] once on entry, [bind] on every
 * state change + a 1 Hz tick, and [onShown]/[onHidden] to start/stop any animation.
 * A theme renders an idle presentation (clock-centric) and a playing presentation
 * (ambient now-playing) since the screensaver can fire in either state.
 */
interface ScreensaverTheme {
    fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View
    fun bind(state: ReceiverDashboardState, is24Hour: Boolean)
    fun onShown()
    fun onHidden()

    /**
     * A wake gesture landed (a tap or key NOT on a chrome button). Return true if the theme
     * consumed it to advance its own state (e.g. OLED's first gesture: freeze + reveal buttons);
     * return false to request the bloom-exit to the dashboard. Default: exit immediately.
     */
    fun onWakeGesture(): Boolean = false

    /**
     * True while this theme owns the remote: the shell routes D-pad LEFT/RIGHT/CENTER/ENTER to
     * [onNavKey], BACK/UP exit, and every other key is a consumed no-op. Default false: all keys
     * fall through to the wake path (any-key-wakes — themes without their own remote surface must
     * never trap a D-pad user; the v2.0.0 Shield rule).
     */
    fun ownsRemote(): Boolean = false

    /**
     * A shell-approved nav key (DPAD_LEFT/RIGHT/CENTER or ENTER — [ShellKeyRouting.isNavKey],
     * initial press only) while [ownsRemote]. The shell enforces the key set; themes are not
     * trusted to filter.
     */
    fun onNavKey(keyCode: Int) {}

    /**
     * Whether this theme paints its own ambient mesh. Drives the exit bloom: a mesh-less theme
     * (OLED) tells the dashboard to keep its mesh hidden during the morph, so the mesh's colors
     * don't flash in over the dark saver. Themes that already show a mesh (Clock) leave it true
     * so the crossfade into the dashboard's mesh stays seamless.
     */
    val rendersAmbientMesh: Boolean get() = true

    /**
     * Show or hide the interactive Settings/Info chrome. The controller hides it when the saver is a
     * pure sleep layer over a non-receiver feature (any tap just wakes). Default: no-op (themes that
     * draw no chrome ignore it).
     */
    fun setChromeVisible(visible: Boolean) {}

    /**
     * Re-evaluate the saver's expandable-launcher toggle against the current enabled-feature set.
     * The controller calls this when features are enabled/disabled while the saver is already showing
     * — the launcher toggle is otherwise computed only at [createView] (mount), and an idle saver
     * never re-mounts on its own, so without this the toggle stays stale (e.g. no way to reach a
     * feature enabled after the saver came up). Default: no-op (themes without a launcher ignore it).
     */
    fun refreshLauncher() {}

    /**
     * The remote-control API faked the screen off (or back on) while this theme is mounted. A theme
     * that keeps doing work nobody can see — the Slideshow's fetch/decode loop — must park for the
     * duration. Deliberately NOT the theme's own pause: a wake must not resume a slideshow the user
     * had paused. Default: no-op (a theme that only draws a clock costs nothing while dark).
     */
    fun setSlideshowSuppressed(suppressed: Boolean) {}
}
