package dev.rusty.app

import dev.rusty.app.renderer.HttpRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** `POST /api/camera/share` — the control page's switch and lens chips for THIS device's camera. */
class ControlCameraShareProtocolTest {

    /** Implements the unrelated half of the seam by refusing; no test here reaches it. */
    private open class Bare : ControlRuntime {
        val snap = ControlSnapshot(
            deviceId = "abc", deviceName = "Rusty", version = "2.7.0",
            screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
            volume = ControlVolume(value = 47, fixed = false),
            playing = ControlPlaying(spotify = false, dlna = false),
            slideshowEnabled = false,
            panel = ControlPanel(null, emptyList(), ControlLockscreen(ScreensaverThemeId.CLOCK, emptyList())),
            app = ControlApp(foreground = false, canBringForward = true),
        )
        override fun snapshot() = snap
        override fun setScreen(on: Boolean, brightness: Int?): ControlSnapshot = error("unused")
        override fun setVolume(percent: Int): ControlSnapshot? = error("unused")
        override fun setPanel(id: ControlPanelId): ControlPanelResult = error("unused")
        override fun setLockscreenTheme(theme: ScreensaverThemeId): ControlLockscreenResult = error("unused")
        override fun setForeground(on: Boolean): ControlForegroundResult = error("unused")
        override fun filters(): ImmichFilters = error("unused")
        override fun setFilters(f: ImmichFilters) = error("unused")
        override fun immichList(kind: String): ControlImmichResult = error("unused")
        override fun controlPageHtml(): String = error("unused")
        override fun announceText(text: String): ControlAnnounceResult = error("unused")
        override fun ttsVoices(): ControlTtsVoices = error("unused")
        override fun setTtsVoice(selector: VoiceSelector): ControlTtsVoiceResult = error("unused")
        override fun downloadTtsVoice(voiceId: String): ControlVoiceDownloadStart = error("unused")
        override fun deleteTtsVoice(voiceId: String): ControlVoiceDeleteResult = error("unused")
        override fun updateCheck(): ControlUpdateCheck = error("unused")
        override fun startUpdateInstall(): ControlInstallStart = error("unused")
    }

    private class Runtime : Bare() {
        val calls = mutableListOf<ControlCameraShareCommand>()
        var result: ControlCameraShareResult = ControlCameraShareResult.Ok(snap)
        override fun setCameraShare(command: ControlCameraShareCommand): ControlCameraShareResult {
            calls.add(command)
            return result
        }
    }

    private val localHosts = setOf("192.168.7.116")

    private fun post(body: String, rt: Bare = Runtime()) = ControlProtocol.route(
        HttpRequest("POST", "/api/camera/share", linkedMapOf("HOST" to "192.168.7.116", "CONTENT-TYPE" to "application/json"), body),
        rt, localHosts,
    )

    @Test fun on_switchesTheShareAndReturnsSnapshot() {
        val rt = Runtime()
        val res = post("""{"on":true}""", rt)
        assertEquals(200, res.status)
        assertEquals(listOf(ControlCameraShareCommand(on = true, lens = null)), rt.calls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    @Test fun off_switchesTheShareOff() {
        val rt = Runtime()
        assertEquals(200, post("""{"on":false}""", rt).status)
        assertEquals(listOf(ControlCameraShareCommand(on = false, lens = null)), rt.calls)
    }

    @Test fun lens_selectsALens() {
        val rt = Runtime()
        assertEquals(200, post("""{"lens":"back"}""", rt).status)
        assertEquals(listOf(ControlCameraShareCommand(on = null, lens = CameraShareSettings.Lens.BACK)), rt.calls)
    }

    @Test fun onAndLensTogether_arriveAsOneCommand() {
        val rt = Runtime()
        assertEquals(200, post("""{"on":true,"lens":"front"}""", rt).status)
        assertEquals(listOf(ControlCameraShareCommand(on = true, lens = CameraShareSettings.Lens.FRONT)), rt.calls)
    }

    @Test fun emptyCommand_400() {
        val rt = Runtime()
        assertEquals(400, post("""{}""", rt).status)
        assertTrue(rt.calls.isEmpty())
    }

    @Test fun nonBooleanOn_400() {
        val rt = Runtime()
        assertEquals(400, post("""{"on":1}""", rt).status)
        assertEquals(400, post("""{"on":"true"}""", rt).status)
        assertTrue(rt.calls.isEmpty())
    }

    @Test fun unknownLens_400() {
        val rt = Runtime()
        assertEquals(400, post("""{"lens":"wide"}""", rt).status)
        assertEquals(400, post("""{"lens":2}""", rt).status)
        assertTrue(rt.calls.isEmpty())
    }

    @Test fun malformedJson_400() {
        val rt = Runtime()
        assertEquals(400, post("{", rt).status)
        assertTrue(rt.calls.isEmpty())
    }

    @Test fun unsupportedDevice_404() {
        val rt = Runtime()
        rt.result = ControlCameraShareResult.Unsupported
        val res = post("""{"on":true}""", rt)
        assertEquals(404, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("cannot share"))
    }

    @Test fun needsForeground_409_saysRustyMustBeOnScreen() {
        val rt = Runtime()
        rt.result = ControlCameraShareResult.NeedsForeground
        val res = post("""{"on":true}""", rt)
        assertEquals(409, res.status)
        assertEquals("needs_foreground", JSONObject(res.body).getString("reason"))
        assertTrue(JSONObject(res.body).getString("error").contains("on screen"))
    }

    @Test fun permissionNeeded_409_pointsAtTheDeviceSettings() {
        val rt = Runtime()
        rt.result = ControlCameraShareResult.PermissionNeeded
        val res = post("""{"on":true}""", rt)
        assertEquals(409, res.status)
        assertEquals("permission_needed", JSONObject(res.body).getString("reason"))
        assertTrue(JSONObject(res.body).getString("error").contains("Cameras settings"))
    }

    /** The runtime default: a device with no share support answers 404 without any override. */
    @Test fun defaultRuntime_isUnsupported() {
        assertEquals(404, post("""{"on":true}""", Bare()).status)
    }

    @Test fun requiresJsonContentType() {
        val rt = Runtime()
        val res = ControlProtocol.route(
            HttpRequest("POST", "/api/camera/share", linkedMapOf("HOST" to "192.168.7.116", "CONTENT-TYPE" to "text/plain"), """{"on":true}"""),
            rt, localHosts,
        )
        assertEquals(415, res.status)
        assertTrue(rt.calls.isEmpty())
    }
}
