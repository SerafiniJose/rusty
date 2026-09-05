package dev.rusty.app

import android.content.SharedPreferences

/**
 * The Spotify "Startup volume" setting: the volume a NEW Spotify Connect session starts at.
 *
 * librespot hard-coded this at ~50%, which meant every fresh connection had to be turned up by
 * hand — useless for a receiver wired into a car or an amplifier, where the real volume knob is
 * downstream (issue #10). Rusty defaults it to 100% and lets it be dialled back.
 *
 * It is deliberately NOT the live volume: changing it never touches a session already playing,
 * it only decides where the next one starts.
 */
object StartupVolumeSettings {
    const val KEY_STARTUP_VOLUME_PERCENT = "startup_volume_percent"

    /** 100% — the receiver hands Spotify's full signal to whatever is downstream. */
    const val DEFAULT_PERCENT = 100

    /** Floor is 10%, not 0: a receiver that starts silent reads as broken, not as configured. */
    const val MIN_PERCENT = 10
    const val MAX_PERCENT = 100
    const val STEP_PERCENT = 5

    fun percent(prefs: SharedPreferences): Int =
        clamp(prefs.getInt(KEY_STARTUP_VOLUME_PERCENT, DEFAULT_PERCENT))

    fun setPercent(prefs: SharedPreferences, percent: Int) {
        prefs.edit().putInt(KEY_STARTUP_VOLUME_PERCENT, clamp(percent)).apply()
    }

    /** Snaps to the slider's step and into range, so a stale or hand-edited pref stays usable. */
    fun clamp(percent: Int): Int {
        val snapped = ((percent + STEP_PERCENT / 2) / STEP_PERCENT) * STEP_PERCENT
        return snapped.coerceIn(MIN_PERCENT, MAX_PERCENT)
    }

    fun label(percent: Int): String = when (clamp(percent)) {
        MAX_PERCENT -> "100% · full"
        else -> "${clamp(percent)}%"
    }
}
