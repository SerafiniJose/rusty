package dev.rusty.app

import android.content.SharedPreferences

/** The now-playing "Show Canvas videos" toggle. Default OFF (opt-in). */
object CanvasSettings {
    const val KEY_CANVAS_ENABLED = "canvas_enabled"

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_CANVAS_ENABLED, false)

    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CANVAS_ENABLED, enabled).apply()
    }
}
