package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspProtocolTest {
    private val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
    private val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
    private val ready = object : RtspStreamSource { override fun describe() = DescribeResult.Ready(sps, pps) }
    private val down = object : RtspStreamSource { override fun describe() = DescribeResult.Unavailable("camera gated") }
    private fun proto() = RtspProtocol(sessionIdSource = { "abc123" }, nonceSource = { "n0nce" })
    private fun req(method: String, cseq: Int, vararg h: Pair<String, String>, uri: String = "rtsp://10.0.0.5:8554/live") =
        RtspRequest(method, uri, cseq, h.toMap().mapKeys { it.key.uppercase() })
    private fun headers(r: RtspResponse) = r.headers.associate { it.first.uppercase() to it.second }

    @Test fun `parse reads request line CSeq and headers case-insensitively`() {
        val r = proto().parse("OPTIONS rtsp://h:8554/live RTSP/1.0\r\ncseq: 3\r\nUser-Agent: x\r\n\r\n")!!
        assertEquals("OPTIONS", r.method); assertEquals(3, r.cseq); assertEquals("x", r.headers["USER-AGENT"])
        assertNull(proto().parse("garbage\r\n\r\n"))
        assertNull(proto().parse("OPTIONS rtsp://h/live RTSP/1.0\r\n\r\n"))   // no CSeq
    }

    @Test fun `OPTIONS lists methods and echoes CSeq`() {
        val out = proto().handle(req("OPTIONS", 1), ready, null)
        assertEquals(200, out.response.status)
        assertEquals("1", headers(out.response)["CSEQ"])
        assertEquals("OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER", headers(out.response)["PUBLIC"])
        assertEquals(SessionEffect.NONE, out.effect)
    }

    @Test fun `DESCRIBE returns sdp with sprop-parameter-sets`() {
        val out = proto().handle(req("DESCRIBE", 2, "Accept" to "application/sdp"), ready, null)
        assertEquals(200, out.response.status)
        assertEquals("application/sdp", headers(out.response)["CONTENT-TYPE"])
        assertTrue(out.response.body.contains("m=video 0 RTP/AVP 96"))
        assertTrue(out.response.body.contains("a=rtpmap:96 H264/90000"))
        assertTrue(out.response.body.contains("sprop-parameter-sets=Z0IAHg==,aM44gA=="))
        assertTrue(out.response.body.contains("packetization-mode=1"))
        assertTrue(out.response.body.contains("a=control:"))
        assertTrue(headers(out.response)["CONTENT-BASE"]!!.endsWith("/live/"))
    }

    @Test fun `DESCRIBE when the camera cannot start is 503`() {
        val out = proto().handle(req("DESCRIBE", 2), down, null)
        assertEquals(503, out.response.status)
    }

    @Test fun `DESCRIBE for another path is 404`() {
        val out = proto().handle(req("DESCRIBE", 2, uri = "rtsp://h:8554/other"), ready, null)
        assertEquals(404, out.response.status)
    }

    @Test fun `sdp strips Annex-B start codes from the parameter sets`() {
        val bare = RtspProtocol.sdp(sps, pps)
        val prefixed = RtspProtocol.sdp(byteArrayOf(0, 0, 0, 1) + sps, byteArrayOf(0, 0, 1) + pps)
        assertEquals(bare, prefixed)
        assertTrue(prefixed.contains("profile-level-id=42001e"))
        assertTrue(prefixed.contains("sprop-parameter-sets=Z0IAHg==,aM44gA=="))
    }

    @Test fun `SETUP interleaved tcp creates a session, udp is 461`() {
        val p = proto()
        val bad = p.handle(req("SETUP", 3, "Transport" to "RTP/AVP;unicast;client_port=5000-5001"), ready, null)
        assertEquals(461, bad.response.status); assertNull(p.sessionId)
        val ok = p.handle(req("SETUP", 4, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, null)
        assertEquals(200, ok.response.status)
        assertEquals("abc123;timeout=60", headers(ok.response)["SESSION"])
        assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", headers(ok.response)["TRANSPORT"])
        assertEquals("abc123", p.sessionId)
    }

    @Test fun `SETUP echoes the client's interleaved channels`() {
        val p = proto()
        assertEquals(0, p.rtpChannel)
        val ok = p.handle(req("SETUP", 3, "Transport" to "RTP/AVP/TCP;unicast;interleaved=2-3"), ready, null)
        assertEquals(200, ok.response.status)
        assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", headers(ok.response)["TRANSPORT"])
        assertEquals(2, p.rtpChannel)
        val q = proto()
        val junk = q.handle(req("SETUP", 3, "Transport" to "RTP/AVP/TCP;unicast;interleaved=oops"), ready, null)
        assertEquals(200, junk.response.status)
        assertEquals("RTP/AVP/TCP;unicast;interleaved=0-1", headers(junk.response)["TRANSPORT"])
        assertEquals(0, q.rtpChannel)
    }

    @Test fun `an out of range interleaved channel is refused`() {
        // An interleaved channel is one byte on the wire: honouring 999 would put frames on
        // channel 231 while the echoed header still said 999, i.e. a black screen the client
        // cannot explain. Refuse instead.
        val p = proto()
        val out = p.handle(req("SETUP", 3, "Transport" to "RTP/AVP/TCP;unicast;interleaved=999-1000"), ready, null)
        assertEquals(461, out.response.status)
        assertNull(p.sessionId)
        assertEquals(0, p.rtpChannel)
        val q = proto()
        assertEquals(461, q.handle(req("SETUP", 3, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-256"), ready, null).response.status)
        assertEquals(0, q.rtpChannel)
    }

    @Test fun `RTP-Info advertises the real stream position, not a hardcoded zero`() {
        val p = proto()
        p.handle(req("SETUP", 6, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, null)
        // First PLAY: nothing has been sent and RtspServer rebases timestamps to this client's
        // first access unit, so zero is exact.
        val first = p.handle(req("PLAY", 7, "Session" to "abc123"), ready, null)
        assertEquals("url=rtsp://10.0.0.5:8554/live/track0;seq=0;rtptime=0", headers(first.response)["RTP-INFO"])
        // A replayed PLAY (VLC's PAUSE->405->PLAY, a media3 seek) does not restart the stream, so
        // repeating zero would tell a client that resyncs on RTP-Info to treat every packet it
        // then receives as far-future.
        val again = p.handle(req("PLAY", 8, "Session" to "abc123"), ready, null) { 412 to 5_400_000L }
        assertEquals(SessionEffect.NONE, again.effect)
        assertEquals("url=rtsp://10.0.0.5:8554/live/track0;seq=412;rtptime=5400000", headers(again.response)["RTP-INFO"])
    }

    @Test fun `PLAY before SETUP is 455, after SETUP attaches`() {
        val p = proto()
        assertEquals(455, p.handle(req("PLAY", 5, "Session" to "abc123"), ready, null).response.status)
        p.handle(req("SETUP", 6, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, null)
        val play = p.handle(req("PLAY", 7, "Session" to "abc123"), ready, null)
        assertEquals(200, play.response.status)
        assertEquals(SessionEffect.ATTACH, play.effect)
        assertTrue(headers(play.response)["RTP-INFO"]!!.startsWith("url="))
        assertTrue(p.playing)
    }

    @Test fun `a repeated PLAY answers 200 but does not attach twice`() {
        val p = proto()
        p.handle(req("SETUP", 6, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, null)
        assertEquals(SessionEffect.ATTACH, p.handle(req("PLAY", 7, "Session" to "abc123"), ready, null).effect)
        val again = p.handle(req("PLAY", 8, "Session" to "abc123"), ready, null)
        assertEquals(200, again.response.status)
        assertEquals(SessionEffect.NONE, again.effect)
        assertTrue(headers(again.response)["RTP-INFO"]!!.startsWith("url="))
        assertTrue(p.playing)
    }

    @Test fun `wrong session id is 454`() {
        val p = proto()
        p.handle(req("SETUP", 6, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, null)
        assertEquals(454, p.handle(req("PLAY", 7, "Session" to "zzz"), ready, null).response.status)
    }

    @Test fun `TEARDOWN detaches and closes, GET_PARAMETER keeps alive`() {
        val p = proto()
        p.handle(req("SETUP", 6, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, null)
        p.handle(req("PLAY", 7, "Session" to "abc123"), ready, null)
        val gp = p.handle(req("GET_PARAMETER", 8, "Session" to "abc123"), ready, null)
        assertEquals(200, gp.response.status); assertEquals(SessionEffect.NONE, gp.effect)
        val td = p.handle(req("TEARDOWN", 9, "Session" to "abc123"), ready, null)
        assertEquals(200, td.response.status); assertEquals(SessionEffect.DETACH, td.effect); assertTrue(td.closeAfter)
        assertFalse(p.playing); assertNull(p.sessionId)
    }

    @Test fun `unknown method is 405`() {
        assertEquals(405, proto().handle(req("RECORD", 1), ready, null).response.status)
    }

    @Test fun `with a password every method but OPTIONS is challenged`() {
        val p = proto()
        assertEquals(200, p.handle(req("OPTIONS", 1), ready, "hunter2").response.status)
        val d = p.handle(req("DESCRIBE", 2), ready, "hunter2")
        assertEquals(401, d.response.status)
        assertEquals(2, d.response.headers.count { it.first == "WWW-Authenticate" })
        assertTrue(d.response.headers.any { it.second.contains("nonce=\"n0nce\"") })
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("rusty:hunter2".toByteArray())
        assertEquals(200, p.handle(req("DESCRIBE", 3, "Authorization" to basic), ready, "hunter2").response.status)
        val digest = "Digest username=\"rusty\", realm=\"rusty\", nonce=\"n0nce\", uri=\"rtsp://10.0.0.5:8554/live\", response=\"" +
            RtspAuth.digestResponse("rusty", "rusty", "hunter2", "SETUP", "rtsp://10.0.0.5:8554/live", "n0nce") + "\""
        assertEquals(200, p.handle(req("SETUP", 4, "Authorization" to digest, "Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"), ready, "hunter2").response.status)
        // The gate runs before method dispatch, so PLAY/TEARDOWN/GET_PARAMETER without credentials
        // are challenged too — an exemption here would matter most for PLAY, since that's the
        // method that actually starts video flowing.
        assertEquals(401, p.handle(req("PLAY", 5), ready, "hunter2").response.status)
        assertEquals(401, p.handle(req("TEARDOWN", 6), ready, "hunter2").response.status)
        assertEquals(401, p.handle(req("GET_PARAMETER", 7), ready, "hunter2").response.status)
        // Syntactically valid but computed with the wrong password: must not pass.
        val wrongPasswordDigest = "Digest username=\"rusty\", realm=\"rusty\", nonce=\"n0nce\", uri=\"rtsp://10.0.0.5:8554/live\", response=\"" +
            RtspAuth.digestResponse("rusty", "rusty", "wrongpassword", "DESCRIBE", "rtsp://10.0.0.5:8554/live", "n0nce") + "\""
        assertEquals(401, p.handle(req("DESCRIBE", 8, "Authorization" to wrongPasswordDigest), ready, "hunter2").response.status)
    }

    @Test fun `without a password nothing is challenged`() {
        assertEquals(200, proto().handle(req("DESCRIBE", 2), ready, null).response.status)
    }

    @Test fun `encode writes status line headers and content-length`() {
        val bytes = RtspResponse(200, "OK", listOf("CSeq" to "1"), "v=0\r\n").encode()
        val text = String(bytes, Charsets.US_ASCII)
        assertTrue(text.startsWith("RTSP/1.0 200 OK\r\nCSeq: 1\r\n"))
        assertTrue(text.contains("Content-Length: 5\r\n\r\nv=0\r\n"))
    }
}
