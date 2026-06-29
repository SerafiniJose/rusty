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
}
