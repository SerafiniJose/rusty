package dev.rusty.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlModelsTest {
    private fun snap() = ControlSnapshot(
        deviceId = "abc", deviceName = "Rusty Speaker", version = "2.3.0",
        screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
        volume = ControlVolume(value = 47, fixed = false),
        playing = ControlPlaying(spotify = true, dlna = false),
        slideshowEnabled = true,
    )

    @Test fun jsonMatchesApiContract() {
        val o = JSONObject(snap().toJson())
        assertEquals("abc", o.getJSONObject("device").getString("id"))
        assertEquals("Rusty Speaker", o.getJSONObject("device").getString("name"))
        assertEquals("2.3.0", o.getJSONObject("device").getString("version"))
        // screen.on is what Home Assistant's light entity's on/off state is built from — the one
        // field in this payload that a typo would break most visibly and most silently.
        assertEquals(true, o.getJSONObject("screen").getBoolean("on"))
        assertEquals(80, o.getJSONObject("screen").getInt("brightness"))
        assertEquals("system", o.getJSONObject("screen").getString("mode"))
        assertEquals(true, o.getJSONObject("screen").getBoolean("writable"))
        assertEquals(true, o.getJSONObject("screen").getBoolean("available"))
        assertEquals(47, o.getJSONObject("volume").getInt("value"))
        assertEquals(false, o.getJSONObject("volume").getBoolean("fixed"))
        assertEquals(true, o.getJSONObject("playing").getBoolean("spotify"))
        assertEquals(false, o.getJSONObject("playing").getBoolean("dlna"))
        assertEquals(true, o.getJSONObject("slideshow").getBoolean("enabled"))
    }
}
