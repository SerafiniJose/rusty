package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepScreenOnSettingsTest {

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

    @Test fun defaultsToOff() {
        assertFalse(KeepScreenOnSettings.isEnabled(FakePrefs()))
    }

    @Test fun roundTripsEnabled() {
        val prefs = FakePrefs()
        KeepScreenOnSettings.setEnabled(prefs, true)
        assertTrue(KeepScreenOnSettings.isEnabled(prefs))
        KeepScreenOnSettings.setEnabled(prefs, false)
        assertFalse(KeepScreenOnSettings.isEnabled(prefs))
    }
}
