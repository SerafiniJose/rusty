package dev.rusty.app

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraStoreTest {
    private val cam = CameraRecord("cam_01ab23cd", "Front door", "rtsp://192.168.2.30:554/s1", null, null, false, true, 0)

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

    @Test fun roundTrips() = assertEquals(listOf(cam), CameraCodec.decode(CameraCodec.encode(listOf(cam))))
    @Test fun badJsonDecodesEmpty() { assertEquals(emptyList<CameraRecord>(), CameraCodec.decode("{nope")); assertEquals(emptyList<CameraRecord>(), CameraCodec.decode(null)) }
    @Test fun missingOptionalFieldsUseDefaults() {
        val v1 = """[{"id":"cam_x","name":"n","rtspUrl":"rtsp://h/","position":0}]"""
        val c = CameraCodec.decode(v1).single()
        assertEquals(null, c.snapshotUrl); assertEquals(false, c.audioEnabled); assertEquals(true, c.forceTcp)
    }
    @Test fun splitsInlineCredentials() {
        val s = CameraCodec.splitInlineCredentials("rtsp://admin:se%40cret@192.168.2.30:554/s1")
        assertEquals(SplitUrl("rtsp://192.168.2.30:554/s1", "admin", "se@cret"), s)
    }
    @Test fun splitLeavesPlainUrlAlone() =
        assertEquals(SplitUrl("rtsp://h:554/s1", null, null), CameraCodec.splitInlineCredentials("rtsp://h:554/s1"))
    @Test fun credentialKeysAreStable() =
        assertEquals("camera_user_cam_x" to "camera_pass_cam_x", CameraStore.credentialKeys("cam_x"))

    @Test fun storeLoadSavesSortedByPosition() {
        val prefs = FakePrefs()
        val a = CameraRecord("cam_a", "A", "rtsp://h1/", null, null, false, true, 1)
        val b = CameraRecord("cam_b", "B", "rtsp://h2/", null, null, false, true, 0)
        CameraStore.save(prefs, listOf(a, b))
        assertEquals(listOf(b, a), CameraStore.load(prefs))
    }

    @Test fun storeLoadEmptyWhenUnset() {
        val prefs = FakePrefs()
        assertEquals(emptyList<CameraRecord>(), CameraStore.load(prefs))
    }

    @Test
    fun `decode without a mainRtspUrl key yields null main stream`() {
        val json = """[{"id":"cam_1","name":"Door","rtspUrl":"rtsp://h/sub","snapshotUrl":null,"audioEnabled":false,"forceTcp":true,"position":0}]"""
        val cam = CameraCodec.decode(json).single()
        assertNull(cam.mainRtspUrl)
    }

    @Test
    fun `mainRtspUrl round-trips through encode and decode`() {
        val cam = CameraRecord("cam_1", "Door", "rtsp://h/sub", "rtsp://h/main", null, false, true, 0)
        val back = CameraCodec.decode(CameraCodec.encode(listOf(cam))).single()
        assertEquals("rtsp://h/main", back.mainRtspUrl)
    }
}
