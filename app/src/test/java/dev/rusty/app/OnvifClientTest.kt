package dev.rusty.app

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifClientTest {

    // --- fixtures -----------------------------------------------------------------------------

    private val deviceXAddr = "http://192.168.2.44/onvif/device_service"
    private val mediaXAddr = "http://192.168.2.44/onvif/media"

    /** Device clock deliberately different from wall time, so `Created` can be attributed. */
    private val deviceTimeXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
          <s:Body>
            <tds:GetSystemDateAndTimeResponse>
              <tds:SystemDateAndTime>
                <tt:DateTimeType>NTP</tt:DateTimeType>
                <tt:DaylightSavings>false</tt:DaylightSavings>
                <tt:TimeZone><tt:TZ>CST-8</tt:TZ></tt:TimeZone>
                <tt:UTCDateTime>
                  <tt:Time><tt:Hour>3</tt:Hour><tt:Minute>4</tt:Minute><tt:Second>5</tt:Second></tt:Time>
                  <tt:Date><tt:Year>2010</tt:Year><tt:Month>9</tt:Month><tt:Day>16</tt:Day></tt:Date>
                </tt:UTCDateTime>
                <tt:LocalDateTime>
                  <tt:Time><tt:Hour>11</tt:Hour><tt:Minute>4</tt:Minute><tt:Second>5</tt:Second></tt:Time>
                  <tt:Date><tt:Year>2010</tt:Year><tt:Month>9</tt:Month><tt:Day>16</tt:Day></tt:Date>
                </tt:LocalDateTime>
              </tds:SystemDateAndTime>
            </tds:GetSystemDateAndTimeResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private val deviceTime = "2010-09-16T03:04:05Z"

    private fun servicesXmlAdvertising(mediaAddr: String) = servicesXml.replace(mediaXAddr, mediaAddr)

    private val servicesXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:tds="http://www.onvif.org/ver10/device/wsdl">
          <s:Body>
            <tds:GetServicesResponse>
              <tds:Service>
                <tds:Namespace>http://www.onvif.org/ver10/device/wsdl</tds:Namespace>
                <tds:XAddr>$deviceXAddr</tds:XAddr>
                <tds:Version><Major>2</Major><Minor>60</Minor></tds:Version>
              </tds:Service>
              <tds:Service>
                <tds:Namespace>http://www.onvif.org/ver10/media/wsdl</tds:Namespace>
                <tds:XAddr>$mediaXAddr</tds:XAddr>
                <tds:Version><Major>2</Major><Minor>60</Minor></tds:Version>
              </tds:Service>
            </tds:GetServicesResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private fun profileXml(token: String, encoding: String, width: Int, height: Int, fps: Int) = """
              <trt:Profiles token="$token" fixed="true">
                <tt:Name>$token</tt:Name>
                <tt:VideoEncoderConfiguration token="vec_$token">
                  <tt:Name>vec_$token</tt:Name>
                  <tt:Encoding>$encoding</tt:Encoding>
                  <tt:Resolution><tt:Width>$width</tt:Width><tt:Height>$height</tt:Height></tt:Resolution>
                  <tt:Quality>4</tt:Quality>
                  <tt:RateControl>
                    <tt:FrameRateLimit>$fps</tt:FrameRateLimit>
                    <tt:EncodingInterval>1</tt:EncodingInterval>
                    <tt:BitrateLimit>4096</tt:BitrateLimit>
                  </tt:RateControl>
                </tt:VideoEncoderConfiguration>
              </trt:Profiles>
    """.trimIndent()

    private fun profilesEnvelope(vararg profiles: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
<s:Body>
<trt:GetProfilesResponse>
${profiles.joinToString("\n")}
</trt:GetProfilesResponse>
</s:Body>
</s:Envelope>"""

    private val threeProfilesXml = profilesEnvelope(
        profileXml("MainStream", "H264", 2560, 1440, 25),
        profileXml("SubStream", "H264", 640, 360, 15),
        profileXml("HevcStream", "H265", 1920, 1080, 25),
    )

    private val streamUriXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
          <s:Body>
            <trt:GetStreamUriResponse>
              <trt:MediaUri>
                <tt:Uri>rtsp://192.168.2.44:554/h264Preview_01_sub</tt:Uri>
                <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                <tt:Timeout>PT60S</tt:Timeout>
              </trt:MediaUri>
            </trt:GetStreamUriResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private val mainToken = "MainStream"

    private val streamUriMainXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
          <s:Body>
            <trt:GetStreamUriResponse>
              <trt:MediaUri>
                <tt:Uri>rtsp://192.168.2.44:554/h264Preview_01_main</tt:Uri>
                <tt:InvalidAfterConnect>false</tt:InvalidAfterConnect>
                <tt:Timeout>PT60S</tt:Timeout>
              </trt:MediaUri>
            </trt:GetStreamUriResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    private val snapshotUriXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:trt="http://www.onvif.org/ver10/media/wsdl" xmlns:tt="http://www.onvif.org/ver10/schema">
          <s:Body>
            <trt:GetSnapshotUriResponse>
              <trt:MediaUri>
                <tt:Uri>http://192.168.2.44/onvif-http/snapshot?channel=1</tt:Uri>
              </trt:MediaUri>
            </trt:GetSnapshotUriResponse>
          </s:Body>
        </s:Envelope>
    """.trimIndent()

    // --- scripted transport -------------------------------------------------------------------

    private data class RecordedCall(val url: String, val body: String, val headers: Map<String, String>)

    /** Hand-written transport: records every call and answers from the supplied script. */
    private class ScriptedTransport(
        val script: (url: String, body: String, headers: Map<String, String>, index: Int) -> SoapResponse,
    ) : SoapTransport {
        val calls = mutableListOf<RecordedCall>()

        override fun post(url: String, body: String, headers: Map<String, String>): SoapResponse {
            val index = calls.size
            calls += RecordedCall(url, body, headers)
            return script(url, body, headers, index)
        }

        fun callsTo(url: String) = calls.filter { it.url == url }
    }

    private fun ok(body: String) = SoapResponse(200, body, emptyMap())

    private val fixedNonce = ByteArray(16) { it.toByte() }

    private fun happyResponse(body: String): SoapResponse = when {
        body.contains("GetSystemDateAndTime") -> ok(deviceTimeXml)
        body.contains("GetServices") -> ok(servicesXml)
        body.contains("GetProfiles") -> ok(threeProfilesXml)
        body.contains("GetStreamUri") -> ok(if (body.contains(mainToken)) streamUriMainXml else streamUriXml)
        body.contains("GetSnapshotUri") -> ok(snapshotUriXml)
        else -> SoapResponse(500, "unexpected", emptyMap())
    }

    // --- WS-UsernameToken ---------------------------------------------------------------------

    @Test
    fun `ws username token matches the published ONVIF vector`() {
        val nonce = Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==")
        val header = OnvifSoap.wsUsernameToken(
            username = "test",
            password = "userpassword",
            nonce = nonce,
            created = "2010-09-16T07:50:45Z",
        )

        assertTrue("digest missing from $header", header.contains("tuOSpGlFlIXsozq4HFNeeGeFLEI="))
        assertTrue(header.contains("LKqI6G/AikKCQrN0zqZFlg=="))
        assertTrue(header.contains("2010-09-16T07:50:45Z"))
        assertTrue(header.contains("#PasswordDigest"))
        assertTrue(header.contains("<wsse:Username>test</wsse:Username>"))
    }

    // --- HTTP Digest --------------------------------------------------------------------------

    @Test
    fun `http digest response matches hand-computed md5`() {
        val authorization = OnvifSoap.httpDigestAuthorization(
            username = "admin",
            password = "pass",
            method = "POST",
            uri = "/onvif/media",
            challenge = """Digest realm="r", nonce="n", qop="auth"""",
            cnonce = "c",
        )

        // HA1 = MD5("admin:r:pass"), HA2 = MD5("POST:/onvif/media"),
        // response = MD5(HA1:n:00000001:c:auth:HA2) = 50f3fbf7f8002a4b68a32eb7ee6628d1
        assertTrue(authorization.startsWith("Digest "))
        assertTrue(authorization.contains("""response="50f3fbf7f8002a4b68a32eb7ee6628d1""""))
        assertTrue(authorization.contains("""username="admin""""))
        assertTrue(authorization.contains("""realm="r""""))
        assertTrue(authorization.contains("""nonce="n""""))
        assertTrue(authorization.contains("""uri="/onvif/media""""))
        assertTrue(authorization.contains("qop=auth"))
        assertTrue(authorization.contains("nc=00000001"))
        assertTrue(authorization.contains("""cnonce="c""""))
    }

    // --- parsing ------------------------------------------------------------------------------

    @Test
    fun `parseServices returns the media service address`() {
        assertEquals(mediaXAddr, OnvifSoap.parseServices(servicesXml))
        assertNull(OnvifSoap.parseServices("<html>not soap</html>"))
    }

    @Test
    fun `parseProfiles reads token codec resolution and frame rate`() {
        val profiles = OnvifSoap.parseProfiles(threeProfilesXml)

        assertEquals(3, profiles.size)
        assertEquals(OnvifProfile("MainStream", "H264", 2560, 1440, 25), profiles[0])
        assertEquals(OnvifProfile("SubStream", "H264", 640, 360, 15), profiles[1])
        assertEquals(OnvifProfile("HevcStream", "H265", 1920, 1080, 25), profiles[2])
    }

    @Test
    fun `parseUri reads stream and snapshot uris`() {
        assertEquals("rtsp://192.168.2.44:554/h264Preview_01_sub", OnvifSoap.parseUri(streamUriXml))
        assertEquals("http://192.168.2.44/onvif-http/snapshot?channel=1", OnvifSoap.parseUri(snapshotUriXml))
        assertNull(OnvifSoap.parseUri("<broken"))
    }

    @Test
    fun `parseDeviceTime formats the UTC device clock`() {
        assertEquals(deviceTime, OnvifSoap.parseDeviceTime(deviceTimeXml))
        assertNull(OnvifSoap.parseDeviceTime("<html>nope</html>"))
    }

    // --- profile selection --------------------------------------------------------------------

    private val anyDecoder: (String, Int, Int) -> Boolean = { _, _, _ -> true }
    private val upTo1080: (String, Int, Int) -> Boolean = { _, _, h -> h <= 1080 }

    @Test
    fun `selectProfiles - office CX820 - sub 360p and main 1440p when decodable`() {
        val profiles = listOf(
            OnvifProfile("000", "H264", 2560, 1440, 25),
            OnvifProfile("001", "H264", 640, 360, 15),
        )
        val pair = OnvifSoap.selectProfiles(profiles, hevcAllowed = false, canDecode = anyDecoder)!!
        assertEquals("001", pair.sub.token)
        assertEquals("000", pair.main?.token)
        assertNull(pair.skippedMain)
    }

    @Test
    fun `selectProfiles - main skipped when the device cannot decode it`() {
        val profiles = listOf(
            OnvifProfile("000", "H264", 2560, 1440, 25),
            OnvifProfile("001", "H264", 640, 360, 15),
        )
        val pair = OnvifSoap.selectProfiles(profiles, hevcAllowed = false, canDecode = upTo1080)!!
        assertEquals("001", pair.sub.token)
        assertNull(pair.main)
        assertEquals("000", pair.skippedMain?.token)
    }

    @Test
    fun `selectProfiles - single profile is the sub and there is no main`() {
        val pair = OnvifSoap.selectProfiles(listOf(OnvifProfile("only", "H264", 1920, 1080, 30)), false, anyDecoder)!!
        assertEquals("only", pair.sub.token)
        assertNull(pair.main)
        assertNull(pair.skippedMain)
    }

    @Test
    fun `selectProfiles - sub prefers the shortest at or above 360 lines, else the shortest`() {
        val tall = listOf(OnvifProfile("a", "H264", 640, 360, 15), OnvifProfile("b", "H264", 1920, 576, 20), OnvifProfile("c", "H264", 4096, 1248, 20))
        assertEquals("a", OnvifSoap.selectProfiles(tall, false, anyDecoder)!!.sub.token)
        val tiny = listOf(OnvifProfile("q", "H264", 320, 240, 10), OnvifProfile("r", "H264", 352, 288, 10))
        assertEquals("q", OnvifSoap.selectProfiles(tiny, false, anyDecoder)!!.sub.token)
    }

    @Test
    fun `selectProfiles - main falls back to the next decodable H264 and prefers 30 fps or less`() {
        val profiles = listOf(
            OnvifProfile("4k", "H264", 3840, 2160, 30),
            OnvifProfile("fhd60", "H264", 1920, 1080, 60),
            OnvifProfile("fhd25", "H264", 1920, 1080, 25),
            OnvifProfile("sub", "H264", 640, 360, 15),
        )
        val pair = OnvifSoap.selectProfiles(profiles, false, upTo1080)!!
        assertEquals("fhd25", pair.main?.token)
        assertNull(pair.skippedMain)
    }

    @Test
    fun `selectProfiles - H265 only when allowed`() {
        val hevcOnly = listOf(OnvifProfile("h", "H265", 1920, 1080, 25))
        assertNull(OnvifSoap.selectProfiles(hevcOnly, hevcAllowed = false, canDecode = anyDecoder))
        assertEquals("h", OnvifSoap.selectProfiles(hevcOnly, hevcAllowed = true, canDecode = anyDecoder)!!.sub.token)
    }

    @Test
    fun `selectProfiles - no profiles at all yields no pair`() {
        assertNull(OnvifSoap.selectProfiles(emptyList(), hevcAllowed = false, canDecode = anyDecoder))
        assertNull(OnvifSoap.selectProfiles(emptyList(), hevcAllowed = true, canDecode = anyDecoder))
        // A profile with no usable dimensions is not a profile either.
        assertNull(OnvifSoap.selectProfiles(listOf(OnvifProfile("bad", "H264", 0, 0, 0)), hevcAllowed = true, canDecode = anyDecoder))
    }

    @Test
    fun `selectProfiles - an eligible H264 still wins the sub slot over a shorter H265`() {
        val mixed = listOf(
            OnvifProfile("hevcSub", "H265", 320, 240, 15),
            OnvifProfile("avcSub", "H264", 640, 360, 15),
            OnvifProfile("avcMain", "H264", 2560, 1440, 25),
        )
        val pair = OnvifSoap.selectProfiles(mixed, hevcAllowed = true, canDecode = anyDecoder)!!
        assertEquals("avcSub", pair.sub.token)
        assertEquals("avcMain", pair.main?.token)
        assertNull(pair.skippedMain)
    }

    // --- resolve ------------------------------------------------------------------------------

    @Test
    fun `resolve walks the ONVIF sequence and returns both uris`() {
        val transport = ScriptedTransport { _, body, _, _ -> happyResponse(body) }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertEquals(
            OnvifResult.Resolved(
                "rtsp://192.168.2.44:554/h264Preview_01_sub",
                "http://192.168.2.44/onvif-http/snapshot?channel=1",
                "rtsp://192.168.2.44:554/h264Preview_01_main",
                null,
            ),
            result,
        )
        val bodies = transport.calls.map { it.body }
        assertEquals(6, bodies.size)
        assertTrue(bodies[0].contains("GetSystemDateAndTime"))
        assertTrue(bodies[1].contains("GetServices"))
        assertTrue(bodies[2].contains("GetProfiles"))
        assertTrue(bodies[3].contains("GetStreamUri"))
        assertTrue(bodies[3].contains("SubStream"))
        assertTrue(bodies[3].contains("RTP-Unicast"))
        assertTrue(bodies[3].contains("RTSP"))
        assertTrue(bodies[4].contains("GetStreamUri"))
        assertTrue(bodies[4].contains(mainToken))
        assertTrue(bodies[5].contains("GetSnapshotUri"))
        // The snapshot is taken from the sub profile, the one the grid shows.
        assertTrue(bodies[5].contains("SubStream"))
        // Media calls go to the media service address, not the device service.
        assertEquals(mediaXAddr, transport.calls[2].url)
        assertEquals(mediaXAddr, transport.calls[3].url)
        assertEquals(mediaXAddr, transport.calls[4].url)
    }


    @Test
    fun `resolve stamps Created with the device clock and skips auth on the time call`() {
        val transport = ScriptedTransport { _, body, _, _ -> happyResponse(body) }
        val client = OnvifClient(transport, { fixedNonce })

        client.resolve(deviceXAddr, "admin", "pass")

        assertFalse(transport.calls[0].body.contains("Security"))
        for (call in transport.calls.drop(1)) {
            assertTrue("no security header in ${call.body}", call.body.contains("UsernameToken"))
            assertTrue("Created is not the device clock: ${call.body}", call.body.contains("<wsu:Created>$deviceTime</wsu:Created>"))
        }
    }

    @Test
    fun `resolve succeeds without a snapshot uri when GetSnapshotUri fails`() {
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetSnapshotUri")) SoapResponse(500, "boom", emptyMap()) else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        assertEquals(
            OnvifResult.Resolved("rtsp://192.168.2.44:554/h264Preview_01_sub", null, "rtsp://192.168.2.44:554/h264Preview_01_main", null),
            client.resolve(deviceXAddr, "admin", "pass"),
        )
    }

    @Test
    fun `resolve falls back to our own clock when the device time is unreadable`() {
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetSystemDateAndTime")) SoapResponse(404, "nope", emptyMap()) else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue(result is OnvifResult.Resolved)
        val created = Regex("<wsu:Created>(.*?)</wsu:Created>").find(transport.calls[1].body)?.groupValues?.get(1)
        assertNotNull(created)
        assertTrue("not an ISO-8601 UTC stamp: $created", Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""").matches(created!!))
    }

    @Test
    fun `resolve retries a 401 once with an HTTP digest authorization`() {
        val challenge = """Digest realm="r", nonce="n", qop="auth""""
        // The retry repeats the same call, so the 401 is scripted by call order.
        val ordered = ScriptedTransport { _, body, _, index ->
            if (body.contains("GetServices") && index == 1) {
                SoapResponse(401, "", mapOf("WWW-Authenticate" to challenge))
            } else {
                happyResponse(body)
            }
        }

        val client = OnvifClient(ordered, { fixedNonce }, cnonceSource = { "c" })
        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue(result is OnvifResult.Resolved)
        val retried = ordered.calls[2]
        assertEquals(deviceXAddr, retried.url)
        assertTrue(retried.body.contains("GetServices"))
        // HA1=MD5("admin:r:pass"), HA2=MD5("POST:/onvif/device_service"),
        // response = MD5(HA1:n:00000001:c:auth:HA2) = f61530feb29ce693f210ff22e06c6d2b
        val authorization = retried.headers["Authorization"]
        assertNotNull(authorization)
        assertTrue(authorization!!.contains("""response="f61530feb29ce693f210ff22e06c6d2b""""))
        // The WS-Security header stays on the retried call.
        assertTrue(retried.body.contains("UsernameToken"))
    }

    @Test
    fun `resolve fails with step auth when the digest retry is rejected too`() {
        val challenge = """Digest realm="r", nonce="n", qop="auth""""
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetServices")) SoapResponse(401, "", mapOf("WWW-Authenticate" to challenge))
            else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce }, cnonceSource = { "c" })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected a failure, got $result", result is OnvifResult.Failed)
        assertEquals("auth", (result as OnvifResult.Failed).step)
        // One unauthenticated time call + two GetServices attempts, and no further calls.
        assertEquals(3, transport.calls.size)
    }

    @Test
    fun `resolve follows a same-host redirect`() {
        val moved = "http://192.168.2.44/onvif/device_service_v2"
        val transport = ScriptedTransport { url, body, _, _ ->
            if (url == deviceXAddr && body.contains("GetServices")) {
                SoapResponse(302, "", mapOf("Location" to moved))
            } else {
                happyResponse(body)
            }
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected success, got $result", result is OnvifResult.Resolved)
        assertEquals(1, transport.callsTo(moved).size)
    }

    @Test
    fun `resolve does not carry the digest authorization across a redirect hop`() {
        val challenge = """Digest realm="r", nonce="n", qop="auth""""
        val moved = "http://192.168.2.44/onvif/device_service_v2"
        // First GetServices attempt 401s (digest handshake), the retry then redirects.
        val transport = ScriptedTransport { url, body, _, index ->
            when {
                !body.contains("GetServices") -> happyResponse(body)
                index == 1 -> SoapResponse(401, "", mapOf("WWW-Authenticate" to challenge))
                url == deviceXAddr -> SoapResponse(302, "", mapOf("Location" to moved))
                else -> happyResponse(body)
            }
        }
        val client = OnvifClient(transport, { fixedNonce }, cnonceSource = { "c" })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected success, got $result", result is OnvifResult.Resolved)
        // The digest response is bound to the request URI, so the new hop must start clean.
        val hop = transport.callsTo(moved).single()
        assertNull(hop.headers["Authorization"])
    }

    @Test
    fun `resolve gives up after two redirects`() {
        val hop1 = "http://192.168.2.44/hop1"
        val hop2 = "http://192.168.2.44/hop2"
        val hop3 = "http://192.168.2.44/hop3"
        val transport = ScriptedTransport { url, body, _, _ ->
            when {
                !body.contains("GetServices") -> happyResponse(body)
                url == deviceXAddr -> SoapResponse(302, "", mapOf("Location" to hop1))
                url == hop1 -> SoapResponse(302, "", mapOf("Location" to hop2))
                url == hop2 -> SoapResponse(302, "", mapOf("Location" to hop3))
                else -> happyResponse(body)
            }
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected a failure, got $result", result is OnvifResult.Failed)
        assertEquals("services", (result as OnvifResult.Failed).step)
        assertTrue(result.detail.contains("redirect", ignoreCase = true))
        assertTrue(transport.callsTo(hop3).isEmpty())
    }

    @Test
    fun `resolve refuses a cross-host redirect without leaking credentials`() {
        val foreign = "http://evil.example.com/onvif/device_service"
        val transport = ScriptedTransport { url, body, _, _ ->
            if (url == deviceXAddr && body.contains("GetServices")) {
                SoapResponse(302, "", mapOf("Location" to foreign))
            } else {
                happyResponse(body)
            }
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected a failure, got $result", result is OnvifResult.Failed)
        assertEquals("services", (result as OnvifResult.Failed).step)
        assertTrue(transport.callsTo(foreign).isEmpty())
        assertTrue(transport.calls.none { it.url.contains("evil.example.com") })
    }

    @Test
    fun `resolve pins a foreign media XAddr back to the device host`() {
        val foreignMedia = "http://evil.example.com/onvif/media_alt?ch=1"
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetServices")) ok(servicesXmlAdvertising(foreignMedia)) else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected success, got $result", result is OnvifResult.Resolved)
        // Path (and query) kept, host replaced by the one the user configured.
        assertEquals("http://192.168.2.44/onvif/media_alt?ch=1", transport.calls[2].url)
        assertEquals("http://192.168.2.44/onvif/media_alt?ch=1", transport.calls[3].url)
        assertTrue(transport.calls.none { it.url.contains("evil.example.com") })
    }

    @Test
    fun `resolve pins a media XAddr on another port back to the device host`() {
        val otherPort = "http://192.168.2.44:8899/onvif/media"
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetServices")) ok(servicesXmlAdvertising(otherPort)) else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected success, got $result", result is OnvifResult.Resolved)
        assertEquals(mediaXAddr, transport.calls[2].url)
        assertTrue(transport.calls.none { it.url.contains(":8899") })
    }

    @Test
    fun `digest username is escaped and cannot inject a header line`() {
        val authorization = OnvifSoap.httpDigestAuthorization(
            username = "ad\"min\r\nX-Evil: 1",
            password = "pass",
            method = "POST",
            uri = "/onvif/media",
            challenge = """Digest realm="r", nonce="n", qop="auth"""",
            cnonce = "c",
        )

        assertFalse(authorization.contains("\r"))
        assertFalse(authorization.contains("\n"))
        assertTrue(authorization.contains("""username="ad\"minX-Evil: 1""""))
        // Escaping is presentation only — the hash still uses the RAW username, as RFC 7616 requires:
        // HA1 = MD5("ad\"min\r\nX-Evil: 1:r:pass"), response = MD5(HA1:n:00000001:c:auth:HA2).
        assertTrue(authorization.contains("""response="ebae2c871bcc8aa9772cc709b0ce9d46""""))
    }

    @Test
    fun `resolve fails with step profiles on a broken GetProfiles body`() {
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetProfiles")) ok("<not-xml") else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected a failure, got $result", result is OnvifResult.Failed)
        assertEquals("profiles", (result as OnvifResult.Failed).step)
    }

    @Test
    fun `resolve fails with step services when no media service is advertised`() {
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetServices")) ok("<html>hello</html>") else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected a failure, got $result", result is OnvifResult.Failed)
        assertEquals("services", (result as OnvifResult.Failed).step)
    }

    @Test
    fun `resolve fails with step streamUri when the stream uri is missing`() {
        val transport = ScriptedTransport { _, body, _, _ ->
            if (body.contains("GetStreamUri")) ok("<s:Envelope/>") else happyResponse(body)
        }
        val client = OnvifClient(transport, { fixedNonce })

        val result = client.resolve(deviceXAddr, "admin", "pass")

        assertTrue("expected a failure, got $result", result is OnvifResult.Failed)
        assertEquals("streamUri", (result as OnvifResult.Failed).step)
    }

    // --- probe --------------------------------------------------------------------------------

    @Test
    fun `probeDevice answers on a SystemDateAndTime reply`() {
        val transport = ScriptedTransport { _, _, _, _ -> ok(deviceTimeXml) }
        assertEquals(OnvifProbe.Answered, OnvifClient(transport, { fixedNonce }).probeDevice(deviceXAddr))
        assertTrue(transport.calls.single().body.contains("GetSystemDateAndTime"))
        assertFalse(transport.calls.single().body.contains("wsse:Security"))
    }

    @Test
    fun `probeDevice answers on a 400 or 401 carrying a SOAP fault`() {
        // An endpoint that refuses the anonymous clock call with a SOAP fault is still ONVIF.
        assertEquals(OnvifProbe.Answered, OnvifClient(ScriptedTransport { _, _, _, _ -> SoapResponse(400, "<s:Fault/>", emptyMap()) }, { fixedNonce }).probeDevice(deviceXAddr))
        assertEquals(
            OnvifProbe.Answered,
            OnvifClient(
                ScriptedTransport { _, _, _, _ ->
                    SoapResponse(401, "<SOAP-ENV:Fault><SOAP-ENV:Code><SOAP-ENV:Value>ter:NotAuthorized</SOAP-ENV:Value></SOAP-ENV:Code></SOAP-ENV:Fault>", emptyMap())
                },
                { fixedNonce },
            ).probeDevice(deviceXAddr),
        )
    }

    @Test
    fun `probeDevice says NotOnvif for a plain error page or html and NoAnswer for a transport failure`() {
        // 400 without any SOAP fault marker — a web server, not a camera.
        assertEquals(OnvifProbe.NotOnvif, OnvifClient(ScriptedTransport { _, _, _, _ -> SoapResponse(400, "<html><body>Bad Request</body></html>", emptyMap()) }, { fixedNonce }).probeDevice(deviceXAddr))
        assertEquals(OnvifProbe.NotOnvif, OnvifClient(ScriptedTransport { _, _, _, _ -> SoapResponse(404, "<s:Fault/>", emptyMap()) }, { fixedNonce }).probeDevice(deviceXAddr))
        assertEquals(OnvifProbe.NotOnvif, OnvifClient(ScriptedTransport { _, _, _, _ -> SoapResponse(200, "<html>router</html>", emptyMap()) }, { fixedNonce }).probeDevice(deviceXAddr))
        assertEquals(OnvifProbe.NoAnswer, OnvifClient(ScriptedTransport { _, _, _, _ -> throw java.net.ConnectException("refused") }, { fixedNonce }).probeDevice(deviceXAddr))
    }
}
