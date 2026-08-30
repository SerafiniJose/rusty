package dev.rusty.app

import dev.rusty.app.renderer.HttpRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `POST /api/announce/text` through [ControlProtocol.route]. Uses its own file-private fake for
 * the announce seams so these tests stay independent of ControlProtocolTest's fake growing.
 */
private class FakeAnnounceRuntime : ControlRuntime {
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

    override fun snapshot(): ControlSnapshot = snap
    override fun setScreen(on: Boolean, brightness: Int?): ControlSnapshot = snap
    override fun setVolume(percent: Int): ControlSnapshot? = snap
    override fun setPanel(id: ControlPanelId): ControlPanelResult = ControlPanelResult.Ok(snap)
    override fun setLockscreenTheme(theme: ScreensaverThemeId): ControlLockscreenResult =
        ControlLockscreenResult.Ok(snap)
    override fun setForeground(on: Boolean): ControlForegroundResult = ControlForegroundResult.Ok(snap)
    override fun filters(): ImmichFilters = ImmichFilters(emptyList(), emptyList(), emptyList())
    override fun setFilters(f: ImmichFilters) {}
    override fun immichList(kind: String): ControlImmichResult = ControlImmichResult.Ok(emptyList())
    override fun controlPageHtml(): String = "<html></html>"
    override fun updateCheck(): ControlUpdateCheck = ControlUpdateCheck(
        current = "2.4.0", status = "up_to_date", latest = null,
        install = InstallSnapshot(InstallPhase.IDLE, null, null),
    )
    override fun startUpdateInstall(): ControlInstallStart = ControlInstallStart.NO_UPDATE

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
}

class ControlAnnounceProtocolTest {
    private val localHosts = setOf("192.168.7.116")

    private fun req(
        method: String,
        path: String,
        body: String = "",
        contentType: String? = "application/json",
    ): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        headers["HOST"] = "192.168.7.116"
        if (contentType != null) headers["CONTENT-TYPE"] = contentType
        return HttpRequest(method, path, headers, body)
    }

    private fun route(r: HttpRequest, rt: ControlRuntime = FakeAnnounceRuntime()) =
        ControlProtocol.route(r, rt, localHosts)

    // -- POST /api/announce/text --------------------------------------------

    @Test fun announceText_accepted_is202AndTrimmed() {
        val rt = FakeAnnounceRuntime()
        val res = route(req("POST", "/api/announce/text", body = """{"text":"  Dinner is ready  "}"""), rt)
        assertEquals(202, res.status)
        assertTrue(res.body.contains("\"playing\""))
        assertEquals(listOf("Dinner is ready"), rt.announceTextCalls)
    }

    @Test fun announceText_malformedJson_is400() {
        val res = route(req("POST", "/api/announce/text", body = "{not json"))
        assertEquals(400, res.status)
    }

    @Test fun announceText_missingText_is400() {
        val rt = FakeAnnounceRuntime()
        val res = route(req("POST", "/api/announce/text", body = """{"other":1}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.announceTextCalls.isEmpty())
    }

    @Test fun announceText_nonStringText_is400() {
        val res = route(req("POST", "/api/announce/text", body = """{"text":42}"""))
        assertEquals(400, res.status)
    }

    @Test fun announceText_blankText_is400() {
        val res = route(req("POST", "/api/announce/text", body = """{"text":"   "}"""))
        assertEquals(400, res.status)
    }

    @Test fun announceText_overMaxChars_is400() {
        val long = "a".repeat(ControlAnnounce.MAX_TEXT_CHARS + 1)
        val rt = FakeAnnounceRuntime()
        val res = route(req("POST", "/api/announce/text", body = """{"text":"$long"}"""), rt)
        assertEquals(400, res.status)
        assertTrue(rt.announceTextCalls.isEmpty())
    }

    @Test fun announceText_atMaxChars_isAccepted() {
        val text = "a".repeat(ControlAnnounce.MAX_TEXT_CHARS)
        val res = route(req("POST", "/api/announce/text", body = """{"text":"$text"}"""))
        assertEquals(202, res.status)
    }

    @Test fun announceText_rendererDown_is409() {
        val rt = FakeAnnounceRuntime().apply { announceTextResult = ControlAnnounceResult.RendererUnavailable }
        assertEquals(409, route(req("POST", "/api/announce/text", body = """{"text":"hi"}"""), rt).status)
    }

    @Test fun announceText_ttsUnavailable_is503() {
        val rt = FakeAnnounceRuntime().apply { announceTextResult = ControlAnnounceResult.TtsUnavailable }
        assertEquals(503, route(req("POST", "/api/announce/text", body = """{"text":"hi"}"""), rt).status)
    }

    @Test fun announceText_wrongContentType_is415() {
        val res = route(req("POST", "/api/announce/text", body = """{"text":"hi"}""", contentType = "text/plain"))
        assertEquals(415, res.status)
    }

    @Test fun announceText_getMethod_is404() {
        assertEquals(404, route(req("GET", "/api/announce/text")).status)
    }

    @Test fun announceText_overSmallBodyCap_is413() {
        // The text route keeps the standard 16 KiB budget even though the parser's own cap is a
        // flat 64 KiB for every route, so a body this size reaches route() before being rejected.
        val big = """{"text":"${"a".repeat(ControlProtocol.MAX_API_BODY_BYTES)}"}"""
        assertEquals(413, route(req("POST", "/api/announce/text", body = big)).status)
    }

    // -- POST /api/announce/voice (removed feature) -------------------------

    @Test fun announceVoice_routeRemoved_is404() {
        // Voice messages were removed (browser mic capture needs a secure context the
        // plain-HTTP LAN page can't provide); the route must be plain 404, not 400/413.
        val res = route(req("POST", "/api/announce/voice", body = """{"audio":"AAAA","mime":"audio/webm"}"""))
        assertEquals(404, res.status)
    }

    // -- GET /api/tts/voices & POST /api/tts/voice --------------------------

    @Test fun getTtsVoices_returnsSnapshotJson() {
        val rt = FakeAnnounceRuntime()
        rt.ttsVoicesValue = ControlTtsVoices(
            "system:default",
            listOf(VoiceInfo("system:default", "System default", "", "normal", true, false)),
        )
        val res = route(req("GET", "/api/tts/voices"), rt)
        assertEquals(200, res.status)
        assertEquals(rt.ttsVoicesValue.toJson(), res.body)
    }

    @Test fun setTtsVoice_validSelector_persistsAndReturnsList() {
        val rt = FakeAnnounceRuntime()
        val res = route(req("POST", "/api/tts/voice", body = """{"id":"system:e.pkg/voice-1"}"""), rt)
        assertEquals(200, res.status)
        assertEquals(listOf<VoiceSelector>(VoiceSelector.System("e.pkg", "voice-1")), rt.setTtsVoiceCalls)
    }

    @Test fun setTtsVoice_malformedSelector_is400BeforeRuntime() {
        val rt = FakeAnnounceRuntime()
        assertEquals(400, route(req("POST", "/api/tts/voice", body = """{"id":"nonsense"}"""), rt).status)
        assertTrue(rt.setTtsVoiceCalls.isEmpty())
    }

    @Test fun setTtsVoice_missingId_is400() {
        assertEquals(400, route(req("POST", "/api/tts/voice", body = """{}""")).status)
    }

    @Test fun setTtsVoice_unknownVoice_is400() {
        val rt = FakeAnnounceRuntime().apply { setTtsVoiceResult = ControlTtsVoiceResult.UnknownVoice }
        val res = route(req("POST", "/api/tts/voice", body = """{"id":"system:e/gone"}"""), rt)
        assertEquals(400, res.status)
    }

    @Test fun setTtsVoice_ttsUnavailable_is503() {
        val rt = FakeAnnounceRuntime().apply { setTtsVoiceResult = ControlTtsVoiceResult.TtsUnavailable }
        assertEquals(503, route(req("POST", "/api/tts/voice", body = """{"id":"system:e/v"}"""), rt).status)
    }

    // -- POST /api/tts/voices/{download,delete} ------------------------------

    @Test fun voiceDownload_started_is202() {
        val rt = FakeAnnounceRuntime()
        val res = route(req("POST", "/api/tts/voices/download", body = """{"id":"piper:en_US-amy-medium"}"""), rt)
        assertEquals(202, res.status)
        assertEquals(listOf("en_US-amy-medium"), rt.downloadVoiceCalls)
    }

    @Test fun voiceDownload_busy_is409() {
        val rt = FakeAnnounceRuntime().apply { downloadVoiceResult = ControlVoiceDownloadStart.BUSY }
        assertEquals(409, route(req("POST", "/api/tts/voices/download", body = """{"id":"piper:x"}"""), rt).status)
    }

    @Test fun voiceDownload_unknown_is404() {
        val rt = FakeAnnounceRuntime().apply { downloadVoiceResult = ControlVoiceDownloadStart.UNKNOWN_VOICE }
        assertEquals(404, route(req("POST", "/api/tts/voices/download", body = """{"id":"piper:x"}"""), rt).status)
    }

    @Test fun voiceDownload_noSpace_is507() {
        val rt = FakeAnnounceRuntime().apply { downloadVoiceResult = ControlVoiceDownloadStart.NO_SPACE }
        assertEquals(507, route(req("POST", "/api/tts/voices/download", body = """{"id":"piper:x"}"""), rt).status)
    }

    @Test fun voiceDownload_systemSelector_is400BeforeRuntime() {
        val rt = FakeAnnounceRuntime()
        assertEquals(400, route(req("POST", "/api/tts/voices/download", body = """{"id":"system:default"}"""), rt).status)
        assertTrue(rt.downloadVoiceCalls.isEmpty())
    }

    @Test fun voiceDelete_ok_returnsFreshList() {
        val rt = FakeAnnounceRuntime()
        val res = route(req("POST", "/api/tts/voices/delete", body = """{"id":"piper:en_US-amy-medium"}"""), rt)
        assertEquals(200, res.status)
        assertEquals(rt.ttsVoicesValue.toJson(), res.body)
        assertEquals(listOf("en_US-amy-medium"), rt.deleteVoiceCalls)
    }

    @Test fun voiceDelete_notInstalled_is404() {
        val rt = FakeAnnounceRuntime().apply { deleteVoiceResult = ControlVoiceDeleteResult.NotInstalled }
        assertEquals(404, route(req("POST", "/api/tts/voices/delete", body = """{"id":"piper:x"}"""), rt).status)
    }

    @Test fun voiceDelete_busyOnThatVoice_is409() {
        val rt = FakeAnnounceRuntime().apply { deleteVoiceResult = ControlVoiceDeleteResult.Busy }
        assertEquals(409, route(req("POST", "/api/tts/voices/delete", body = """{"id":"piper:x"}"""), rt).status)
    }
}
