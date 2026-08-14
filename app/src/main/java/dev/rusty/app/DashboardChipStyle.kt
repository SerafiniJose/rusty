package dev.rusty.app

/**
 * Pure styling decisions for the HA dashboard switcher chips in the shell bottom bar. The chips
 * render as round icon-only pills (visually one family with the settings / app-selector buttons
 * beside them); [label] decides when a chip carries its full title text instead.
 */
object DashboardChipStyle {

    /** The text a chip shows: its full title while active (the always-visible "tooltip" naming the
     *  current dashboard) or while holding D-pad focus (a TV user's preview of what a click would
     *  select); nothing otherwise (icon-only pill). */
    fun label(title: String, active: Boolean, focused: Boolean): String =
        if (active || focused) title else ""
}
