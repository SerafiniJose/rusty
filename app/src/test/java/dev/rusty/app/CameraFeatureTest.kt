package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mirrors the DLNA feature-registration tests (`DlnaFeatureWiringTest`, `FeatureDisableTest`). */
class CameraFeatureTest {

    /** Minimal in-memory SharedPreferences for the boolean pref we use. */
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

    @Test fun idAndWireNameAreCamera() {
        assertEquals(FeatureId.CAMERA, CameraFeature.id)
        assertEquals("camera", FeatureId.CAMERA.name.lowercase())
    }

    @Test fun disabledByDefault() {
        assertFalse(CameraFeature.isEnabled(FakePrefs()))
    }

    @Test fun enabledByPref() {
        val prefs = FakePrefs()
        prefs.edit().putBoolean(CameraFeature.KEY_ENABLED, true).apply()
        assertTrue(CameraFeature.isEnabled(prefs))
    }

    @Test fun doesNotRetainStartedWhenHidden() {
        assertFalse(CameraFeature.retainStartedWhenHidden)
    }

    @Test fun registeredInFeatureRegistryAfterDlna() {
        val ids = FeatureRegistry.all.map { it.id }
        assertTrue(ids.contains(FeatureId.CAMERA))
        val dlnaIndex = ids.indexOf(FeatureId.DLNA)
        val cameraIndex = ids.indexOf(FeatureId.CAMERA)
        assertTrue(dlnaIndex >= 0 && cameraIndex > dlnaIndex)
    }
}
