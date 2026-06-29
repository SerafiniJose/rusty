package dev.rusty.app

import kotlin.math.cos
import kotlin.math.floor

/**
 * Pure math for the OLED screensaver's burn-in animation, factored out of [OledTheme] so the
 * brightness curve and the jitter bounds are unit-testable without a View or Choreographer.
 */
object OledBurnIn {

    /** Full breathe cycle (fade up from black to peak and back down) in milliseconds. */
    const val PERIOD_MS = 8_000L

    /**
     * Group alpha for a cycle [phase] in [0,1): a raised cosine that is 0 at the ends (text fully
     * gone) and 1 at the midpoint (peak). A peak of 1.0 leaves each TextView's own dim color
     * intact, so the look at full brightness matches the static OLED theme.
     */
    fun breatheAlpha(phase: Float): Float {
        val p = (phase - floor(phase)).toDouble() // wrap defensively into [0,1)
        return (0.5 - 0.5 * cos(2.0 * Math.PI * p)).toFloat()
    }

    /**
     * Half-extent (px) of allowed center travel along one axis: how far the center-anchored text
     * group may shift from screen center while keeping [insetPx] padding from both edges. Never
     * negative — a group larger than the safe area is pinned to center (0 travel).
     */
    fun maxTravel(rootSizePx: Int, groupSizePx: Int, insetPx: Float): Float {
        val half = rootSizePx / 2f - insetPx - groupSizePx / 2f
        return if (half > 0f) half else 0f
    }
}
