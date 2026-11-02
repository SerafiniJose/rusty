package dev.rusty.app

import android.content.SharedPreferences

/**
 * The stored fallback for the receiver's volume, used only when the device exposes no adjustable
 * media stream (see [SystemVolume], which is the master on every normal device).
 *
 * Upstream this was "Startup volume": the volume a NEW Spotify Connect session starts at. It is
 * kept with the same bounds and step so an existing pref stays valid, but the settings panel now
 * moves the system stream instead, and Android remembers that across casts on its own.
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
