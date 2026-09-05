package dev.rusty.app

import dev.rusty.app.renderer.HttpRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeControlRuntime : ControlRuntime {
    var snap = ControlSnapshot(
        deviceId = "abc", deviceName = "Rusty Speaker", version = "2.3.0",
        screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
        volume = ControlVolume(value = 47, fixed = false),
        playing = ControlPlaying(spotify = true, dlna = false),
        slideshowEnabled = true,
        panel = ControlPanel(
            active = ControlPanelId.SPOTIFY,
            available = ControlPanelId.values().toList(),
            lockscreen = ControlLockscreen(
                theme = ScreensaverThemeId.CLOCK,
                themes = ScreensaverThemeId.values().toList(),
            ),
        ),
        app = ControlApp(foreground = true, canBringForward = true),
    )
    override fun snapshot(): ControlSnapshot = snap

    val panelCalls = mutableListOf<ControlPanelId>()
    var panelResult: ControlPanelResult = ControlPanelResult.Ok(snap)
    override fun setPanel(id: ControlPanelId): ControlPanelResult {
        panelCalls.add(id)
        return panelResult
    }

    val lockscreenCalls = mutableListOf<ScreensaverThemeId>()
    var lockscreenResult: ControlLockscreenResult = ControlLockscreenResult.Ok(snap)
    override fun setLockscreenTheme(theme: ScreensaverThemeId): ControlLockscreenResult {
        lockscreenCalls.add(theme)
        return lockscreenResult
    }

    val foregroundCalls = mutableListOf<Boolean>()
    var foregroundResult: ControlForegroundResult = ControlForegroundResult.Ok(snap)
    override fun setForeground(on: Boolean): ControlForegroundResult {
        foregroundCalls.add(on)
        return foregroundResult
    }

    val screenCalls = mutableListOf<Pair<Boolean, Int?>>()
    var screenResult = snap
    /** Lets a test simulate a runtime that fails with something other than a plain [Exception]
     *  (e.g. a [StackOverflowError] surfacing from deep in some unrelated dependency), to verify
     *  route()'s boundary catch is [Throwable]-wide and not just [Exception]-wide. */
    var screenThrows: Throwable? = null
    override fun setScreen(on: Boolean, brightness: Int?): ControlSnapshot {
        screenThrows?.let { throw it }
        screenCalls.add(on to brightness)
        return screenResult
    }

    val volumeCalls = mutableListOf<Int>()
    var volumeResult: ControlSnapshot? = snap
    override fun setVolume(percent: Int): ControlSnapshot? {
        volumeCalls.add(percent)
        return volumeResult
    }

    var filtersValue = ImmichFilters(emptyList(), emptyList(), emptyList())
    override fun filters(): ImmichFilters = filtersValue

    val setFiltersCalls = mutableListOf<ImmichFilters>()
    override fun setFilters(f: ImmichFilters) {
        setFiltersCalls.add(f)
    }

    var immichResult: ControlImmichResult = ControlImmichResult.Ok(emptyList())
    val immichCalls = mutableListOf<String>()
    override fun immichList(kind: String): ControlImmichResult {
        immichCalls.add(kind)
        return immichResult
    }

    var html = "<html><body>control page</body></html>"
    override fun controlPageHtml(): String = html

    var announceTextResult: ControlAnnounceResult = ControlAnnounceResult.Ok
    val announceTextCalls = mutableListOf<String>()
    override fun announceText(text: String): ControlAnnounceResult {
        announceTextCalls.add(text)
        return announceTextResult
    }

    var ttsVoicesValue = ControlTtsVoices(TtsVoices.SYSTEM_DEFAULT, emptyList())
    override fun ttsVoices(): ControlTtsVoices = ttsVoicesValue

    var setTtsVoiceResult: ControlTtsVoiceResult? = null
    val setTtsVoiceCalls = mutableListOf<VoiceSelector>()
    override fun setTtsVoice(selector: VoiceSelector): ControlTtsVoiceResult {
        setTtsVoiceCalls.add(selector)
        return setTtsVoiceResult ?: ControlTtsVoiceResult.Ok(ttsVoicesValue)
    }

    var downloadVoiceResult = ControlVoiceDownloadStart.STARTED
    val downloadVoiceCalls = mutableListOf<String>()
    override fun downloadTtsVoice(voiceId: String): ControlVoiceDownloadStart {
        downloadVoiceCalls.add(voiceId)
        return downloadVoiceResult
    }

    var deleteVoiceResult: ControlVoiceDeleteResult? = null
    val deleteVoiceCalls = mutableListOf<String>()
    override fun deleteTtsVoice(voiceId: String): ControlVoiceDeleteResult {
        deleteVoiceCalls.add(voiceId)
        return deleteVoiceResult ?: ControlVoiceDeleteResult.Ok(ttsVoicesValue)
    }

    var updateCheckResult = ControlUpdateCheck(
        current = "2.3.0", status = "up_to_date", latest = null,
        install = InstallSnapshot(InstallPhase.IDLE, null, null),
    )
    var updateCheckCalls = 0
    override fun updateCheck(): ControlUpdateCheck {
        updateCheckCalls++
        return updateCheckResult
    }

    var installStartResult = ControlInstallStart.STARTED
    var installStartCalls = 0
    override fun startUpdateInstall(): ControlInstallStart {
        installStartCalls++
        return installStartResult
    }

    /** null = the camera feature is switched off (the router answers 404). */
    var camerasResult: List<ControlCamera>? = listOf(ControlCamera("cam_1", "Front door", "ok"))
    var camerasCalls = 0
    override fun cameras(): List<ControlCamera>? {
        camerasCalls++
        return camerasResult
    }

    var viewCameraResult: ControlCameraViewResult? = null
    val viewCameraCalls = mutableListOf<String>()
    override fun viewCamera(cameraId: String): ControlCameraViewResult {
        viewCameraCalls.add(cameraId)
        return viewCameraResult ?: ControlCameraViewResult.Ok(snap)
    }

    var dismissCameraResult: ControlCameraDismissResult? = null
    var dismissCameraCalls = 0
    override fun dismissCamera(): ControlCameraDismissResult {
        dismissCameraCalls++
        return dismissCameraResult ?: ControlCameraDismissResult.Ok(snap)
    }

    var showCameraGridResult: ControlCameraGridResult? = null
    var showCameraGridCalls = 0
    override fun showCameraGrid(): ControlCameraGridResult {
        showCameraGridCalls++
        return showCameraGridResult ?: ControlCameraGridResult.Ok(snap)
    }

    /** null = no password required (the whole-API gate is off — and, for the snapshot route,
     *  the reason it answers 403). */
    var password: String? = null
    override fun requiredPassword(): String? = password

    var snapshotJpeg: ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0x7F, 0xFF.toByte(), 0xD9.toByte())
    var cameraSnapshotResult: ControlCameraSnapshotResult? = null
    val cameraSnapshotCalls = mutableListOf<String>()
    override fun cameraSnapshot(cameraId: String): ControlCameraSnapshotResult {
        cameraSnapshotCalls.add(cameraId)
        return cameraSnapshotResult ?: ControlCameraSnapshotResult.Ok(snapshotJpeg)
    }
}

class ControlProtocolTest {
    private val localHosts = setOf("192.168.7.116")

    private fun req(
        method: String,
        path: String,
        body: String = "",
        contentType: String? = "application/json",
        host: String? = "192.168.7.116",
        authorization: String? = null,
    ): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        if (host != null) headers["HOST"] = host
        if (contentType != null) headers["CONTENT-TYPE"] = contentType
        if (authorization != null) headers["AUTHORIZATION"] = authorization
        return HttpRequest(method, path, headers, body)
    }

    private fun route(r: HttpRequest, rt: ControlRuntime = FakeControlRuntime()) =
        ControlProtocol.route(r, rt, localHosts)

    // -- GET /api/state -----------------------------------------------------

    @Test fun getState_returnsSnapshotJson() {
        val rt = FakeControlRuntime()
        val res = route(req("GET", "/api/state"), rt)
        assertEquals(200, res.status)
        assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("application/json") })
        assertEquals(rt.snap.toJson(), res.body)
    }

    // -- POST /api/panel ------------------------------------------------------

    @Test fun postPanel_switchesAndReturnsSnapshot() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/panel", body = """{"id":"home_assistant"}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(ControlPanelId.HOME_ASSISTANT), rt.panelCalls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    @Test fun postPanel_lockscreenIsASwitchTargetLikeAnyOther() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/panel", body = """{"id":"lockscreen"}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(ControlPanelId.LOCKSCREEN), rt.panelCalls)
    }

    /** An unknown id must never reach the runtime — that is what would hand the shell an
     *  arbitrary string to switch to. */
    @Test fun postPanel_unknownId_400_andRuntimeUntouched() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/panel", body = """{"id":"screensaver"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.panelCalls.isEmpty())
    }

    @Test fun postPanel_missingId_400() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/panel", body = """{}"""), rt).status)
        assertTrue(rt.panelCalls.isEmpty())
    }

    @Test fun postPanel_nonStringId_400() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/panel", body = """{"id":3}"""), rt).status)
        assertEquals(400, route(req("POST", "/api/panel", body = """{"id":null}"""), rt).status)
        assertTrue(rt.panelCalls.isEmpty())
    }

    @Test fun postPanel_malformedJson_400() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/panel", body = "{"), rt).status)
        assertTrue(rt.panelCalls.isEmpty())
    }

    @Test fun postPanel_noWindow_409() {
        val rt = FakeControlRuntime()
        rt.panelResult = ControlPanelResult.NoWindow
        val res = route(req("POST", "/api/panel", body = """{"id":"dlna"}"""), rt)
        assertEquals(409, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("isn't on screen"))
    }

    @Test fun postPanel_disabledPanel_409() {
        val rt = FakeControlRuntime()
        rt.panelResult = ControlPanelResult.Disabled
        val res = route(req("POST", "/api/panel", body = """{"id":"dlna"}"""), rt)
        assertEquals(409, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("switched off"))
    }

    @Test fun postPanel_requiresJsonContentType() {
        val rt = FakeControlRuntime()
        val res = route(
            req("POST", "/api/panel", body = """{"id":"dlna"}""", contentType = "text/plain"), rt
        )
        assertEquals(415, res.status)
        assertTrue(rt.panelCalls.isEmpty())
    }

    @Test fun getPanel_isNotARoute_404() {
        assertEquals(404, route(req("GET", "/api/panel")).status)
    }

    // -- POST /api/lockscreen -------------------------------------------------

    @Test fun postLockscreen_setsThemeAndReturnsSnapshot() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/lockscreen", body = """{"theme":"oled"}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(ScreensaverThemeId.OLED), rt.lockscreenCalls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    @Test fun postLockscreen_unknownTheme_400_andRuntimeUntouched() {
        val rt = FakeControlRuntime()
        // "photos" is the control page's LABEL for the slideshow lamp, never its wire value —
        // a page that confuses the two must fail loudly rather than set some default theme.
        val res = route(req("POST", "/api/lockscreen", body = """{"theme":"photos"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.lockscreenCalls.isEmpty())
    }

    @Test fun postLockscreen_nonStringOrMissingTheme_400() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/lockscreen", body = """{}"""), rt).status)
        assertEquals(400, route(req("POST", "/api/lockscreen", body = """{"theme":7}"""), rt).status)
        assertTrue(rt.lockscreenCalls.isEmpty())
    }

    @Test fun postLockscreen_unavailableTheme_409() {
        val rt = FakeControlRuntime()
        rt.lockscreenResult = ControlLockscreenResult.ThemeUnavailable
        val res = route(req("POST", "/api/lockscreen", body = """{"theme":"slideshow"}"""), rt)
        assertEquals(409, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("switched off"))
    }

    @Test fun postLockscreen_requiresJsonContentType() {
        val rt = FakeControlRuntime()
        val res = route(
            req("POST", "/api/lockscreen", body = """{"theme":"oled"}""", contentType = "text/plain"), rt
        )
        assertEquals(415, res.status)
        assertTrue(rt.lockscreenCalls.isEmpty())
    }

    // -- POST /api/foreground -------------------------------------------------

    @Test fun postForeground_bringsForwardAndReturnsSnapshot() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/foreground", body = """{"on":true}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(true), rt.foregroundCalls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    @Test fun postForeground_sendsToBackground() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/foreground", body = """{"on":false}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(false), rt.foregroundCalls)
    }

    @Test fun postForeground_missingOrNonBooleanOn_400() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/foreground", body = """{}"""), rt).status)
        // "on":1 is the classic truthy-JSON mistake; it must not be read as true.
        assertEquals(400, route(req("POST", "/api/foreground", body = """{"on":1}"""), rt).status)
        assertEquals(400, route(req("POST", "/api/foreground", body = """{"on":"true"}"""), rt).status)
        assertTrue(rt.foregroundCalls.isEmpty())
    }

    @Test fun postForeground_malformedJson_400() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/foreground", body = "{"), rt).status)
        assertTrue(rt.foregroundCalls.isEmpty())
    }

    /** Both directions are refused without the overlay grant — sending Rusty away when it cannot
     *  be brought back would strand a touchless device. */
    @Test fun postForeground_withoutOverlayGrant_409_inBothDirections() {
        for (on in listOf(true, false)) {
            val rt = FakeControlRuntime()
            rt.foregroundResult = ControlForegroundResult.CannotBringForward
            val res = route(req("POST", "/api/foreground", body = """{"on":$on}"""), rt)
            assertEquals(409, res.status)
            assertTrue(JSONObject(res.body).getString("error").contains("Display over other apps"))
        }
    }

    @Test fun postForeground_requiresJsonContentType() {
        val rt = FakeControlRuntime()
        val res = route(
            req("POST", "/api/foreground", body = """{"on":true}""", contentType = "text/plain"), rt
        )
        assertEquals(415, res.status)
        assertTrue(rt.foregroundCalls.isEmpty())
    }

    @Test fun getForeground_isNotARoute_404() {
        assertEquals(404, route(req("GET", "/api/foreground")).status)
    }

    /** Every new write sits behind the same DNS-rebinding guard as the rest of the API. */
    @Test fun panelWrites_rejectAForeignHost() {
        val rt = FakeControlRuntime()
        assertEquals(
            403,
            route(req("POST", "/api/panel", body = """{"id":"dlna"}""", host = "evil.example.com"), rt).status,
        )
        assertEquals(
            403,
            route(req("POST", "/api/lockscreen", body = """{"theme":"oled"}""", host = "evil.example.com"), rt).status,
        )
        assertEquals(
            403,
            route(req("POST", "/api/foreground", body = """{"on":true}""", host = "evil.example.com"), rt).status,
        )
        assertTrue(rt.panelCalls.isEmpty())
        assertTrue(rt.lockscreenCalls.isEmpty())
        assertTrue(rt.foregroundCalls.isEmpty())
    }

    // -- POST /api/screen -----------------------------------------------------

    @Test fun postScreen_off_noBrightness() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":false}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(false to null), rt.screenCalls)
        assertEquals(rt.screenResult.toJson(), res.body)
    }

    @Test fun postScreen_onWithBrightness() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":true,"brightness":55}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(true to 55), rt.screenCalls)
    }

    @Test fun postScreen_missingOnKey_400() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"brightness":50}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    @Test fun postScreen_contentTypeWithCharsetParam_accepted() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":false}""", contentType = "application/json; charset=utf-8"), rt)
        assertEquals(200, res.status)
        assertEquals(1, rt.screenCalls.size)
    }

    @Test fun postScreen_brightnessOutOfRange_400() {
        val rtLow = FakeControlRuntime()
        val resLow = route(req("POST", "/api/screen", body = """{"on":true,"brightness":0}"""), rtLow)
        assertEquals(400, resLow.status)
        assertTrue(rtLow.screenCalls.isEmpty())

        val rtHigh = FakeControlRuntime()
        val resHigh = route(req("POST", "/api/screen", body = """{"on":true,"brightness":101}"""), rtHigh)
        assertEquals(400, resHigh.status)
        assertTrue(rtHigh.screenCalls.isEmpty())
    }

    @Test fun postScreen_fractionalBrightness_400_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":true,"brightness":30.5}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    @Test fun postScreen_stringBrightness_400_notCalled() {
        // org.json's opt() happily coerces "30" to 30 on request — the handler must reject the
        // JSON *type*, not accept a numeric-looking string, or a client could smuggle any text
        // past a validator that only ever asks for an int.
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":true,"brightness":"30"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    @Test fun postScreen_stringOn_400_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":"false"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    // -- POST /api/volume -----------------------------------------------------

    @Test fun postVolume_valid_200() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/volume", body = """{"value":30}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf(30), rt.volumeCalls)
    }

    @Test fun postVolume_fixed_returns409() {
        val rt = FakeControlRuntime()
        rt.volumeResult = null
        val res = route(req("POST", "/api/volume", body = """{"value":30}"""), rt)
        assertEquals(409, res.status)
        assertEquals("volume is fixed", JSONObject(res.body).getString("error"))
    }

    @Test fun postVolume_fractionalValue_400_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/volume", body = """{"value":30.5}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.volumeCalls.isEmpty())
    }

    @Test fun postVolume_stringValue_400_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/volume", body = """{"value":"30"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.volumeCalls.isEmpty())
    }

    @Test fun postVolume_outOfRange_400_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/volume", body = """{"value":101}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.volumeCalls.isEmpty())

        val rtNegative = FakeControlRuntime()
        val resNegative = route(req("POST", "/api/volume", body = """{"value":-1}"""), rtNegative)
        assertEquals(400, resNegative.status)
        assertTrue(rtNegative.volumeCalls.isEmpty())
    }

    // -- Content-Type / body-size guards --------------------------------------

    @Test fun postWithoutJsonContentType_415() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = """{"on":false}""", contentType = "text/plain"), rt)
        assertEquals(415, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    @Test fun bodyOverCap_413() {
        val rt = FakeControlRuntime()
        val bigBody = """{"on":false,"pad":"${"x".repeat(ControlProtocol.MAX_API_BODY_BYTES)}"}"""
        val res = route(req("POST", "/api/screen", body = bigBody), rt)
        assertEquals(413, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    @Test fun oversizedBodyWithWrongContentType_413_notFifteen() {
        // Pins the ORDER of the two write guards, which is otherwise invisible: swapping them
        // would keep every other test green while making a 16 MB body from a cross-origin form
        // be reported as "wrong media type" only AFTER it had already been read and measured.
        // The size cap must be the first thing that answers.
        val rt = FakeControlRuntime()
        val bigBody = """{"on":false,"pad":"${"x".repeat(ControlProtocol.MAX_API_BODY_BYTES)}"}"""
        val res = route(req("POST", "/api/screen", body = bigBody, contentType = "text/plain"), rt)
        assertEquals(413, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    // -- Slideshow filters -----------------------------------------------------

    @Test fun getFilters_returnsJson() {
        val rt = FakeControlRuntime()
        rt.filtersValue = ImmichFilters(listOf("a1"), listOf("p1"), listOf("t1"))
        val res = route(req("GET", "/api/slideshow/filters"), rt)
        assertEquals(200, res.status)
        val o = JSONObject(res.body)
        assertEquals("a1", o.getJSONArray("albumIds").getString(0))
        assertEquals("p1", o.getJSONArray("personIds").getString(0))
        assertEquals("t1", o.getJSONArray("tagIds").getString(0))
    }

    @Test fun putFilters_valid_callsSetFilters() {
        val rt = FakeControlRuntime()
        val uuid = "11111111-2222-3333-4444-555555555555"
        val res = route(req("PUT", "/api/slideshow/filters", body = """{"albumIds":["$uuid"]}"""), rt)
        assertEquals(200, res.status)
        assertEquals(1, rt.setFiltersCalls.size)
        assertEquals(listOf(uuid), rt.setFiltersCalls[0].albumIds)
    }

    @Test fun putFilters_invalidUuid_400_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("PUT", "/api/slideshow/filters", body = """{"albumIds":["not-a-uuid"]}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.setFiltersCalls.isEmpty())
    }

    // -- Immich lists -----------------------------------------------------

    @Test fun immichAlbums_ok_mapsLabelToName() {
        val rt = FakeControlRuntime()
        rt.immichResult = ControlImmichResult.Ok(listOf(ImmichPickerItem("id1", "Vacation")))
        val res = route(req("GET", "/api/immich/albums"), rt)
        assertEquals(200, res.status)
        assertEquals(listOf("albums"), rt.immichCalls)
        val arr = org.json.JSONArray(res.body)
        assertEquals("id1", arr.getJSONObject(0).getString("id"))
        assertEquals("Vacation", arr.getJSONObject(0).getString("name"))
    }

    @Test fun immichAlbums_notConfigured_404() {
        val rt = FakeControlRuntime()
        rt.immichResult = ControlImmichResult.NotConfigured
        val res = route(req("GET", "/api/immich/albums"), rt)
        assertEquals(404, res.status)
        assertEquals("immich not configured", JSONObject(res.body).getString("error"))
    }

    @Test fun immichAlbums_unauthorized_502() {
        val rt = FakeControlRuntime()
        rt.immichResult = ControlImmichResult.Unauthorized
        val res = route(req("GET", "/api/immich/albums"), rt)
        assertEquals(502, res.status)
        assertEquals("immich unauthorized", JSONObject(res.body).getString("error"))
    }

    @Test fun immichAlbums_unreachable_502() {
        val rt = FakeControlRuntime()
        rt.immichResult = ControlImmichResult.Unreachable
        val res = route(req("GET", "/api/immich/albums"), rt)
        assertEquals(502, res.status)
        // Exact, not just non-blank: "unreachable" and "unauthorized" are the two answers the
        // control page distinguishes for the user ("check the server" vs "check the API key"),
        // and both are 502 — so the body is the ONLY thing that tells them apart.
        assertEquals("immich unreachable", JSONObject(res.body).getString("error"))
    }

    @Test fun immichUnknownKind_404_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("GET", "/api/immich/zzz"), rt)
        assertEquals(404, res.status)
        assertTrue(rt.immichCalls.isEmpty())
    }

    @Test fun immichEmptyKind_404_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("GET", "/api/immich/"), rt)
        assertEquals(404, res.status)
        assertTrue(rt.immichCalls.isEmpty())
    }

    @Test fun immichPathTraversalKind_404_notCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("GET", "/api/immich/albums/../../admin/users"), rt)
        assertEquals(404, res.status)
        assertTrue(rt.immichCalls.isEmpty())
    }

    // -- Static page / misc -----------------------------------------------------

    @Test fun getRoot_returnsControlPageHtml() {
        val rt = FakeControlRuntime()
        val res = route(req("GET", "/"), rt)
        assertEquals(200, res.status)
        assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("text/html") })
        assertEquals(rt.html, res.body)
    }

    @Test fun unknownPath_404() {
        val res = route(req("GET", "/nope"))
        assertEquals(404, res.status)
    }

    @Test fun malformedJsonBody_400() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/screen", body = "not json"), rt)
        assertEquals(400, res.status)
        assertTrue(rt.screenCalls.isEmpty())
    }

    // -- Internal-error boundary: handlers never throw into the server loop --------------------

    @Test fun deeplyNestedJsonBody_underCap_doesNotCrash() {
        // A pathologically nested body is a classic recursive-descent-parser DoS vector. Drive
        // the deepest nesting that still fits under MAX_API_BODY_BYTES (so this exercises the
        // JSON layer, not the body-size guard) and confirm route() never lets anything escape:
        // this org.json version (20231013) itself catches an internal StackOverflowError while
        // descending and re-throws it as a plain JSONException, which our 400 path already
        // handles — but how deep that takes to trigger depends on the JVM's thread stack size,
        // so both outcomes (guard tripped -> 400, or this stack tolerates it -> 200) are accepted
        // here. The dedicated Throwable test below is what actually pins down route()'s boundary
        // catch, independent of this library-internal, stack-size-sensitive behavior.
        val rt = FakeControlRuntime()
        val depth = ControlProtocol.MAX_API_BODY_BYTES / 2 - 16
        val body = """{"on":false,"x":${"[".repeat(depth)}${"]".repeat(depth)}}"""
        assertTrue(body.toByteArray(Charsets.UTF_8).size <= ControlProtocol.MAX_API_BODY_BYTES)
        val res = route(req("POST", "/api/screen", body = body), rt)
        assertTrue(res.status == 200 || res.status == 400)
        if (res.status == 400) assertTrue(rt.screenCalls.isEmpty()) else assertEquals(1, rt.screenCalls.size)
    }

    @Test fun handlerThrowsNonException_caughtAsThrowable_500_andReportedToSink() {
        val rt = FakeControlRuntime()
        rt.screenThrows = StackOverflowError()
        var reported: Throwable? = null
        ControlProtocol.onInternalError = { t, _ -> reported = t }
        try {
            val res = route(req("POST", "/api/screen", body = """{"on":false}"""), rt)
            assertEquals(500, res.status)
            assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("application/json") })
            assertTrue(reported is StackOverflowError)
        } finally {
            ControlProtocol.onInternalError = { _, _ -> }
        }
    }

    @Test fun throwingSink_doesNotEscapeRoute_stillReturns500() {
        // A sink assigned by the owning service could itself misbehave (OOM in a logger, a
        // logging call that throws...). That must not turn a handled 500 into an uncaught
        // Throwable escaping route() — the exact failure mode this whole boundary catch exists
        // to close in the first place.
        val rt = FakeControlRuntime()
        rt.screenThrows = IllegalStateException("boom")
        ControlProtocol.onInternalError = { _, _ -> throw RuntimeException("sink itself is broken") }
        try {
            val res = route(req("POST", "/api/screen", body = """{"on":false}"""), rt)
            assertEquals(500, res.status)
            assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("application/json") })
        } finally {
            ControlProtocol.onInternalError = { _, _ -> }
        }
    }

    // -- Host guard -----------------------------------------------------

    @Test fun hostGuard_matchingHostWithPort_allowed() {
        val res = route(req("GET", "/api/state", host = "192.168.7.116:8765"))
        assertEquals(200, res.status)
    }

    @Test fun hostGuard_foreignHost_403() {
        val res = route(req("GET", "/api/state", host = "evil.example.com"))
        assertEquals(403, res.status)
    }

    @Test fun hostGuard_missingHost_403() {
        val res = route(req("GET", "/api/state", host = null))
        assertEquals(403, res.status)
    }

    @Test fun hostGuard_runsBeforeRouting_unknownPathFromForeignHostIs403() {
        val res = route(req("GET", "/totally/unknown", host = "evil.example.com"))
        assertEquals(403, res.status)
    }

    // -- No CORS, ever -----------------------------------------------------
    //
    // The entire cross-origin WRITE defense is the ABSENCE of an `Access-Control-Allow-*`
    // header: with none emitted, a browser refuses to hand another origin's script the response
    // (and refuses the preflight a JSON write requires at all). That is a property of code that
    // does not exist, so nothing else in this suite can notice it being broken — someone adding
    // `Access-Control-Allow-Origin: *` to make a local debugging page work would keep every
    // other test green while opening every write route to any site the user has open in a tab.
    // These two tests are the tripwire, and they sweep EVERY status the router can produce
    // (200/400/403/404/409/413/415/500), because a header could just as easily be added on one
    // path (say the error helper) as on all of them.

    @Test fun noCorsHeaders_onEveryResponseShape() {
        val big = """{"on":false,"pad":"${"x".repeat(ControlProtocol.MAX_API_BODY_BYTES)}"}"""
        val uuid = "11111111-2222-3333-4444-555555555555"
        val fixedVolume = FakeControlRuntime().apply { volumeResult = null }
        val throwing = FakeControlRuntime().apply { screenThrows = IllegalStateException("boom") }

        val responses = listOf(
            "state 200" to route(req("GET", "/api/state")),
            "page 200 (html)" to route(req("GET", "/")),
            "filters 200 (write)" to route(req("PUT", "/api/slideshow/filters", body = """{"albumIds":["$uuid"]}""")),
            "malformed 400" to route(req("POST", "/api/screen", body = "not json")),
            "rebinding 403" to route(req("GET", "/api/state", host = "evil.example.com")),
            "unknown 404" to route(req("GET", "/nope")),
            "fixed volume 409" to route(req("POST", "/api/volume", body = """{"value":30}"""), fixedVolume),
            "oversized 413" to route(req("POST", "/api/screen", body = big)),
            "wrong type 415" to route(req("POST", "/api/screen", body = """{"on":false}""", contentType = "text/plain")),
            "runtime blew up 500" to route(req("POST", "/api/screen", body = """{"on":false}"""), throwing),
        )

        for ((label, res) in responses) {
            val offending = res.headers.filter { it.first.startsWith("Access-Control-", ignoreCase = true) }
            assertTrue("$label (HTTP ${res.status}) emitted CORS headers: $offending", offending.isEmpty())
        }
        // Sanity: the list above really did exercise the statuses it claims to.
        assertEquals(
            listOf(200, 200, 200, 400, 403, 404, 409, 413, 415, 500),
            responses.map { it.second.status },
        )
    }

    @Test fun noCorsHeaders_survivesRendering() {
        // Belt and braces on the assertion above: headers are also injected at render() time
        // (Content-Length, Connection), so assert the CORS absence on the WIRE bytes too.
        val rendered = route(req("GET", "/api/state")).render()
        assertTrue(!rendered.contains("Access-Control-", ignoreCase = true))
    }

    // -- GET /api/update --------------------------------------------------------

    @Test fun getUpdate_returnsCheckJson() {
        val rt = FakeControlRuntime()
        rt.updateCheckResult = ControlUpdateCheck(
            current = "2.3.0", status = "update_available",
            latest = ControlUpdateLatest("2.4.0", "• notes", "https://x/rel", hasApk = true),
            install = InstallSnapshot(InstallPhase.DOWNLOADING, 7, null),
        )
        val res = route(req("GET", "/api/update"), rt)
        assertEquals(200, res.status)
        assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("application/json") })
        assertEquals(rt.updateCheckResult.toJson(), res.body)
        assertEquals(1, rt.updateCheckCalls)
    }

    @Test fun getUpdate_disallowedHost_403_runtimeNotCalled() {
        val rt = FakeControlRuntime()
        val res = route(req("GET", "/api/update", host = "evil.example.com"), rt)
        assertEquals(403, res.status)
        assertEquals(0, rt.updateCheckCalls)
    }

    // -- POST /api/update/install ------------------------------------------------

    @Test fun postInstall_started_202() {
        val rt = FakeControlRuntime()
        rt.installStartResult = ControlInstallStart.STARTED
        val res = route(req("POST", "/api/update/install", body = "{}"), rt)
        assertEquals(202, res.status)
        assertEquals("started", JSONObject(res.body).getString("status"))
        assertEquals(1, rt.installStartCalls)
    }

    @Test fun postInstall_noUpdate_409() {
        val rt = FakeControlRuntime()
        rt.installStartResult = ControlInstallStart.NO_UPDATE
        assertEquals(409, route(req("POST", "/api/update/install", body = "{}"), rt).status)
    }

    @Test fun postInstall_busy_409() {
        val rt = FakeControlRuntime()
        rt.installStartResult = ControlInstallStart.BUSY
        assertEquals(409, route(req("POST", "/api/update/install", body = "{}"), rt).status)
    }

    @Test fun postInstall_noApk_503() {
        val rt = FakeControlRuntime()
        rt.installStartResult = ControlInstallStart.NO_APK
        assertEquals(503, route(req("POST", "/api/update/install", body = "{}"), rt).status)
    }

    @Test fun postInstall_withoutJsonContentType_415_notStarted() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/update/install", body = "{}", contentType = "text/plain"), rt)
        assertEquals(415, res.status)
        assertEquals(0, rt.installStartCalls)
    }

    @Test fun getOnInstallPath_404() {
        val rt = FakeControlRuntime()
        assertEquals(404, route(req("GET", "/api/update/install"), rt).status)
        assertEquals(0, rt.installStartCalls)
    }

    // -- OPTIONS: no preflight is ever answered ---------------------------------
    //
    // The corollary of "no CORS headers": an OPTIONS preflight must NOT be special-cased into a
    // 200. A cross-origin `application/json` POST is preflighted, and a 404 to that preflight is
    // what makes the browser abandon the write.
    @Test fun optionsPreflight_isNotAnswered() {
        val rt = FakeControlRuntime()
        val res = route(req("OPTIONS", "/api/screen"), rt)
        assertEquals(404, res.status)
        assertTrue(rt.screenCalls.isEmpty())
        assertTrue(res.headers.none { it.first.startsWith("Access-Control-", ignoreCase = true) })
    }

    // -- GET /api/cameras -------------------------------------------------------

    @Test fun getCameras_returnsIdNameState() {
        val rt = FakeControlRuntime()
        rt.camerasResult = listOf(
            ControlCamera("cam_1", "Front door", "ok"),
            ControlCamera("cam_2", "Garden", "unreachable"),
        )
        val res = route(req("GET", "/api/cameras"), rt)
        assertEquals(200, res.status)
        assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("application/json") })
        val arr = org.json.JSONArray(res.body)
        assertEquals(2, arr.length())
        assertEquals("cam_1", arr.getJSONObject(0).getString("id"))
        assertEquals("Front door", arr.getJSONObject(0).getString("name"))
        assertEquals("ok", arr.getJSONObject(0).getString("state"))
        assertEquals("unreachable", arr.getJSONObject(1).getString("state"))
    }

    @Test fun getCameras_emptyList_isAnEmptyArray_not404() {
        // "no cameras configured" is not "no camera feature": the page must be able to tell them
        // apart, or an empty list would look like an old build with no camera support.
        val rt = FakeControlRuntime()
        rt.camerasResult = emptyList()
        val res = route(req("GET", "/api/cameras"), rt)
        assertEquals(200, res.status)
        assertEquals(0, org.json.JSONArray(res.body).length())
    }

    @Test fun getCameras_featureDisabled_404() {
        val rt = FakeControlRuntime()
        rt.camerasResult = null
        val res = route(req("GET", "/api/cameras"), rt)
        assertEquals(404, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("camera"))
    }

    @Test fun postCameras_isNotARoute_404() {
        val rt = FakeControlRuntime()
        assertEquals(404, route(req("POST", "/api/cameras", body = "{}"), rt).status)
        assertEquals(0, rt.camerasCalls)
    }

    // -- POST /api/camera/view --------------------------------------------------

    @Test fun postCameraView_summons_200() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/camera/view", body = """{"id":"cam_1"}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf("cam_1"), rt.viewCameraCalls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    @Test fun postCameraView_unknownCamera_400() {
        val rt = FakeControlRuntime()
        rt.viewCameraResult = ControlCameraViewResult.UnknownCamera
        val res = route(req("POST", "/api/camera/view", body = """{"id":"cam_nope"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("cam_nope"))
    }

    @Test fun postCameraView_featureDisabled_404() {
        val rt = FakeControlRuntime()
        rt.viewCameraResult = ControlCameraViewResult.FeatureDisabled
        assertEquals(404, route(req("POST", "/api/camera/view", body = """{"id":"cam_1"}"""), rt).status)
    }

    /** Backgrounded with no "Display over other apps" grant: the summon cannot put Rusty in front,
     *  and the same machine-readable code the page already handles for /api/foreground says so. */
    @Test fun postCameraView_withoutOverlayGrant_409_overlayPermissionRequired() {
        val rt = FakeControlRuntime()
        rt.viewCameraResult = ControlCameraViewResult.OverlayPermissionRequired
        val res = route(req("POST", "/api/camera/view", body = """{"id":"cam_1"}"""), rt)
        assertEquals(409, res.status)
        assertEquals("overlay_permission_required", JSONObject(res.body).getString("error"))
    }

    @Test fun postCameraView_missingOrNonStringId_400_runtimeUntouched() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/camera/view", body = """{}"""), rt).status)
        assertEquals(400, route(req("POST", "/api/camera/view", body = """{"id":3}"""), rt).status)
        assertEquals(400, route(req("POST", "/api/camera/view", body = """{"id":null}"""), rt).status)
        assertEquals(400, route(req("POST", "/api/camera/view", body = """{"id":"  "}"""), rt).status)
        assertTrue(rt.viewCameraCalls.isEmpty())
    }

    @Test fun postCameraView_malformedJson_400_runtimeUntouched() {
        val rt = FakeControlRuntime()
        assertEquals(400, route(req("POST", "/api/camera/view", body = "{"), rt).status)
        assertTrue(rt.viewCameraCalls.isEmpty())
    }

    @Test fun postCameraView_requiresJsonContentType() {
        val rt = FakeControlRuntime()
        val res = route(
            req("POST", "/api/camera/view", body = """{"id":"cam_1"}""", contentType = "text/plain"), rt
        )
        assertEquals(415, res.status)
        assertTrue(rt.viewCameraCalls.isEmpty())
    }

    @Test fun postCameraView_foreignHost_403_runtimeUntouched() {
        val rt = FakeControlRuntime()
        val res = route(
            req("POST", "/api/camera/view", body = """{"id":"cam_1"}""", host = "evil.example.com"), rt
        )
        assertEquals(403, res.status)
        assertTrue(rt.viewCameraCalls.isEmpty())
    }

    // -- POST /api/camera/dismiss -----------------------------------------------

    @Test fun postCameraDismiss_restores_200() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/camera/dismiss", body = "{}"), rt)
        assertEquals(200, res.status)
        assertEquals(1, rt.dismissCameraCalls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    @Test fun postCameraDismiss_nothingSummoned_409() {
        val rt = FakeControlRuntime()
        rt.dismissCameraResult = ControlCameraDismissResult.NotSummoned
        val res = route(req("POST", "/api/camera/dismiss", body = "{}"), rt)
        assertEquals(409, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("summon"))
    }

    /** No attached window means every restore step is a silent no-op, so the dismiss must refuse
     *  (and keep the capture) rather than report a restore that never happened — the same answer,
     *  and the same wording, POST /api/panel gives. */
    @Test fun postCameraDismiss_noWindow_409() {
        val rt = FakeControlRuntime()
        rt.dismissCameraResult = ControlCameraDismissResult.NoWindow
        val res = route(req("POST", "/api/camera/dismiss", body = "{}"), rt)
        assertEquals(409, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("isn't on screen"))
    }

    @Test fun postCameraDismiss_featureDisabled_404() {
        val rt = FakeControlRuntime()
        rt.dismissCameraResult = ControlCameraDismissResult.FeatureDisabled
        assertEquals(404, route(req("POST", "/api/camera/dismiss", body = "{}"), rt).status)
    }

    @Test fun postCameraDismiss_requiresJsonContentType() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/camera/dismiss", body = "{}", contentType = "text/plain"), rt)
        assertEquals(415, res.status)
        assertEquals(0, rt.dismissCameraCalls)
    }

    @Test fun getCameraDismiss_isNotARoute_404() {
        val rt = FakeControlRuntime()
        assertEquals(404, route(req("GET", "/api/camera/dismiss"), rt).status)
        assertEquals(0, rt.dismissCameraCalls)
    }

    // -- POST /api/camera/grid --------------------------------------------------

    @Test fun postCameraGrid_showsTheGrid_200() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/camera/grid", body = "{}"), rt)
        assertEquals(200, res.status)
        assertEquals(1, rt.showCameraGridCalls)
        assertEquals(rt.snap.toJson(), res.body)
    }

    /**
     * The difference from dismiss that this route exists for: asking for the grid is meaningful
     * with no summon in force (the Camera panel was selected directly), so there is no
     * `NotSummoned` refusal to give — it simply succeeds.
     */
    @Test fun postCameraGrid_withNoSummon_stillSucceeds_200() {
        val rt = FakeControlRuntime()
        rt.showCameraGridResult = ControlCameraGridResult.Ok(rt.snap)
        assertEquals(200, route(req("POST", "/api/camera/grid", body = "{}"), rt).status)
    }

    @Test fun postCameraGrid_noWindow_409() {
        val rt = FakeControlRuntime()
        rt.showCameraGridResult = ControlCameraGridResult.NoWindow
        val res = route(req("POST", "/api/camera/grid", body = "{}"), rt)
        assertEquals(409, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("isn't on screen"))
    }

    @Test fun postCameraGrid_featureDisabled_404() {
        val rt = FakeControlRuntime()
        rt.showCameraGridResult = ControlCameraGridResult.FeatureDisabled
        assertEquals(404, route(req("POST", "/api/camera/grid", body = "{}"), rt).status)
    }

    @Test fun postCameraGrid_requiresJsonContentType() {
        val rt = FakeControlRuntime()
        val res = route(req("POST", "/api/camera/grid", body = "{}", contentType = "text/plain"), rt)
        assertEquals(415, res.status)
        assertEquals(0, rt.showCameraGridCalls)
    }

    /** `/api/camera/grid` must not be mistaken for a camera id by the snapshot route, which lives
     *  under the same prefix. */
    @Test fun getCameraGrid_isNotARoute_404() {
        val rt = FakeControlRuntime()
        assertEquals(404, route(req("GET", "/api/camera/grid"), rt).status)
        assertEquals(0, rt.showCameraGridCalls)
    }

    // -- GET /api/camera/{id}/snapshot ------------------------------------------
    //
    // The one route that serves camera IMAGERY, so it is opt-in only: without the API password
    // enabled it answers 403 no matter what else is true. Everything below therefore turns the
    // gate on and presents the Bearer token.

    private fun authed(rt: FakeControlRuntime, path: String) =
        route(req("GET", path, contentType = null, authorization = "Bearer s3cret"), rt)

    @Test fun getCameraSnapshot_withAuthEnabled_200_imageJpegBytes() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        val res = authed(rt, "/api/camera/cam_1/snapshot")
        assertEquals(200, res.status)
        assertEquals(listOf("cam_1"), rt.cameraSnapshotCalls)
        assertTrue(res.headers.any { it.first == "Content-Type" && it.second == "image/jpeg" })
        // The bytes must survive verbatim — a JPEG routed through a UTF-8 String body would be
        // mangled beyond recognition, so this asserts on the binary body the server writes.
        assertTrue(rt.snapshotJpeg.contentEquals(res.binaryBody))
    }

    /** The whole point of the route's own gate: with the API password OFF, anyone on the LAN could
     *  otherwise pull frames from the house's cameras. */
    @Test fun getCameraSnapshot_withAuthDisabled_403_runtimeUntouched() {
        val rt = FakeControlRuntime()
        rt.password = null
        val res = route(req("GET", "/api/camera/cam_1/snapshot", contentType = null), rt)
        assertEquals(403, res.status)
        assertTrue(res.headers.any { it.first == "Content-Type" && it.second.contains("application/json") })
        assertTrue(rt.cameraSnapshotCalls.isEmpty())
        assertNull(res.binaryBody)
    }

    @Test fun getCameraSnapshot_withAuthEnabledButNoToken_401() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        val res = route(req("GET", "/api/camera/cam_1/snapshot", contentType = null), rt)
        assertEquals(401, res.status)
        assertTrue(rt.cameraSnapshotCalls.isEmpty())
    }

    @Test fun getCameraSnapshot_wrongToken_401() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        val res = route(
            req("GET", "/api/camera/cam_1/snapshot", contentType = null, authorization = "Bearer nope"), rt
        )
        assertEquals(401, res.status)
        assertTrue(rt.cameraSnapshotCalls.isEmpty())
    }

    @Test fun getCameraSnapshot_unknownCamera_400() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        rt.cameraSnapshotResult = ControlCameraSnapshotResult.UnknownCamera
        val res = authed(rt, "/api/camera/cam_nope/snapshot")
        assertEquals(400, res.status)
        assertEquals(listOf("cam_nope"), rt.cameraSnapshotCalls)
    }

    @Test fun getCameraSnapshot_noFrameYet_404() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        rt.cameraSnapshotResult = ControlCameraSnapshotResult.NoFrame
        val res = authed(rt, "/api/camera/cam_1/snapshot")
        assertEquals(404, res.status)
        assertTrue(JSONObject(res.body).getString("error").contains("frame"))
    }

    @Test fun getCameraSnapshot_featureDisabled_404() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        rt.cameraSnapshotResult = ControlCameraSnapshotResult.FeatureDisabled
        assertEquals(404, authed(rt, "/api/camera/cam_1/snapshot").status)
    }

    @Test fun getCameraSnapshot_emptyOrTraversedId_404_runtimeUntouched() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        assertEquals(404, authed(rt, "/api/camera//snapshot").status)
        assertEquals(404, authed(rt, "/api/camera/../secrets/snapshot").status)
        assertEquals(404, authed(rt, "/api/camera/cam_1/snapshot/extra").status)
        assertEquals(404, authed(rt, "/api/camera/cam_1").status)
        assertTrue(rt.cameraSnapshotCalls.isEmpty())
    }

    @Test fun getCameraSnapshot_foreignHost_403_runtimeUntouched() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        val res = route(
            req("GET", "/api/camera/cam_1/snapshot", contentType = null, host = "evil.example.com",
                authorization = "Bearer s3cret"),
            rt,
        )
        assertEquals(403, res.status)
        assertTrue(rt.cameraSnapshotCalls.isEmpty())
    }

    @Test fun getCameraSnapshot_rendersTheJpegVerbatimOnTheWire() {
        // render() is what the server writes to the socket. A binary body must land byte-for-byte
        // with a Content-Length measured in BYTES, not in UTF-8-mangled characters.
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        rt.snapshotJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x80.toByte(), 0x00, 0xFF.toByte(), 0xD9.toByte())
        val wire = authed(rt, "/api/camera/cam_1/snapshot").renderBytes()
        val headerEnd = String(wire, Charsets.ISO_8859_1).indexOf("\r\n\r\n") + 4
        val head = String(wire, 0, headerEnd, Charsets.ISO_8859_1)
        assertTrue(head.contains("Content-Type: image/jpeg"))
        assertTrue(head.contains("Content-Length: ${rt.snapshotJpeg.size}"))
        assertTrue(rt.snapshotJpeg.contentEquals(wire.copyOfRange(headerEnd, wire.size)))
    }

    @Test fun cameraRoutes_emitNoCorsHeaders() {
        val rt = FakeControlRuntime().apply { password = "s3cret" }
        val responses = listOf(
            route(req("GET", "/api/cameras", contentType = null, authorization = "Bearer s3cret"), rt),
            route(req("POST", "/api/camera/view", body = """{"id":"cam_1"}""", authorization = "Bearer s3cret"), rt),
            route(req("POST", "/api/camera/dismiss", body = "{}", authorization = "Bearer s3cret"), rt),
            route(req("POST", "/api/camera/grid", body = "{}", authorization = "Bearer s3cret"), rt),
            authed(rt, "/api/camera/cam_1/snapshot"),
        )
        for (res in responses) {
            assertTrue(res.headers.none { it.first.startsWith("Access-Control-", ignoreCase = true) })
        }
        assertEquals(listOf(200, 200, 200, 200, 200), responses.map { it.status })
    }
}
