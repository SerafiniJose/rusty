package dev.rusty.app

import android.content.SharedPreferences

/** The three playback-takeover toggles (Spotify tab). All default OFF (opt-in). */
object PlaybackTakeoverSettings {
    const val KEY_SWITCH_PAGE = "takeover_switch_page"
    const val KEY_FOREGROUND = "takeover_foreground"
    const val KEY_WAKE_SCREEN = "takeover_wake_screen"

    fun isSwitchPageEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SWITCH_PAGE, false)

    fun isBringToFrontEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_FOREGROUND, false)

    fun isWakeScreenEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_WAKE_SCREEN, false)

    fun setSwitchPage(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SWITCH_PAGE, enabled).apply()
    }

    fun setBringToFront(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FOREGROUND, enabled).apply()
    }

    fun setWakeScreen(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_WAKE_SCREEN, enabled).apply()
    }

    fun toggles(prefs: SharedPreferences): TakeoverToggles = TakeoverToggles(
        switchPage = isSwitchPageEnabled(prefs),
        bringToFront = isBringToFrontEnabled(prefs),
        wakeScreen = isWakeScreenEnabled(prefs),
    )
}
