package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure decisions inside [CameraShareService], kept on its companion for the same reason
 * `MediaRendererController.shouldRun` was: a [android.app.Service] cannot be exercised on the
 * JVM, but "should sharing be running?" and "what does the shade say?" are ordinary decisions
 * that deserve tests rather than inspection.
 */
class CameraShareServiceTest {

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

    // -- shouldRun ---------------------------------------------------------------------------

    @Test fun `nothing is shared while both switches are off`() {
        assertFalse(CameraShareService.shouldRun(prefs))
    }

    /**
     * Remote Control is a PREREQUISITE, not a nicety: mDNS advertising (the `cam=1` TXT record
     * other Rusty devices scan for) and the snapshot endpoint both live in `ControlService`, so a
     * share running without it is an RTSP port no device can discover and no dashboard can
     * thumbnail.
     */
    @Test fun `the share switch alone does not run — Remote Control is a prerequisite`() {
        CameraShareSettings.setEnabled(prefs, true)
        assertFalse(CameraShareService.shouldRun(prefs))
    }

    @Test fun `Remote Control alone shares nothing`() {
        ControlSettings.setEnabled(prefs, true)
        assertFalse(CameraShareService.shouldRun(prefs))
    }

    @Test fun `both switches on runs the share`() {
        CameraShareSettings.setEnabled(prefs, true)
        ControlSettings.setEnabled(prefs, true)
        assertTrue(CameraShareService.shouldRun(prefs))
    }

    /** Turning Remote Control off underneath a live share has to stop it — same rule, read again. */
    @Test fun `turning Remote Control off stops the share`() {
        CameraShareSettings.setEnabled(prefs, true)
        ControlSettings.setEnabled(prefs, true)
        assertTrue(CameraShareService.shouldRun(prefs))
        ControlSettings.setEnabled(prefs, false)
        assertFalse(CameraShareService.shouldRun(prefs))
    }

    // -- notificationText --------------------------------------------------------------------

    @Test fun `the shade tells an idle share from a streaming one`() {
        assertEquals("Ready for viewers", CameraShareService.notificationText(CameraShareStatus.State.Ready))
        assertEquals("Sharing camera to 1 viewer", CameraShareService.notificationText(CameraShareStatus.State.Streaming(1)))
        assertEquals("Sharing camera to 3 viewers", CameraShareService.notificationText(CameraShareStatus.State.Streaming(3)))
    }

    /**
     * Off is not a theoretical branch: [CameraShareStatus.addListener] REPLAYS the current value,
     * so a share restarting while the previous instance's Off is still published posts this text
     * into the shade before its own first publish lands. It must not claim the camera is being
     * shared at the one moment it is not.
     */
    @Test fun `the shade does not claim to be sharing while the state is Off`() {
        assertEquals("Not sharing", CameraShareService.notificationText(CameraShareStatus.State.Off))
    }

    @Test fun `a failure names its reason in the shade`() {
        assertEquals(
            "Camera unavailable: port 8554 busy",
            CameraShareService.notificationText(CameraShareStatus.State.Unavailable("port 8554 busy")))
        // Unsupported reasons are already whole sentences ("no H.264 encoder on this device").
        assertEquals(
            "no H.264 encoder on this device",
            CameraShareService.notificationText(CameraShareStatus.State.Unsupported("no H.264 encoder on this device")))
    }
}
