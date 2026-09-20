package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraShareSettingsTest {
    private val prefs = FakePrefs()

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

    @Test fun `the default resolution is 720p, what the share used before the setting existed`() {
        assertEquals(CameraShareSettings.Resolution.MEDIUM, CameraShareSettings.resolution(prefs))
        assertEquals(1280, CameraShareSettings.Resolution.MEDIUM.width)
        assertEquals(720, CameraShareSettings.Resolution.MEDIUM.height)
    }

    @Test fun `resolution round-trips and an unknown pref falls back to 720p`() {
        CameraShareSettings.setResolution(prefs, CameraShareSettings.Resolution.HIGH)
        assertEquals(CameraShareSettings.Resolution.HIGH, CameraShareSettings.resolution(prefs))
        prefs.edit().putString(CameraShareSettings.KEY_RESOLUTION, "4k").apply()
        assertEquals(CameraShareSettings.Resolution.MEDIUM, CameraShareSettings.resolution(prefs))
    }

    @Test fun `the resolution pref key is the one 2_6 installs already wrote`() {
        assertEquals("camera_share_quality", CameraShareSettings.KEY_RESOLUTION)
    }

    @Test fun `tier and frame rate default to Good and 15 fps`() {
        assertEquals(CameraShareSettings.Tier.GOOD, CameraShareSettings.tier(prefs))
        assertEquals(CameraShareSettings.FrameRate.FPS_15, CameraShareSettings.frameRate(prefs))
        assertEquals(15, CameraShareSettings.FrameRate.FPS_15.fps)
    }

    @Test fun `tier and frame rate round-trip and fall back on garbage`() {
        CameraShareSettings.setTier(prefs, CameraShareSettings.Tier.BEST)
        CameraShareSettings.setFrameRate(prefs, CameraShareSettings.FrameRate.FPS_30)
        assertEquals(CameraShareSettings.Tier.BEST, CameraShareSettings.tier(prefs))
        assertEquals(CameraShareSettings.FrameRate.FPS_30, CameraShareSettings.frameRate(prefs))
        prefs.edit().putString(CameraShareSettings.KEY_TIER, "ultra").putString(CameraShareSettings.KEY_FPS, "60").apply()
        assertEquals(CameraShareSettings.Tier.GOOD, CameraShareSettings.tier(prefs))
        assertEquals(CameraShareSettings.FrameRate.FPS_15, CameraShareSettings.frameRate(prefs))
    }

    @Test fun `the default encoding is exactly the old fixed budget`() {
        val e = CameraShareSettings.encoding(prefs)
        assertEquals(CameraShareSettings.Resolution.MEDIUM, e.resolution)
        assertEquals(CameraShareSettings.Tier.GOOD, e.tier)
        assertEquals(CameraShareSettings.FrameRate.FPS_15, e.frameRate)
        assertEquals(1_500_000, e.bitrate)
    }

    @Test fun `bitrate table at 15 fps`() {
        fun bps(r: CameraShareSettings.Resolution, t: CameraShareSettings.Tier) =
            CameraShareSettings.Encoding(r, t, CameraShareSettings.FrameRate.FPS_15).bitrate
        val LOW = CameraShareSettings.Resolution.LOW; val MEDIUM = CameraShareSettings.Resolution.MEDIUM
        val HIGH = CameraShareSettings.Resolution.HIGH
        val BASIC = CameraShareSettings.Tier.BASIC; val GOOD = CameraShareSettings.Tier.GOOD; val BEST = CameraShareSettings.Tier.BEST
        assertEquals(400_000, bps(LOW, BASIC)); assertEquals(800_000, bps(LOW, GOOD)); assertEquals(1_200_000, bps(LOW, BEST))
        assertEquals(800_000, bps(MEDIUM, BASIC)); assertEquals(1_500_000, bps(MEDIUM, GOOD)); assertEquals(2_500_000, bps(MEDIUM, BEST))
        assertEquals(1_500_000, bps(HIGH, BASIC)); assertEquals(3_000_000, bps(HIGH, GOOD)); assertEquals(5_000_000, bps(HIGH, BEST))
    }

    @Test fun `frame rate scales the budget sub-linearly`() {
        val R = CameraShareSettings.Resolution.MEDIUM; val T = CameraShareSettings.Tier.GOOD
        assertEquals(1_125_000, CameraShareSettings.Encoding(R, T, CameraShareSettings.FrameRate.FPS_10).bitrate)
        assertEquals(2_400_000, CameraShareSettings.Encoding(R, T, CameraShareSettings.FrameRate.FPS_30).bitrate)
    }

    @Test fun `enabled flag round-trips`() {
        assertFalse(CameraShareSettings.isEnabled(prefs))
        CameraShareSettings.setEnabled(prefs, true)
        assertTrue(CameraShareSettings.isEnabled(prefs))
    }

    @Test fun `lens round-trips and defaults to front`() {
        assertEquals(CameraShareSettings.Lens.FRONT, CameraShareSettings.lens(prefs))
        CameraShareSettings.setLens(prefs, CameraShareSettings.Lens.BACK)
        assertEquals(CameraShareSettings.Lens.BACK, CameraShareSettings.lens(prefs))
    }

    @Test fun `urls use the fixed port and paths`() {
        assertEquals("rtsp://192.168.7.116:8554/live", CameraShareSettings.streamUrl("192.168.7.116"))
        assertEquals("http://192.168.7.116:8765/api/camera/local/snapshot.jpg", CameraShareSettings.snapshotUrl("192.168.7.116"))
        assertEquals("http://192.168.7.116:9000/api/camera/local/snapshot.jpg", CameraShareSettings.snapshotUrl("192.168.7.116", 9000))
    }
}
