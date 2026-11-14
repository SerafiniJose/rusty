package dev.rusty.app

import android.content.SharedPreferences

/**
 * Backdrop choices for the clock screensaver. They exist for one reason: the large thin clock
 * needs a predictable field behind it. The ambient mesh is the original look, but on some panels
 * its moving blooms sit right under the numerals; the flat choices trade that motion for a
 * quieter, higher-contrast field (and [PAPER] flips the face to dark ink).
 *
 * Declaration order is the settings-selector order.
 */
enum class ClockBackdrop {
    AMBIENT, MIDNIGHT, SLATE, EMBER, PAPER;

    val prefValue: String get() = name

    /** Light backdrops need dark ink; everything else keeps the app's warm off-white ink. */
    val isLight: Boolean get() = this == PAPER

    companion object {
        fun fromPrefValue(value: String?): ClockBackdrop =
            values().firstOrNull { it.name == value } ?: AMBIENT
    }
}

/**
 * Reads the clock face's appearance prefs. Kept separate from [ClockTheme] so the settings binder
 * and the 1 Hz screensaver tick agree on defaults and clamping without sharing a view.
 */
object ClockAppearance {
    const val KEY_BACKDROP = "clock_backdrop"
    const val KEY_SCRIM_PERCENT = "clock_scrim_percent"

    /** Enough darkening to lift the clock off a busy mesh without hiding the backdrop. */
    const val DEFAULT_SCRIM_PERCENT = 40

    const val MIN_SCRIM_PERCENT = 0
    const val MAX_SCRIM_PERCENT = 80
    const val SCRIM_STEP = 5

    fun backdrop(prefs: SharedPreferences): ClockBackdrop =
        ClockBackdrop.fromPrefValue(prefs.getString(KEY_BACKDROP, null))

    fun scrimPercent(prefs: SharedPreferences): Int =
        prefs.getInt(KEY_SCRIM_PERCENT, DEFAULT_SCRIM_PERCENT)
            .coerceIn(MIN_SCRIM_PERCENT, MAX_SCRIM_PERCENT)

    /** Quantises an arbitrary slider position to the persisted 5% steps. */
    fun snapScrimPercent(percent: Int): Int {
        val clamped = percent.coerceIn(MIN_SCRIM_PERCENT, MAX_SCRIM_PERCENT)
        return (clamped / SCRIM_STEP) * SCRIM_STEP
    }
}
