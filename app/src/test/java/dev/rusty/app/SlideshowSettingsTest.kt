package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SlideshowSettingsTest {

    /** Minimal in-memory SharedPreferences (same idiom as CanvasSettingsTest). */
    private class FakePrefs : SharedPreferences {
        val map = HashMap<String, Any?>()
        override fun getBoolean(key: String, defValue: Boolean) = map[key] as? Boolean ?: defValue
        override fun getString(k: String, d: String?) = map[k] as? String ?: d
        override fun getInt(k: String, d: Int) = map[k] as? Int ?: d
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
            override fun putString(k: String, v: String?) = apply { map[k] = v }
            override fun putInt(k: String, v: Int) = apply { map[k] = v }
            override fun remove(k: String) = apply { map.remove(k) }
            override fun apply() {}
            override fun commit() = true
            override fun putStringSet(k: String, v: MutableSet<String>?) = this
            override fun putLong(k: String, v: Long) = this
            override fun putFloat(k: String, v: Float) = this
            override fun clear() = this
        }
        override fun getAll() = map
        override fun getStringSet(k: String, d: MutableSet<String>?) = d
        override fun getLong(k: String, d: Long) = d
        override fun getFloat(k: String, d: Float) = d
        override fun contains(k: String) = map.containsKey(k)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test fun disabledByDefaultAndRoundTrips() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        assertFalse(SlideshowSettings.isEnabled(prefs))
        SlideshowSettings.setEnabled(prefs, true)
        assertTrue(SlideshowSettings.isEnabled(prefs))
    }

    @Test fun configIsNullUntilBothUrlAndKeySaved() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        assertNull(SlideshowSettings.config(prefs, secrets))
        assertEquals(ImmichConnectionSave.INVALID, SlideshowSettings.saveConnection(prefs, secrets, "  ", "key"))
        assertEquals(ImmichConnectionSave.SAVED_CONFIG_CHANGED,
            SlideshowSettings.saveConnection(prefs, secrets, "192.168.7.30:2283", "abc"))
        assertEquals(ImmichConfig("http://192.168.7.30:2283", "abc"), SlideshowSettings.config(prefs, secrets))
    }

    @Test fun saveNormalizesUrlAndStripsTrailingSlash() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local/", "k")
        assertEquals("http://immich.local", SlideshowSettings.config(prefs, secrets)!!.baseUrl)
    }

    @Test fun configChangeWipesFiltersButResaveDoesNot() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://a", "k1")
        SlideshowSettings.setFilters(prefs, ImmichFilters(listOf("al1", "al2"), listOf("p1"), listOf("t1")))
        // Identical re-save: filters survive.
        assertEquals(ImmichConnectionSave.SAVED, SlideshowSettings.saveConnection(prefs, secrets, "http://a", "k1"))
        assertEquals(listOf("al1", "al2"), SlideshowSettings.filters(prefs).albumIds)
        // Key change: filters wiped (stale foreign IDs).
        assertEquals(ImmichConnectionSave.SAVED_CONFIG_CHANGED,
            SlideshowSettings.saveConnection(prefs, secrets, "http://a", "k2"))
        assertEquals(ImmichFilters(emptyList(), emptyList(), emptyList()), SlideshowSettings.filters(prefs))
    }

    @Test fun connectionGenerationBumpsOnlyOnEffectiveChange() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        assertEquals(0, SlideshowSettings.connectionGeneration(prefs))
        SlideshowSettings.saveConnection(prefs, secrets, "http://a", "k1")   // change
        assertEquals(1, SlideshowSettings.connectionGeneration(prefs))
        SlideshowSettings.saveConnection(prefs, secrets, "http://a", "k1")   // re-save, no change
        assertEquals(1, SlideshowSettings.connectionGeneration(prefs))
        SlideshowSettings.saveConnection(prefs, secrets, "http://a", "k2")   // key-only change
        assertEquals(2, SlideshowSettings.connectionGeneration(prefs))
        SlideshowSettings.saveConnection(prefs, secrets, " ", "k2")          // INVALID
        assertEquals(2, SlideshowSettings.connectionGeneration(prefs))
    }

    /** The point of the SecretStore: the key goes there and NOWHERE into the plaintext prefs file
     *  (which is what `adb backup` and cloud backup copy off the device). */
    @Test fun apiKeyIsWrittenToSecretStoreAndNeverToPrefs() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://a", "s3cret")
        assertEquals("s3cret", secrets.get(SlideshowSettings.KEY_API_KEY))
        assertEquals("s3cret", SlideshowSettings.apiKey(secrets))
        assertFalse(prefs.map.containsKey(SlideshowSettings.KEY_API_KEY))
        assertFalse(prefs.map.values.any { it == "s3cret" })
    }

    /** A key present without a URL (or vice versa) is not a usable config. */
    @Test fun configNeedsBothHalvesEvenAcrossTheTwoStores() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        secrets.put(SlideshowSettings.KEY_API_KEY, "k")
        assertNull(SlideshowSettings.config(prefs, secrets))
    }

    @Test fun cleartextIsFlaggedForHttpOnly() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        assertFalse(SlideshowSettings.isCleartext(prefs))
        SlideshowSettings.saveConnection(prefs, secrets, "192.168.7.30:2283", "k")
        assertTrue(SlideshowSettings.isCleartext(prefs))
        SlideshowSettings.saveConnection(prefs, secrets, "https://immich.example.com", "k")
        assertFalse(SlideshowSettings.isCleartext(prefs))
    }

    @Test fun filtersRoundTripAndDefaultEmpty() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        assertEquals(ImmichFilters(emptyList(), emptyList(), emptyList()), SlideshowSettings.filters(prefs))
        SlideshowSettings.setFilters(prefs, ImmichFilters(emptyList(), listOf("p1", "p2"), emptyList()))
        assertEquals(listOf("p1", "p2"), SlideshowSettings.filters(prefs).personIds)
        SlideshowSettings.setFilters(prefs, ImmichFilters(emptyList(), emptyList(), emptyList()))
        assertEquals(emptyList<String>(), SlideshowSettings.filters(prefs).personIds)
    }

    @Test fun setFiltersWritesAllThreeCategoriesAtomically() {
        val prefs = FakePrefs()
        val u1 = "11111111-2222-3333-4444-555555555555"
        val u2 = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"
        SlideshowSettings.setFilters(prefs, ImmichFilters(listOf(u1), listOf(u2), emptyList()))
        assertEquals(ImmichFilters(listOf(u1), listOf(u2), emptyList()), SlideshowSettings.filters(prefs))
    }

    @Test fun displayDefaultsMatchSpec() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        assertEquals(45, SlideshowSettings.intervalSeconds(prefs))
        assertTrue(SlideshowSettings.showClock(prefs))
        assertTrue(SlideshowSettings.showInfo(prefs))
        assertTrue(SlideshowSettings.zoomEnabled(prefs))
        assertTrue(SlideshowSettings.splitViewEnabled(prefs))
        assertTrue(45 in SlideshowSettings.INTERVAL_STEPS)
        assertEquals("45 seconds", SlideshowSettings.intervalLabel(45))
        assertEquals("2 minutes", SlideshowSettings.intervalLabel(120))
    }

    @Test fun intervalIndexForExactStepIsThatStepsIndex() {
        SlideshowSettings.INTERVAL_STEPS.forEachIndexed { index, seconds ->
            assertEquals(index, SlideshowSettings.intervalIndexFor(seconds))
        }
    }

    @Test fun intervalIndexForDefaultIsItsStepIndex() {
        assertEquals(
            SlideshowSettings.INTERVAL_STEPS.indexOf(SlideshowSettings.DEFAULT_INTERVAL_SECONDS),
            SlideshowSettings.intervalIndexFor(SlideshowSettings.DEFAULT_INTERVAL_SECONDS),
        )
    }

    @Test fun intervalIndexForOffStepValueSnapsToNearestStepNotMinusOne() {
        // 37 is not in INTERVAL_STEPS; a raw indexOf() would return -1 and crash steps[index].
        val index = SlideshowSettings.intervalIndexFor(37)
        assertTrue(index in SlideshowSettings.INTERVAL_STEPS.indices)
        assertEquals(SlideshowSettings.INTERVAL_STEPS.indexOf(30), index)
        // Out-of-range values clamp to the ends rather than falling off the list.
        assertEquals(0, SlideshowSettings.intervalIndexFor(1))
        assertEquals(SlideshowSettings.INTERVAL_STEPS.lastIndex, SlideshowSettings.intervalIndexFor(99_999))
    }

    @Test fun accountNameRoundTrips() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.setAccountName(prefs, "Jose")
        assertEquals("Jose", SlideshowSettings.accountName(prefs))
    }

    @Test fun accountNameBlankReadsAsNull() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.setAccountName(prefs, "")
        assertNull(SlideshowSettings.accountName(prefs))
    }

    @Test fun changingConnectionClearsStoredAccountName() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY1")
        SlideshowSettings.setAccountName(prefs, "Jose")
        // Different key => effective change => name belongs to the old server, must be dropped.
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY2")
        assertNull(SlideshowSettings.accountName(prefs))
    }

    @Test fun resavingSameConnectionKeepsStoredAccountName() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY1")
        SlideshowSettings.setAccountName(prefs, "Jose")
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY1")
        assertEquals("Jose", SlideshowSettings.accountName(prefs))
    }

    @Test fun verifiedDefaultsFalse() {
        assertFalse(SlideshowSettings.isVerified(FakePrefs()))
    }

    @Test fun verifiedRoundTrips() {
        val prefs = FakePrefs()
        SlideshowSettings.setVerified(prefs, true)
        assertTrue(SlideshowSettings.isVerified(prefs))
        SlideshowSettings.setVerified(prefs, false)
        assertFalse(SlideshowSettings.isVerified(prefs))
    }

    // A changed connection can't inherit the previous key's "verified" verdict — the new key hasn't
    // proven itself yet, so the header must drop back to "Key not verified" until a Save confirms it.
    @Test fun changingConnectionClearsVerified() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY1")
        SlideshowSettings.setVerified(prefs, true)
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY2")
        assertFalse(SlideshowSettings.isVerified(prefs))
    }

    @Test fun resavingSameConnectionKeepsVerified() {
        val prefs = FakePrefs()
        val secrets = InMemorySecretStore()
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY1")
        SlideshowSettings.setVerified(prefs, true)
        SlideshowSettings.saveConnection(prefs, secrets, "http://immich.local", "KEY1")
        assertTrue(SlideshowSettings.isVerified(prefs))
    }
}
