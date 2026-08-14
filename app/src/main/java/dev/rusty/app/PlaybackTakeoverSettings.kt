package dev.rusty.app

import android.content.SharedPreferences

/**
 * The two playback-takeover toggles (Spotify tab). Both default OFF (opt-in).
 *
 * [KEY_SHOW_ON_PLAYBACK] replaced the earlier `takeover_foreground` + `takeover_wake_screen` pair.
 * There is deliberately no migration from those keys: the takeover feature has never shipped, so
 * the only installs holding them are test builds, and reading a stale value would switch the
 * merged toggle on without the user asking.
 */
object PlaybackTakeoverSettings {
    const val KEY_SWITCH_PAGE = "takeover_switch_page"
    const val KEY_SHOW_ON_PLAYBACK = "takeover_show_on_playback"

    fun isSwitchPageEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SWITCH_PAGE, false)

    fun isShowOnPlaybackEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(KEY_SHOW_ON_PLAYBACK, false)

    fun setSwitchPage(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SWITCH_PAGE, enabled).apply()
    }

    fun setShowOnPlayback(prefs: SharedPreferences, enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_ON_PLAYBACK, enabled).apply()
    }

    fun toggles(prefs: SharedPreferences): TakeoverToggles = TakeoverToggles(
        switchPage = isSwitchPageEnabled(prefs),
        showOnPlayback = isShowOnPlaybackEnabled(prefs),
    )
}
