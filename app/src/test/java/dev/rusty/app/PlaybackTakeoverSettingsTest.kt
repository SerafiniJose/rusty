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

    @Test fun allDefaultToOff() {
        val prefs = FakePrefs()
        assertFalse(PlaybackTakeoverSettings.isSwitchPageEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.isBringToFrontEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.isWakeScreenEnabled(prefs))
        val toggles = PlaybackTakeoverSettings.toggles(prefs)
        assertFalse(toggles.switchPage)
        assertFalse(toggles.bringToFront)
        assertFalse(toggles.wakeScreen)
    }

    @Test fun eachKeyRoundTripsIndependently() {
        val prefs = FakePrefs()
        PlaybackTakeoverSettings.setSwitchPage(prefs, true)
        assertTrue(PlaybackTakeoverSettings.isSwitchPageEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.isBringToFrontEnabled(prefs))
        assertFalse(PlaybackTakeoverSettings.isWakeScreenEnabled(prefs))

        PlaybackTakeoverSettings.setBringToFront(prefs, true)
        PlaybackTakeoverSettings.setWakeScreen(prefs, true)
        val toggles = PlaybackTakeoverSettings.toggles(prefs)
        assertTrue(toggles.switchPage)
        assertTrue(toggles.bringToFront)
        assertTrue(toggles.wakeScreen)

        PlaybackTakeoverSettings.setSwitchPage(prefs, false)
        assertFalse(PlaybackTakeoverSettings.isSwitchPageEnabled(prefs))
        assertTrue(PlaybackTakeoverSettings.isBringToFrontEnabled(prefs))
    }
}
