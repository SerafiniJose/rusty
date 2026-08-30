package dev.rusty.app

import dev.rusty.app.renderer.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The password gate in [ControlProtocol.route]. Own file-private fake (the
 * [ControlAnnounceProtocolTest] idiom) so these tests stay independent of the main fake growing.
 */
private class FakeAuthRuntime : ControlRuntime {
    private val snap = ControlSnapshot(
        deviceId = "abc", deviceName = "Rusty Speaker", version = "2.4.0",
        screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
        volume = ControlVolume(value = 47, fixed = false),
        playing = ControlPlaying(spotify = false, dlna = false),
        slideshowEnabled = false,
        panel = ControlPanel(
            active = ControlPanelId.SPOTIFY,
            available = ControlPanelId.values().toList(),
            lockscreen = ControlLockscreen(ScreensaverThemeId.CLOCK, ScreensaverThemeId.values().toList()),
        ),
        app = ControlApp(foreground = true, canBringForward = true),
    )

    var password: String? = null
    override fun requiredPassword(): String? = password

    val screenCalls = mutableListOf<Pair<Boolean, Int?>>()
    override fun setScreen(on: Boolean, brightness: Int?): ControlSnapshot {
        screenCalls.add(on to brightness)
        return snap
    }

    override fun snapshot(): ControlSnapshot = snap
    override fun setVolume(percent: Int): ControlSnapshot? = snap
    override fun setPanel(id: ControlPanelId): ControlPanelResult = ControlPanelResult.Ok(snap)
    override fun setLockscreenTheme(theme: ScreensaverThemeId): ControlLockscreenResult =
        ControlLockscreenResult.Ok(snap)
    override fun setForeground(on: Boolean): ControlForegroundResult = ControlForegroundResult.Ok(snap)
    override fun filters(): ImmichFilters = ImmichFilters(emptyList(), emptyList(), emptyList())
    override fun setFilters(f: ImmichFilters) {}
    override fun immichList(kind: String): ControlImmichResult = ControlImmichResult.Ok(emptyList())
    override fun controlPageHtml(): String = "<html>page</html>"
    override fun updateCheck(): ControlUpdateCheck = ControlUpdateCheck(
        current = "2.4.0", status = "up_to_date", latest = null,
        install = InstallSnapshot(InstallPhase.IDLE, null, null),
    )
    override fun startUpdateInstall(): ControlInstallStart = ControlInstallStart.NO_UPDATE
    override fun announceText(text: String): ControlAnnounceResult = ControlAnnounceResult.Ok
    override fun ttsVoices(): ControlTtsVoices = ControlTtsVoices(TtsVoices.SYSTEM_DEFAULT, emptyList())
    override fun setTtsVoice(selector: VoiceSelector): ControlTtsVoiceResult =
        ControlTtsVoiceResult.Ok(ttsVoices())
    override fun downloadTtsVoice(voiceId: String): ControlVoiceDownloadStart =
        ControlVoiceDownloadStart.STARTED
    override fun deleteTtsVoice(voiceId: String): ControlVoiceDeleteResult =
        ControlVoiceDeleteResult.Ok(ttsVoices())
}

class ControlAuthProtocolTest {
    private val localHosts = setOf("192.168.7.116")

    private fun req(
        method: String,
        path: String,
        body: String = "",
        authorization: String? = null,
        contentType: String? = if (method == "GET") null else "application/json",
    ): HttpRequest {
        val headers = mutableMapOf("HOST" to "192.168.7.116")
        if (contentType != null) headers["CONTENT-TYPE"] = contentType
        if (authorization != null) headers["AUTHORIZATION"] = authorization
        return HttpRequest(method, path, headers, body)
    }

    private fun route(r: HttpRequest, rt: ControlRuntime) = ControlProtocol.route(r, rt, localHosts)

    @Test fun withoutAPassword_apiIsOpen() {
        val rt = FakeAuthRuntime()
        assertEquals(200, route(req("GET", "/api/state"), rt).status)
    }

    @Test fun withAPassword_readsNeedTheHeader() {
        val rt = FakeAuthRuntime().apply { password = "hunter2" }
        val denied = route(req("GET", "/api/state"), rt)
        assertEquals(401, denied.status)
        assertTrue(denied.body.contains("password"))
        assertEquals(200, route(req("GET", "/api/state", authorization = "Bearer hunter2"), rt).status)
    }

    @Test fun withAPassword_wrongPasswordIs401() {
        val rt = FakeAuthRuntime().apply { password = "hunter2" }
        assertEquals(401, route(req("GET", "/api/state", authorization = "Bearer nope"), rt).status)
    }

    @Test fun withAPassword_writesAreGatedBeforeTheHandlerRuns() {
        val rt = FakeAuthRuntime().apply { password = "hunter2" }
        val res = route(req("POST", "/api/screen", body = """{"on":false}"""), rt)
        assertEquals(401, res.status)
        assertTrue(rt.screenCalls.isEmpty())
        val ok = route(req("POST", "/api/screen", body = """{"on":false}""", authorization = "Bearer hunter2"), rt)
        assertEquals(200, ok.status)
        assertEquals(listOf(false to null), rt.screenCalls.map { it })
    }

    @Test fun withAPassword_authOutranksTheWriteGuards() {
        // An unauthenticated probe learns nothing about body caps or content-type rules.
        val rt = FakeAuthRuntime().apply { password = "hunter2" }
        val oversized = "x".repeat(ControlProtocol.MAX_API_BODY_BYTES + 1)
        assertEquals(401, route(req("POST", "/api/screen", body = oversized), rt).status)
        assertEquals(401, route(req("POST", "/api/screen", body = "{}", contentType = "text/plain"), rt).status)
    }

    @Test fun withAPassword_unknownApiPathsAre401NotEnumerable() {
        val rt = FakeAuthRuntime().apply { password = "hunter2" }
        assertEquals(401, route(req("GET", "/api/does-not-exist"), rt).status)
    }

    @Test fun withAPassword_thePageItselfStaysOpen() {
        // The login overlay lives in the page; the HTML holds no secrets.
        val rt = FakeAuthRuntime().apply { password = "hunter2" }
        assertEquals(200, route(req("GET", "/"), rt).status)
    }
}
