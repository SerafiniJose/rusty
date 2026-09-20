package dev.rusty.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SdpRepairProxyTest {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x33, 0xAC.toByte(), 0x15)
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0xB0.toByte())
    private val frame = byteArrayOf('$'.code.toByte(), 0, 0, 3, 9, 8, 7)

    private companion object {
        /** What the Reolink sends: an H.264 video section with no `a=fmtp` line at all. */
        const val BROKEN_SDP = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\na=control:track1\r\n"
        /** A camera that needs no repair: the proxy must pass this through byte for byte. */
        const val HEALTHY_SDP = "v=0\r\nm=video 0 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 packetization-mode=1;sprop-parameter-sets=Z0LgHtoCgPRA,aM4wpIA=\r\na=control:track1\r\n"
        const val FIXED_FMTP =
            "a=fmtp:96 packetization-mode=1;profile-level-id=640033;sprop-parameter-sets=Z2QAM6wV,aO48sA=="
        /** The camera names ITSELF here, never the proxy: media3 must receive it untouched. */
        const val CONTENT_BASE = "rtsp://192.0.2.7/h264Preview_01_sub/"
        const val PARAMETERS_BODY = "position: 12.500\r\nscale: 1.0\r\n"
    }

    /**
     * A fake camera: records the request head it saw, answers DESCRIBE with [sdpText] (plus a
     * `Content-Base` of its own address), GET_PARAMETER with a non-SDP body, and after PLAY writes
     * one interleaved frame followed by a server-side request. With [challengeRealm] it answers
     * every request with a Digest challenge instead; with [truncateHead] it cuts a response head in
     * half and hangs up. Connections are served concurrently, so a client that reconnects does not
     * have to wait for the previous relay to be torn down.
     */
    private class Upstream(
        val sdpText: String = BROKEN_SDP,
        val challengeRealm: String? = null,
        val truncateHead: Boolean = false,
    ) : AutoCloseable {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        @Volatile var lastRequestLine = ""
        @Volatile var lastHead: List<String> = emptyList()
        @Volatile var failure: Throwable? = null
        /** Connections this fake has seen through to their end — i.e. relays the proxy tore down. */
        val finished = AtomicInteger()
        private val live = CopyOnWriteArrayList<Socket>()

        init {
            Thread {
                runCatching {
                    while (true) {
                        val s = server.accept()
                        live.add(s)
                        Thread { serve(s) }.apply { isDaemon = true; start() }
                    }
                }
            }.apply { isDaemon = true; start() }
        }

        private fun serve(socket: Socket) {
            try {
                socket.use { s ->
                    val input = DataInputStream(BufferedInputStream(s.getInputStream()))
                    val out = s.getOutputStream()
                    while (true) {
                        val head = RtspClientMessages.readHead(input) ?: break
                        lastRequestLine = head[0]
                        lastHead = head
                        val cseq = head.first { it.startsWith("CSeq", true) }.substringAfter(':').trim()
                        val method = head[0].substringBefore(' ')
                        if (truncateHead) {
                            // A head with no blank line and no body: EOF lands mid-message.
                            out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Ty".toByteArray())
                            out.flush()
                            break
                        }
                        if (challengeRealm != null) {
                            out.write(
                                ("RTSP/1.0 401 Unauthorized\r\nCSeq: $cseq\r\n" +
                                    "WWW-Authenticate: Digest realm=\"$challengeRealm\", nonce=\"abc\"\r\n\r\n")
                                    .toByteArray(Charsets.ISO_8859_1)
                            )
                            out.flush()
                            continue
                        }
                        when (method) {
                            "DESCRIBE" -> out.write(
                                ("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Type: application/sdp\r\n" +
                                    "Content-Base: $CONTENT_BASE\r\nContent-Length: ${sdpText.length}\r\n\r\n$sdpText")
                                    .toByteArray()
                            )
                            "GET_PARAMETER" -> out.write(
                                ("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nContent-Type: text/parameters\r\n" +
                                    "Content-Length: ${PARAMETERS_BODY.length}\r\n\r\n$PARAMETERS_BODY").toByteArray()
                            )
                            "PLAY" -> {
                                out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\nSession: S1\r\n\r\n".toByteArray())
                                out.write(byteArrayOf('$'.code.toByte(), 0, 0, 3, 9, 8, 7))
                                out.write("GET_PARAMETER rtsp://x RTSP/1.0\r\nCSeq: 99\r\n\r\n".toByteArray())
                            }
                            else -> out.write("RTSP/1.0 200 OK\r\nCSeq: $cseq\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            } catch (t: Throwable) {
                if (!server.isClosed) failure = t
            } finally {
                finished.incrementAndGet()
            }
        }

        override fun close() {
            server.close()
            live.forEach { runCatching { it.close() } }
        }
    }

    /** Accepts as many connections as the proxy opens and says nothing: only the socket count matters. */
    private class SilentUpstream : AutoCloseable {
        val server = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        val port get() = server.localPort
        val accepted = CopyOnWriteArrayList<Socket>()
        init {
            Thread {
                runCatching { while (true) accepted.add(server.accept()) }
            }.apply { isDaemon = true; start() }
        }
        override fun close() {
            server.close()
            accepted.forEach { runCatching { it.close() } }
        }
    }

    private fun Socket.send(text: String) { getOutputStream().write(text.toByteArray()); getOutputStream().flush() }

    // Proxy threads are named, but the JVM is shared with every other test class in this Gradle
    // fork (Task 9 builds real proxies too), so only threads that appeared DURING this test count.
    private var preexistingThreads: Set<Thread> = emptySet()

    @Before
    fun snapshotThreads() { preexistingThreads = proxyThreads() }

    private fun proxyThreads(): Set<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("sdp-proxy") }.toSet()

    private fun newProxyThreads(): List<String> = (proxyThreads() - preexistingThreads).map { it.name }.sorted()

    /** Fails unless every accept/relay thread THIS test started has ended. */
    private fun assertNoProxyThreadsLeft() {
        val deadline = System.currentTimeMillis() + 5_000
        var live = newProxyThreads()
        while (live.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
            live = newProxyThreads()
        }
        assertEquals(emptyList<String>(), live)
    }

    /** EOF, or a reset if the proxy closed with our bytes still unread — either is "closed". */
    private fun assertClosed(c: Socket) {
        val outcome = runCatching { c.getInputStream().read() }
        assertTrue(outcome.toString(), outcome.isFailure || outcome.getOrNull() == -1)
    }

    private fun assertStillOpen(c: Socket) {
        c.soTimeout = 250
        val outcome = runCatching { c.getInputStream().read() }
        assertTrue(outcome.toString(), outcome.exceptionOrNull() is SocketTimeoutException)
    }

    @Test
    fun `describe response gains the fmtp line and a correct content length`() {
        Upstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start(); assertNotNull(port)
                assertEquals(port, proxy.port)
                Socket("127.0.0.1", port!!).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("DESCRIBE rtsp://127.0.0.1:$port/h264Preview_01_sub RTSP/1.0\r\nCSeq: 1\r\nAccept: application/sdp\r\n\r\n")
                    val r = RtspClientMessages.readResponse(input)!!
                    assertEquals(200, r.status)
                    val body = String(r.body)
                    assertEquals(r.body.size.toString(), r.headers["CONTENT-LENGTH"])
                    assertTrue(body, body.contains("\r\n$FIXED_FMTP\r\n"))
                    assertNull(SdpRepair.missingH264Fmtp(body))
                    // The camera's own Content-Base survives the rewritten head untouched.
                    assertEquals(CONTENT_BASE, r.headers["CONTENT-BASE"])
                    // The request line reached the camera untouched (127.0.0.1 host and all).
                    assertEquals("DESCRIBE rtsp://127.0.0.1:$port/h264Preview_01_sub RTSP/1.0", up.lastRequestLine)
                }
            }
            assertNull(up.failure)
        }
    }

    @Test
    fun `after play, interleaved frames and server requests pass through verbatim`() {
        Upstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start()!!
                Socket("127.0.0.1", port).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("PLAY rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 2\r\nSession: S1\r\n\r\n")
                    assertEquals(200, RtspClientMessages.readResponse(input)!!.status)
                    val got = ByteArray(frame.size); input.readFully(got)
                    assertArrayEquals(frame, got)
                    val head = RtspClientMessages.readHead(input)!!
                    assertEquals("GET_PARAMETER rtsp://x RTSP/1.0", head[0])
                }
            }
            assertNull(up.failure)
        }
    }

    @Test
    fun `an sdp that already has its fmtp is forwarded untouched`() {
        Upstream(HEALTHY_SDP).use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start()!!
                Socket("127.0.0.1", port).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("DESCRIBE rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                    val r = RtspClientMessages.readResponse(input)!!
                    assertEquals(HEALTHY_SDP, String(r.body))
                    assertEquals(HEALTHY_SDP.length.toString(), r.headers["CONTENT-LENGTH"])
                }
            }
            assertNull(up.failure)
        }
    }

    @Test
    fun `a body that is not sdp is forwarded intact`() {
        Upstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start()!!
                Socket("127.0.0.1", port).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("GET_PARAMETER rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 4\r\nSession: S1\r\n\r\n")
                    val r = RtspClientMessages.readResponse(input)!!
                    assertEquals("text/parameters", r.headers["CONTENT-TYPE"])
                    assertEquals(PARAMETERS_BODY, String(r.body))
                    assertEquals(PARAMETERS_BODY.length.toString(), r.headers["CONTENT-LENGTH"])
                }
            }
            assertNull(up.failure)
        }
    }

    @Test
    fun `the request head reaches the camera byte for byte, authorization included`() {
        Upstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start()!!
                val auth = "Digest username=\"admin\", realm=\"rtsp\", nonce=\"abc\", " +
                    "uri=\"rtsp://127.0.0.1:$port/x\", response=\"deadbeef\""
                Socket("127.0.0.1", port).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("OPTIONS rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 1\r\nAuthorization: $auth\r\nUser-Agent: media3\r\n\r\n")
                    assertEquals(200, RtspClientMessages.readResponse(input)!!.status)
                    assertEquals(
                        listOf("OPTIONS rtsp://127.0.0.1:$port/x RTSP/1.0", "CSeq: 1", "Authorization: $auth", "User-Agent: media3"),
                        up.lastHead,
                    )
                }
            }
            assertNull(up.failure)
        }
    }

    @Test
    fun `a non-ascii digest realm survives the head rewrite`() {
        // RTSP header text is latin-1: re-encoding the head as ASCII would turn the 0xE9 into '?',
        // media3 would hash the mangled realm and Digest auth would fail with no visible cause.
        val realm = "café"
        Upstream(challengeRealm = realm).use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start()!!
                Socket("127.0.0.1", port).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("DESCRIBE rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                    val r = RtspClientMessages.readResponse(input)!!
                    assertEquals(401, r.status)
                    assertEquals("Digest realm=\"$realm\", nonce=\"abc\"", r.headers["WWW-AUTHENTICATE"])
                }
            }
            assertNull(up.failure)
        }
    }

    @Test
    fun `a head cut short upstream is never forwarded as a whole message`() {
        Upstream(truncateHead = true).use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val port = proxy.start()!!
                Socket("127.0.0.1", port).use { c ->
                    c.soTimeout = 5_000
                    val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                    c.send("DESCRIBE rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                    // Nothing at all, not a fabricated "RTSP/1.0 200 OK" with a blank line bolted on.
                    assertNull(runCatching { RtspClientMessages.readResponse(input) }.getOrNull())
                }
            }
        }
    }

    @Test
    fun `an unreachable upstream closes the client connection`() {
        val dead = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        SdpRepairProxy("127.0.0.1", dead, sps, pps).use { proxy ->
            val port = proxy.start()!!
            Socket("127.0.0.1", port).use { c ->
                c.soTimeout = 5_000
                c.send("OPTIONS rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                assertClosed(c)
            }
        }
    }

    @Test
    fun `a failed upstream connect frees its relay slot and leaves no thread behind`() {
        val log = CopyOnWriteArrayList<String>()
        val dead = ServerSocket(0, 0, InetAddress.getLoopbackAddress()).use { it.localPort }
        SdpRepairProxy("127.0.0.1", dead, sps, pps, log = { log.add(it) }).use { proxy ->
            val port = proxy.start()!!
            // Six is more than the four-connection cap: had the dead connect leaked its sockets,
            // the fifth attempt would be refused at capacity instead of relayed and closed.
            repeat(6) {
                Socket("127.0.0.1", port).use { c -> c.soTimeout = 5_000; assertClosed(c) }
            }
            assertEquals(emptyList<String>(), log.filter { it.contains("capacity") })
        }
        assertNoProxyThreadsLeft()
    }

    @Test
    fun `a client that reconnects gets its relay slot back at once`() {
        val log = CopyOnWriteArrayList<String>()
        Upstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps, log = { log.add(it) }).use { proxy ->
                val port = proxy.start()!!
                // media3's retry shape: six sequential sessions against a cap of four. A slot that
                // lingers after the client hangs up would refuse the fifth.
                for (n in 1..6) {
                    Socket("127.0.0.1", port).use { c ->
                        c.soTimeout = 5_000
                        val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                        c.send("DESCRIBE rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: $n\r\n\r\n")
                        assertEquals(200, RtspClientMessages.readResponse(input)!!.status)
                    }
                    // Teardown must be immediate, not "within a second": the camera-side socket is
                    // closed as soon as the client goes away.
                    val deadline = System.currentTimeMillis() + 500
                    while (up.finished.get() < n && System.currentTimeMillis() < deadline) Thread.sleep(5)
                    assertEquals("session $n was not torn down promptly", n, up.finished.get())
                }
                assertEquals(emptyList<String>(), log.filter { it.contains("capacity") })
            }
        }
        assertNoProxyThreadsLeft()
    }

    @Test
    fun `a fifth simultaneous connection is refused`() {
        val log = CopyOnWriteArrayList<String>()
        SilentUpstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps, log = { log.add(it) }).use { proxy ->
                val port = proxy.start()!!
                val open = (1..4).map { Socket("127.0.0.1", port) }
                try {
                    val deadline = System.currentTimeMillis() + 5_000
                    while (up.accepted.size < 4 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                    assertEquals(4, up.accepted.size)
                    Socket("127.0.0.1", port).use { fifth ->
                        fifth.soTimeout = 5_000
                        assertClosed(fifth)
                    }
                    assertEquals(4, up.accepted.size)
                    assertTrue(log.toString(), log.any { it.contains("capacity") })
                    open.forEach { assertStillOpen(it) }
                } finally {
                    open.forEach { runCatching { it.close() } }
                }
            }
        }
        assertNoProxyThreadsLeft()
    }

    @Test
    fun `close mid-relay drops the client and ends every proxy thread`() {
        Upstream().use { up ->
            val proxy = SdpRepairProxy("127.0.0.1", up.port, sps, pps)
            val port = proxy.start()!!
            Socket("127.0.0.1", port).use { c ->
                c.soTimeout = 5_000
                val input = DataInputStream(BufferedInputStream(c.getInputStream()))
                c.send("DESCRIBE rtsp://127.0.0.1:$port/x RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                assertEquals(200, RtspClientMessages.readResponse(input)!!.status)
                assertTrue(newProxyThreads().toString(), newProxyThreads().isNotEmpty())
                proxy.close()
                proxy.close()
                assertClosed(c)
                assertNull(proxy.port)
            }
            assertNoProxyThreadsLeft()
        }
    }

    @Test
    fun `close stops accepting`() {
        val proxy = SdpRepairProxy("127.0.0.1", 1, sps, pps)
        val port = proxy.start()!!
        proxy.close(); proxy.close()
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        assertNoProxyThreadsLeft()
    }

    @Test
    fun `starting twice binds one listener and one accept thread`() {
        Upstream().use { up ->
            SdpRepairProxy("127.0.0.1", up.port, sps, pps).use { proxy ->
                val first = proxy.start()!!
                // A second bind would be invisible to close(): its socket and its accept thread
                // would outlive the proxy.
                assertEquals(first, proxy.start())
                assertEquals(first, proxy.port)
                assertEquals(listOf("sdp-proxy-accept"), newProxyThreads())
            }
        }
        assertNoProxyThreadsLeft()
    }

    @Test
    fun `starting after close binds nothing`() {
        val proxy = SdpRepairProxy("127.0.0.1", 1, sps, pps)
        proxy.close()
        assertNull(proxy.start())
        assertNull(proxy.port)
        assertEquals(emptyList<String>(), newProxyThreads())

        val restarted = SdpRepairProxy("127.0.0.1", 1, sps, pps)
        val port = restarted.start()!!
        restarted.close()
        assertNull(restarted.start())
        assertNull(restarted.port)
        assertTrue(runCatching { Socket("127.0.0.1", port).close() }.isFailure)
        assertNoProxyThreadsLeft()
    }

    @Test
    fun `start and close stay open so a never-binding fake can replace them`() {
        // Task 9's LiveStreamRepairer test substitutes exactly this shape; Kotlin members are final
        // unless marked open, so the substitution must keep compiling.
        var closes = 0
        val fake = object : SdpRepairProxy("127.0.0.1", 1, sps, pps) {
            override fun start(): Int? = null
            override fun close() { closes++ }
        }
        assertNull(fake.start())
        assertNull(fake.port)
        fake.close()
        assertEquals(1, closes)
    }

    /**
     * Pins the bind contract the on-device fix depends on: the listener must be on the IPv4
     * loopback SPECIFICALLY, because [LiveStreamRepairer]'s proxy URL is the literal
     * `rtsp://127.0.0.1:<port>/…`.
     *
     * Honest scope: this test CANNOT go red on a JVM host. `InetAddress.getLoopbackAddress()` —
     * the old bind address, and the bug — already returns 127.0.0.1 on Linux/macOS; it is on
     * ANDROID that it returns ::1, which put the listener on [::1]:port and made media3's dial of
     * 127.0.0.1 fail with ECONNREFUSED forever. That was proved on the device (Echo Show,
     * Android 11: /proc/net/tcp6 held the ::1 LISTEN row, /proc/net/tcp was empty), not here.
     * What this test does buy is a guard against anyone reintroducing a bind address that is not
     * IPv4 on any platform, and it asserts the URL the repairer builds names the address we bound.
     */
    @Test
    fun `the listener is reachable at the IPv4 loopback that the proxy url names`() {
        // Dialled by literal bytes, never by a name or a platform "loopback" alias, so this
        // connect succeeds only against a listener that really is on 127.0.0.1.
        val ipv4Loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        SdpRepairProxy("127.0.0.1", 1, sps, pps).use { proxy ->
            val port = proxy.start()!!
            Socket(ipv4Loopback, port).use { c ->
                assertEquals("127.0.0.1", c.inetAddress.hostAddress)
            }
            assertEquals(
                "rtsp://127.0.0.1:$port/h264Preview_01_sub",
                LiveStreamRepairer.proxyUrlFor("rtsp://camera.local:554/h264Preview_01_sub", port),
            )
        }
        assertNoProxyThreadsLeft()
    }
}
