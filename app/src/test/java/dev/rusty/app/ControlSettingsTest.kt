package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Test

class ControlSettingsTest {
    /** Minimal in-memory SharedPreferences storing booleans AND strings. */
    private class FakePrefs : SharedPreferences {
        private val map = HashMap<String, Any?>()
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun getString(k: String, d: String?) = map[k] as? String ?: d
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun putString(k: String, v: String?) = apply { map[k] = v }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
            override fun remove(k: String) = this
            override fun clear() = this
        }
        override fun getAll() = map
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getInt(k: String, d: Int) = d
        override fun getLong(k: String, d: Long) = d
        override fun getFloat(k: String, d: Float) = d
        override fun contains(k: String) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun disabledByDefault() { assertFalse(ControlSettings.isEnabled(FakePrefs())) }

    @Test fun enableRoundTrips() {
        val p = FakePrefs()
        ControlSettings.setEnabled(p, true)
        assertTrue(ControlSettings.isEnabled(p))
    }

    @Test fun requiredPassword_isNullUntilTheSwitchAndTheSecretAgree() {
        val p = FakePrefs()
        val secrets = InMemorySecretStore()

        // Off by default: no password required even when one is stored.
        secrets.put(ControlSettings.SECRET_PASSWORD, "hunter2")
        assertNull(ControlSettings.requiredPassword(p, secrets))

        ControlSettings.setAuthRequired(p, true)
        assertEquals("hunter2", ControlSettings.requiredPassword(p, secrets))
    }

    @Test fun requiredPassword_flagWithoutASecretEnforcesNothing() {
        // The UI can't create this state; if prefs drift into it the API must stay reachable
        // (an unset password can never lock the owner out) rather than 401 everything forever.
        val p = FakePrefs()
        ControlSettings.setAuthRequired(p, true)
        assertNull(ControlSettings.requiredPassword(p, InMemorySecretStore()))
        val blank = InMemorySecretStore().apply { put(ControlSettings.SECRET_PASSWORD, "   ") }
        assertNull(ControlSettings.requiredPassword(p, blank))
    }

    @Test fun deviceIdIsCreatedOnceAndStable() {
        val p = FakePrefs()
        val first = ControlSettings.deviceId(p)
        assertTrue(first.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals(first, ControlSettings.deviceId(p))
    }
}
