package dev.rusty.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspParamSetFetcherTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0x15)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())
    private val nonce = "9aeda17d85a4a73eec956ef3bcd7fc8d"
    private val sdp = "v=0\r\no=- 0 0 IN IP4 0.0.0.0\r\ns=x\r\nt=0 0\r\na=control:*\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\nm=audio 0 RTP/AVP 97\r\na=rtpmap:97 MPEG4-GENERIC/16000\r\na=control:track2\r\n"

    // The fake camera verifies Digest with the existing SERVER-side RtspAuth.authorized, which
    // hard-codes user "rusty" and realm "rusty" (RtspAuth.kt:19-20, 63-64). So the fake challenges
    // with realm "rusty" and the tests sign in as "rusty"; the client code itself is agnostic (see
    // the last test for a foreign realm/user).
    private val realm = RtspAuth.REALM
    private val user = BasicAuth.USER

    /** Reads one request head from [input]: (method, uri, headers upper-cased). */
    private fun readRequest(input: DataInputStream): Triple<String, String, Map<String, String>>? {
        val lines = mutableListOf<String>()
        while (true) {
            val sb = StringBuilder()
            while (true) { val c = input.read(); if (c < 0) return null; if (c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
            if (sb.isEmpty()) break
            lines.add(sb.toString())
        }
        val (m, u) = lines[0].split(' ')
        return Triple(m, u, lines.drop(1).associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':').trim() })
    }

    private fun rtp(payload: ByteArray): ByteArray = byteArrayOf(0x80.toByte(), 96, 0, 1, 0, 0, 0, 2, 0, 0, 0, 3) + payload
    private fun interleaved(channel: Int, packet: ByteArray) = byteArrayOf('$'.code.toByte(), channel.toByte(), (packet.size shr 8).toByte(), packet.size.toByte()) + packet

    /**
     * A fake Reolink: Digest-protected, DESCRIBE without fmtp, then SPS/PPS in-band after PLAY.
     * Records the methods it saw, the Authorization headers it was sent, and whether the Digest
     * checked out. Anything thrown on its thread — a JUnit assertion included — is captured in
     * [failure] so the test thread can report it instead of an opaque client-side EOF.
     */
    private inner class FakeCamera(
        val password: String?,
        val sendParams: Boolean = true,
        val sdpText: String = sdp,
        val announce: Boolean = false,
        val offerBasic: Boolean = false,
        val channel: Int = 0,
    ) : AutoCloseable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        val methods = CopyOnWriteArrayList<String>()
        val auths = CopyOnWriteArrayList<String>()
        @Volatile var digestOk = false
        @Volatile var failure: Throwable? = null
        val thread = Thread {
            runCatching {
                server.accept().use { s ->
                    s.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val out = s.getOutputStream()
                    fun reply(text: String) { out.write(text.toByteArray(Charsets.US_ASCII)); out.flush() }
                    var session: String? = null
                    while (true) {
                        val (method, uri, h) = readRequest(input) ?: break
                        methods += method
                        h["AUTHORIZATION"]?.let { auths += it }
                        val cseq = h["CSEQ"]
                        if (password != null && method != "OPTIONS") {
                            val ok = RtspAuth.authorized(h["AUTHORIZATION"], method, uri, nonce, password)
                            if (!ok) {
                                val basic = if (offerBasic) "WWW-Authenticate: Basic realm=\"$realm\"\r\n" else ""
                                reply("RTSP/1.0 401 Unauthorized\r\nCSeq: $cseq\r\nWWW-Authenticate: Digest realm=\"$realm\", nonce=\"$nonce\"\r\n$basic\r\n")
                                continue
                            }
                            digestOk = true
                        }
                        when (method) {
                            "DESCRIBE" -> reply("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Base: $uri/\r\nContent-Type: application/sdp\r\nContent-Length: ${sdpText.length}\r\n\r\n$sdpText")
                            "SETUP" -> { session = "ABCDEF12"; assertTrue(uri.endsWith("/track1")); assertTrue(h["TRANSPORT"]!!.contains("interleaved=0-1")); reply("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nSession: $session;timeout=60\r\nTransport: RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}\r\n\r\n") }
                            "PLAY" -> {
                                assertEquals(session, h["SESSION"]?.substringBefore(';'))
                                reply("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nSession: $session\r\n\r\n")
                                if (sendParams) {
                                    out.write(interleaved(channel + 1, byteArrayOf(0x80.toByte(), 200.toByte(), 0, 1, 0, 0, 0, 0)))   // RTCP SR stub, must be skipped
                                    out.write(interleaved(channel, rtp(byteArrayOf(0x65, 1, 2))))                                    // IDR slice, ignored
                                    // A server-initiated message at a frame boundary (Reolink announces):
                                    // the client must swallow it, not desynchronise on it.
                                    if (announce) reply("ANNOUNCE rtsp://cam/live RTSP/1.0\r\nCSeq: 99\r\nSession: $session\r\nContent-Length: 5\r\n\r\nhello")
                                    out.write(interleaved(channel, rtp(sps)))
                                    out.write(interleaved(channel, rtp(pps)))
                                    out.flush()
                                }
                            }
                            // The client hangs up right after writing TEARDOWN, so this last reply
                            // may race a closed socket: that is not a fake-camera failure.
                            "TEARDOWN" -> { runCatching { reply("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n") }; break }
                            else -> reply("RTSP/1.0 405 Method Not Allowed\r\nCSeq: $cseq\r\n\r\n")
                        }
                    }
                }
            }.onFailure { failure = it }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close() }
    }

    /** A camera that answers the first request with [reply] verbatim, then hangs up. */
    private inner class RawCamera(val reply: String) : AutoCloseable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        val thread = Thread {
            runCatching {
                server.accept().use { s ->
                    s.soTimeout = 5_000
                    readRequest(DataInputStream(BufferedInputStream(s.getInputStream())))
                    s.getOutputStream().write(reply.toByteArray(Charsets.US_ASCII))
                    s.getOutputStream().flush()
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close() }
    }

    /**
     * A camera that answers one byte every [gapMs] — each byte short of the deadline, the whole
     * response far past it. Pins that the deadline is per READ, not per message: a per-message
     * timeout is restarted by every byte and would never fire.
     */
    private inner class DribblingCamera(val gapMs: Long = 150, val bytes: Int = 60) : AutoCloseable {
        val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        val thread = Thread {
            runCatching {
                server.accept().use { s ->
                    s.soTimeout = 10_000
                    readRequest(DataInputStream(BufferedInputStream(s.getInputStream())))
                    val head = "RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Type: application/sdp\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)
                    val out = s.getOutputStream()
                    for (i in 0 until bytes) { out.write(head[i % head.size].toInt()); out.flush(); Thread.sleep(gapMs) }
                }
            }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close() }
    }

    @Test
    fun `fetches sps and pps through digest auth and tears down`() {
        FakeCamera("s3cret").use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/h264Preview_01_sub", user, "s3cret")
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Found)
            r as RtspParamSetFetcher.Result.Found
            assertArrayEquals(sps, r.sps); assertArrayEquals(pps, r.pps)
            assertEquals(sdp, r.sdp)
            cam.thread.join(3_000)
            assertFalse(cam.thread.isAlive)
            assertNull(cam.failure?.toString(), cam.failure)
            assertTrue(cam.digestOk)
            assertEquals(listOf("DESCRIBE", "DESCRIBE", "SETUP", "PLAY", "TEARDOWN"), cam.methods.toList())
        }
    }

    @Test
    fun `works without a password`() {
        FakeCamera(null).use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Found)
            assertNull(cam.failure?.toString(), cam.failure)
        }
    }

    @Test
    fun `the video channel the camera granted is the one that is sniffed`() {
        FakeCamera(null, channel = 2).use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Found)
            r as RtspParamSetFetcher.Result.Found
            assertArrayEquals(sps, r.sps); assertArrayEquals(pps, r.pps)
            assertNull(cam.failure?.toString(), cam.failure)
        }
    }

    @Test
    fun `a digest challenge is preferred over a basic one offered alongside it`() {
        FakeCamera("s3cret", offerBasic = true).use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", user, "s3cret")
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Found)
            assertNull(cam.failure?.toString(), cam.failure)
            // Never Basic: that would put the password on the wire although Digest was on offer.
            assertTrue(cam.auths.toString(), cam.auths.isNotEmpty() && cam.auths.all { it.startsWith("Digest") })
        }
    }

    @Test
    fun `an rtsp message between interleaved frames does not derail the sniffer`() {
        FakeCamera(null, announce = true).use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Found)
            r as RtspParamSetFetcher.Result.Found
            assertArrayEquals(sps, r.sps); assertArrayEquals(pps, r.pps)
            assertNull(cam.failure?.toString(), cam.failure)
        }
    }

    @Test
    fun `wrong password fails with an auth reason`() {
        FakeCamera("s3cret").use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", user, "nope")
            assertTrue(r is RtspParamSetFetcher.Result.Failed)
            assertTrue((r as RtspParamSetFetcher.Result.Failed).reason.contains("401"))
            assertNull(cam.failure?.toString(), cam.failure)
        }
    }

    @Test
    fun `no parameter sets before the deadline fails`() {
        FakeCamera(null, sendParams = false).use { cam ->
            val r = RtspParamSetFetcher(deadlineMs = 800).fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertEquals(RtspParamSetFetcher.Result.Failed("no SPS/PPS within 800ms"), r)
            assertNull(cam.failure?.toString(), cam.failure)
        }
    }

    @Test
    fun `a peer that dribbles a response one byte at a time cannot outlast the deadline`() {
        DribblingCamera().use { cam ->
            val started = System.currentTimeMillis()
            val r = RtspParamSetFetcher(deadlineMs = 600).fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            val elapsed = System.currentTimeMillis() - started
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Failed)
            // A per-MESSAGE timeout is re-armed by every dribbled byte and would run for 9 s.
            assertTrue("took ${elapsed}ms", elapsed < 3_000)
        }
    }

    @Test
    fun `refused connection fails instead of throwing`() {
        val port = ServerSocket(0).use { it.localPort }
        val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:$port/live", null, null)
        assertTrue(r is RtspParamSetFetcher.Result.Failed)
    }

    @Test
    fun `a truncated response body fails instead of throwing`() {
        RawCamera("RTSP/1.0 200 OK\r\nCSeq: 1\r\nContent-Type: application/sdp\r\nContent-Length: 4000\r\n\r\nv=0\r\n").use { cam ->
            val r = RtspParamSetFetcher(deadlineMs = 800).fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Failed)
        }
    }

    @Test
    fun `a garbage status line fails instead of throwing`() {
        RawCamera("i am not an rtsp server\r\n\r\n").use { cam ->
            val r = RtspParamSetFetcher(deadlineMs = 800).fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertTrue(r.toString(), r is RtspParamSetFetcher.Result.Failed)
        }
    }

    @Test
    fun `a bad url fails instead of throwing`() {
        val r = RtspParamSetFetcher().fetch("not a url at all", null, null)
        assertEquals(RtspParamSetFetcher.Result.Failed("bad url"), r)
    }

    @Test
    fun `no failure reason carries the credentials or the url`() {
        FakeCamera("s3cret").use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", user, "nope")
            val reason = (r as RtspParamSetFetcher.Result.Failed).reason
            assertTrue(reason, !reason.contains("nope") && !reason.contains("rtsp://") && !reason.contains("${cam.port}"))
        }
    }

    @Test
    fun `a repeated header keeps the first value so digest is not downgraded`() {
        val head = "RTSP/1.0 401 Unauthorized\r\nCSeq: 1\r\nWWW-Authenticate: Digest realm=\"r\", nonce=\"n\"\r\nWWW-Authenticate: Basic realm=\"r\"\r\n\r\n"
        val resp = RtspClientMessages.readResponse(DataInputStream(head.byteInputStream(Charsets.US_ASCII)))!!
        assertEquals(401, resp.status)
        assertTrue(resp.headers["WWW-AUTHENTICATE"]!!.startsWith("Digest"))
        assertTrue(RtspClientMessages.authorization(resp.headers["WWW-AUTHENTICATE"], "DESCRIBE", "rtsp://cam/s", "admin", "pw")!!.startsWith("Digest"))
    }

    @Test
    fun `control url resolution follows content-base then falls back to the request url`() {
        val base = "rtsp://cam:554/h264Preview_01_sub/"
        assertEquals("rtsp://cam:554/h264Preview_01_sub/track1", RtspClientMessages.videoControlUrl(sdp, "rtsp://cam:554/h264Preview_01_sub", base))
        assertEquals("rtsp://cam:554/h264Preview_01_sub/track1", RtspClientMessages.videoControlUrl(sdp, "rtsp://cam:554/h264Preview_01_sub", null))
        assertEquals("rtsp://x/abs", RtspClientMessages.videoControlUrl(sdp.replace("a=control:track1", "a=control:rtsp://x/abs"), "rtsp://cam/s", base))
        // A host-absolute control is resolved against the host, not appended to the base path.
        assertEquals("rtsp://cam:554/track1", RtspClientMessages.videoControlUrl(sdp.replace("a=control:track1", "a=control:/track1"), "rtsp://cam:554/h264Preview_01_sub", base))
        assertEquals("rtsp://cam/s", RtspClientMessages.videoControlUrl("v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n", "rtsp://cam/s", null))
    }

    @Test
    fun `the H264 section is chosen even when an H265 section comes first, and none means null`() {
        val twoVideo = "v=0\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=control:trackA\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:trackB\r\n"
        assertEquals("rtsp://cam/s/trackB", RtspClientMessages.videoControlUrl(twoVideo, "rtsp://cam/s", null))
        assertNull(RtspClientMessages.videoControlUrl("v=0\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=control:trackA\r\n", "rtsp://cam/s", null))
    }

    @Test
    fun `an H265-only camera fails fast without a SETUP`() {
        val h265 = "v=0\r\na=control:*\r\nm=video 0 RTP/AVP 98\r\na=rtpmap:98 H265/90000\r\na=control:track1\r\n"
        FakeCamera(null, sdpText = h265).use { cam ->
            val r = RtspParamSetFetcher().fetch("rtsp://127.0.0.1:${cam.port}/live", null, null)
            assertEquals(RtspParamSetFetcher.Result.Failed("no H.264 video track"), r)
            cam.thread.join(3_000)
            assertFalse(cam.thread.isAlive)
            assertNull(cam.failure?.toString(), cam.failure)
            assertEquals(listOf("DESCRIBE"), cam.methods.toList())
        }
    }

    @Test
    fun `authorization header builds a digest the server accepts`() {
        val challenge = "Digest realm=\"$realm\", nonce=\"$nonce\""
        val header = RtspClientMessages.authorization(challenge, "DESCRIBE", "rtsp://cam/s", user, "pw")!!
        assertTrue(RtspAuth.authorized(header, "DESCRIBE", "rtsp://cam/s", nonce, "pw"))
        assertEquals("Basic YWRtaW46cHc=", RtspClientMessages.authorization("Basic realm=\"x\"", "DESCRIBE", "rtsp://cam/s", "admin", "pw"))
        assertEquals(null, RtspClientMessages.authorization(challenge, "DESCRIBE", "rtsp://cam/s", null, null))
    }

    @Test
    fun `digest works for a foreign realm and user (a real Reolink)`() {
        val header = RtspClientMessages.authorization("Digest realm=\"BC Streaming Media\", nonce=\"$nonce\"", "DESCRIBE", "rtsp://cam/s", "admin", "pw")!!
        val params = Regex("(\\w+)=\"([^\"]*)\"").findAll(header).associate { it.groupValues[1] to it.groupValues[2] }
        assertEquals("admin", params["username"]); assertEquals("BC Streaming Media", params["realm"]); assertEquals("rtsp://cam/s", params["uri"])
        assertEquals(RtspAuth.digestResponse("admin", "BC Streaming Media", "pw", "DESCRIBE", "rtsp://cam/s", nonce), params["response"])
    }
}
