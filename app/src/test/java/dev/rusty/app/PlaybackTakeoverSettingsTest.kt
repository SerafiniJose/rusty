package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTakeoverSettingsTest {

    /** Minimal in-memory SharedPreferences for the boolean we use. */
    private class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun apply() {}
            override fun commit() = true
            override fun putString(k: String, v: String?) = this
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
            override fun remove(k: String) = this
            override fun clear() = this
        }
        override fun getAll() = map
        override fun getString(k: String, d: String?) = d
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = d
        override fun getLong(k: String, d: Long) = d
        override fun getFloat(k: String, d: Float) = d
        override fun contains(k: String) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun bothDefaultToOff() {
        val prefs = FakePrefs()
        assertFalse(PlaybackTakeoverSettings.isSwitchPageEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.isShowOnPlaybackEnabled(prefs))
        val toggles = PlaybackTakeoverSettings.toggles(prefs)
        assertFalse(toggles.switchPage)
        assertFalse(toggles.showOnPlayback)
    }

    @Test fun eachKeyRoundTripsIndependently() {
        val prefs = FakePrefs()
        PlaybackTakeoverSettings.setSwitchPage(prefs, true)
        assertTrue(PlaybackTakeoverSettings.isSwitchPageEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.isShowOnPlaybackEnabled(prefs))

        PlaybackTakeoverSettings.setShowOnPlayback(prefs, true)
        val toggles = PlaybackTakeoverSettings.toggles(prefs)
        assertTrue(toggles.switchPage)
        assertTrue(toggles.showOnPlayback)

        PlaybackTakeoverSettings.setSwitchPage(prefs, false)
        assertFalse(PlaybackTakeoverSettings.isSwitchPageEnabled(prefs))
        assertTrue(PlaybackTakeoverSettings.isShowOnPlaybackEnabled(prefs))
    }

    @Test fun theRetiredWakeAndForegroundKeysAreNotReadAnyMore() {
        // The merged toggle ships in an unreleased version, so there is deliberately no migration:
        // a stale value left by a test build must not switch the new toggle on behind the user.
        val prefs = FakePrefs()
        prefs.edit().putBoolean("takeover_wake_screen", true).apply()
        prefs.edit().putBoolean("takeover_foreground", true).apply()
        assertFalse(PlaybackTakeoverSettings.isShowOnPlaybackEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.toggles(prefs).showOnPlayback)
    }
}
