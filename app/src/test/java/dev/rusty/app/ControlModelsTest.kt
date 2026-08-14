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

    // ---- ControlUpdateCheck -------------------------------------------------

    @Test fun updateCheckJson_fullShape() {
        val check = ControlUpdateCheck(
            current = "2.3.0",
            status = "update_available",
            latest = ControlUpdateLatest(
                version = "2.4.0",
                notes = "• Remote updates",
                url = "https://github.com/SerafiniJose/rusty/releases/tag/v2.4.0",
                hasApk = true,
            ),
            install = InstallSnapshot(InstallPhase.DOWNLOADING, 42, null),
        )
        val o = JSONObject(check.toJson())
        assertEquals("2.3.0", o.getString("current"))
        assertEquals("update_available", o.getString("status"))
        val latest = o.getJSONObject("latest")
        assertEquals("2.4.0", latest.getString("version"))
        assertEquals("• Remote updates", latest.getString("notes"))
        assertEquals("https://github.com/SerafiniJose/rusty/releases/tag/v2.4.0", latest.getString("url"))
        assertEquals(true, latest.getBoolean("hasApk"))
        val install = o.getJSONObject("install")
        assertEquals("downloading", install.getString("phase"))
        assertEquals(42, install.getInt("progress"))
        assertEquals(false, install.has("error"))
    }

    @Test fun updateCheckJson_noLatestOmitsKey() {
        val check = ControlUpdateCheck(
            current = "2.3.0", status = "error", latest = null,
            install = InstallSnapshot(InstallPhase.IDLE, null, null),
        )
        val o = JSONObject(check.toJson())
        assertEquals(false, o.has("latest"))
        val install = o.getJSONObject("install")
        assertEquals("idle", install.getString("phase"))
        assertEquals(false, install.has("progress"))
        assertEquals(false, install.has("error"))
    }

    @Test fun updateCheckJson_errorPhaseCarriesMessage() {
        val check = ControlUpdateCheck(
            current = "2.3.0", status = "up_to_date", latest = null,
            install = InstallSnapshot(InstallPhase.ERROR, null, "download HTTP 503"),
        )
        val install = JSONObject(check.toJson()).getJSONObject("install")
        assertEquals("error", install.getString("phase"))
        assertEquals("download HTTP 503", install.getString("error"))
        assertEquals(false, install.has("progress"))
    }

    @Test fun updateCheckJson_awaitingConfirmPhaseName() {
        val check = ControlUpdateCheck(
            current = "2.3.0", status = "update_available", latest = null,
            install = InstallSnapshot(InstallPhase.AWAITING_CONFIRM, null, null),
        )
        assertEquals(
            "awaiting_confirm",
            JSONObject(check.toJson()).getJSONObject("install").getString("phase")
        )
    }
}
