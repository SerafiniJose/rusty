package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The signed-in Home Assistant account name is produced ONLY by a live WebView discovery round-trip
 * ([HomeAssistantDashboards.parseDiscoveryOrNull] → `HaDiscovery.Loaded.account`), so it exists just
 * while the HA feature is open. Any surface that never opens that WebView — the Services & status
 * page — could otherwise say "Connected" but never "Signed in as Marco" after a process restart.
 * [HomeAssistantFeature.KEY_ACCOUNT_NAME] is that mirror, and these tests pin the two properties it
 * has to keep to be safe: an absent/blank name reads as null (never an empty identity), and the key
 * is server-scoped, so a sign-out or a server change wipes it with the rest of the session.
 */
class HaAccountNameTest {

    /** Minimal in-memory SharedPreferences (same idiom as ControlSettingsTest). `remove` really
     *  removes here — clearing the key is half of what is under test. */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun getString(k: String, d: String?) = map[k] as? String ?: d
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun putString(k: String, v: String?) = apply { map[k] = v }
            override fun remove(k: String) = apply { map.remove(k) }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putInt(k: String, v: Int) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
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

    @Test fun accountNameIsNullUntilDiscoveryStoresOne() {
        assertNull(HomeAssistantFeature.accountName(FakePrefs()))
    }

    @Test fun accountNameRoundTrips() {
        val prefs = FakePrefs()
        HomeAssistantFeature.setAccountName(prefs, "Marco")
        assertEquals("Marco", HomeAssistantFeature.accountName(prefs))
    }

    /** A blank name is not an identity: the Info page must fall back to "Connected", not to a row
     *  that names nobody. */
    @Test fun blankNameReadsAsNull() {
        val prefs = FakePrefs()
        HomeAssistantFeature.setAccountName(prefs, "   ")
        assertNull(HomeAssistantFeature.accountName(prefs))
    }

    @Test fun setAccountNameNullRemovesTheKey() {
        val prefs = FakePrefs()
        HomeAssistantFeature.setAccountName(prefs, "Marco")
        assertTrue(prefs.contains(HomeAssistantFeature.KEY_ACCOUNT_NAME))
        HomeAssistantFeature.setAccountName(prefs, null)
        assertFalse(prefs.contains(HomeAssistantFeature.KEY_ACCOUNT_NAME))
        assertNull(HomeAssistantFeature.accountName(prefs))
    }

    /** The name belongs to one server's session: signing out or pointing Rusty at another Home
     *  Assistant must not leave the previous user's name on the new one. */
    @Test fun accountNameIsWipedByAServerReset() {
        assertTrue(HomeAssistantFeature.SERVER_RESET_KEYS.contains(HomeAssistantFeature.KEY_ACCOUNT_NAME))
        val prefs = FakePrefs()
        HomeAssistantFeature.setAccountName(prefs, "Marco")
        val edit = prefs.edit()
        HomeAssistantFeature.SERVER_RESET_KEYS.forEach { edit.remove(it) }
        edit.apply()
        assertNull(HomeAssistantFeature.accountName(prefs))
    }
}
