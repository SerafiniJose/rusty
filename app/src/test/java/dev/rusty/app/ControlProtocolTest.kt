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
    )
    override fun snapshot(): ControlSnapshot = snap

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
}

class ControlProtocolTest {
    private val localHosts = setOf("192.168.7.116")

    private fun req(
        method: String,
        path: String,
        body: String = "",
        contentType: String? = "application/json",
        host: String? = "192.168.7.116",
    ): HttpRequest {
        val headers = LinkedHashMap<String, String>()
        if (host != null) headers["HOST"] = host
        if (contentType != null) headers["CONTENT-TYPE"] = contentType
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
}
