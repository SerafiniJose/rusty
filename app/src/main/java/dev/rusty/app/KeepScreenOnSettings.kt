package dev.rusty.app

import android.content.SharedPreferences

/** The General "Keep screen on" toggle. Default OFF (opt-in). */
object KeepScreenOnSettings {
    const val KEY_KEEP_SCREEN_ON = "keep_screen_on"

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_KEEP_SCREEN_ON, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_KEEP_SCREEN_ON, enabled).apply()
    }
}
