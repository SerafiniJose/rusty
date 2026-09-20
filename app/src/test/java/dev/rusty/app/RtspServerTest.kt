package dev.rusty.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspServerTest {
    private val encoding = CameraShareSettings.Encoding(
        CameraShareSettings.Resolution.MEDIUM, CameraShareSettings.Tier.GOOD, CameraShareSettings.FrameRate.FPS_15,
    )

    private class FakePipeline : CapturePipeline {
        @Volatile var listener: PipelineListener? = null
        override fun start(
            lens: CameraShareSettings.Lens,
            encoding: CameraShareSettings.Encoding,
            listener: PipelineListener,
        ): PipelineStart {
            this.listener = listener; listener.onCodecConfig(byteArrayOf(0x67, 1), byteArrayOf(0x68, 2)); return PipelineStart.Ok
        }
        override fun stop() { listener = null }
        override fun requestKeyframe() {}
        override fun grabJpeg(): ByteArray? = null
    }

    private fun freePort(): Int = java.net.ServerSocket(0).use { it.localPort }

    /** One buffered reader per socket for the whole test: a fresh BufferedInputStream per response
     *  could read ahead and strand interleaved frames in a discarded buffer. */
    private class Conn(val socket: Socket) : AutoCloseable {
        val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
        override fun close() = socket.close()
    }

    private fun Conn.rtsp(request: String): Pair<Int, Map<String, String>> {
        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().flush()
        val lines = mutableListOf<String>()
        while (true) {
            val sb = StringBuilder()
            while (true) { val c = input.read(); if (c < 0 || c == '\n'.code) break; if (c != '\r'.code) sb.append(c.toChar()) }
            if (sb.isEmpty()) break
            lines.add(sb.toString())
        }
        val status = lines[0].split(' ')[1].toInt()
        val headers = lines.drop(1).associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':').trim() }
        headers["CONTENT-LENGTH"]?.toInt()?.let { input.readFully(ByteArray(it)) }
        return status to headers
    }

    @Test fun `full session over loopback delivers interleaved rtp frames`() {
        val pipeline = FakePipeline()
        val scheduler = DelayScheduler { _, _ -> {} }
        val hub = CameraShareHub(pipeline, { CameraShareSettings.Lens.FRONT }, { encoding }, scheduler, {}, clock = { 0L })
        val port = freePort()
        val server = RtspServer(port, hub, { null })
        assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                val base = "rtsp://127.0.0.1:$port/live"
                assertEquals(200, s.rtsp("OPTIONS $base RTSP/1.0\r\nCSeq: 1\r\n\r\n").first)
                assertEquals(200, s.rtsp("DESCRIBE $base RTSP/1.0\r\nCSeq: 2\r\nAccept: application/sdp\r\n\r\n").first)
                val (st, h) = s.rtsp("SETUP $base/track0 RTSP/1.0\r\nCSeq: 3\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                assertEquals(200, st)
                val session = h["SESSION"]!!.substringBefore(';')
                assertEquals(200, s.rtsp("PLAY $base RTSP/1.0\r\nCSeq: 4\r\nSession: $session\r\n\r\n").first)
                // Wait until the hub sees the viewer, then push one keyframe through the fake pipeline.
                val deadline = System.currentTimeMillis() + 2_000
                while (hub.viewerCount() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
                assertEquals(1, hub.viewerCount())
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x65, 7, 7)), 5_000_000, keyframe = true))
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x41, 8)), 6_000_000, keyframe = false))
                val input = s.input
                fun ts(pkt: ByteArray) = ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
                fun seq(pkt: ByteArray) = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
                // First AU: 3 NALs (sps, pps, idr) -> 3 interleaved frames, timestamps rebased to 0, seq from 0
                repeat(3) { i ->
                    assertEquals('$'.code, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                    val len = input.readUnsignedShort(); val pkt = ByteArray(len); input.readFully(pkt)
                    assertEquals(0x80, pkt[0].toInt() and 0xFF)
                    assertEquals(i == 2, (pkt[1].toInt() and 0x80) != 0)
                    assertEquals(0L, ts(pkt)); assertEquals(i, seq(pkt))
                }
                // Second AU, one second later: 1 frame at 90 kHz * 1 s
                assertEquals('$'.code, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
                val len2 = input.readUnsignedShort(); val pkt2 = ByteArray(len2); input.readFully(pkt2)
                assertEquals(90_000L, ts(pkt2)); assertEquals(3, seq(pkt2))
                assertEquals(200, s.rtsp("TEARDOWN $base RTSP/1.0\r\nCSeq: 5\r\nSession: $session\r\n\r\n").first)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (hub.viewerCount() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(0, hub.viewerCount())
        } finally {
            server.stop()
        }
    }

    @Test fun `client rtcp frames are skipped and a dropped socket detaches`() {
        val pipeline = FakePipeline()
        val hub = CameraShareHub(pipeline, { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                val base = "rtsp://127.0.0.1:$port/live"
                s.rtsp("DESCRIBE $base RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                val session = s.rtsp("SETUP $base/track0 RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n").second["SESSION"]!!.substringBefore(';')
                s.rtsp("PLAY $base RTSP/1.0\r\nCSeq: 3\r\nSession: $session\r\n\r\n")
                // An interleaved RTCP receiver report on channel 1, then a keepalive that must still be answered.
                s.socket.getOutputStream().write(byteArrayOf('$'.code.toByte(), 1, 0, 2, 0x11, 0x22)); s.socket.getOutputStream().flush()
                assertEquals(200, s.rtsp("GET_PARAMETER $base RTSP/1.0\r\nCSeq: 4\r\nSession: $session\r\n\r\n").first)
            }
            val deadline = System.currentTimeMillis() + 2_000
            while (hub.viewerCount() != 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(0, hub.viewerCount()); assertEquals(0, server.clientCount())
        } finally { server.stop() }
    }

    @Test fun `start fails when the port is busy`() {
        java.net.ServerSocket(0).use { taken ->
            val hub = CameraShareHub(FakePipeline(), { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
            assertEquals(false, RtspServer(taken.localPort, hub, { null }).start())
        }
    }

    // -- the rest ------------------------------------------------------------------------------

    private fun waitUntil(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 2_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return condition()
    }

    /** True once the peer is gone, whether it hung up cleanly (EOF) or reset the connection. A
     *  read timeout is deliberately NOT a close: a connection that is merely silent is the failure
     *  these tests are looking for. */
    private fun Conn.isClosed(): Boolean = try {
        input.read() < 0
    } catch (_: java.net.SocketTimeoutException) {
        false
    } catch (_: java.io.IOException) {
        true
    }

    @Test fun `an oversized request head closes the connection`() {
        val hub = CameraShareHub(FakePipeline(), { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                // A head that never reaches its blank line. Unbounded buffering here would be a
                // one-connection memory exhaustion, so the server has to give up and hang up.
                val out = s.socket.getOutputStream()
                try {
                    out.write("OPTIONS rtsp://127.0.0.1:$port/live RTSP/1.0\r\nCSeq: 1\r\n".toByteArray(Charsets.US_ASCII))
                    repeat(64) { out.write(("X-Pad: " + "a".repeat(500) + "\r\n").toByteArray(Charsets.US_ASCII)); out.flush() }
                } catch (_: java.io.IOException) {
                    // The server hung up mid-write, which is the behaviour under test.
                }
                assertTrue(s.isClosed())
            }
            assertTrue(waitUntil { server.clientCount() == 0 })
        } finally { server.stop() }
    }

    @Test fun `a request without CSeq gets 400 and then EOF`() {
        val hub = CameraShareHub(FakePipeline(), { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                assertEquals(400, s.rtsp("OPTIONS rtsp://127.0.0.1:$port/live RTSP/1.0\r\n\r\n").first)
                assertTrue(s.isClosed())
            }
            assertTrue(waitUntil { server.clientCount() == 0 })
        } finally { server.stop() }
    }

    @Test fun `frames go out on the interleaved channel SETUP negotiated`() {
        val pipeline = FakePipeline()
        val hub = CameraShareHub(pipeline, { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                val base = "rtsp://127.0.0.1:$port/live"
                s.rtsp("DESCRIBE $base RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                val (st, h) = s.rtsp("SETUP $base/track0 RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;unicast;interleaved=2-3\r\n\r\n")
                assertEquals(200, st)
                assertEquals("RTP/AVP/TCP;unicast;interleaved=2-3", h["TRANSPORT"])
                val session = h["SESSION"]!!.substringBefore(';')
                assertEquals(200, s.rtsp("PLAY $base RTSP/1.0\r\nCSeq: 3\r\nSession: $session\r\n\r\n").first)
                assertTrue(waitUntil { hub.viewerCount() == 1 })
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x65, 7, 7)), 0, keyframe = true))
                // media3 demuxes on the channel it ASKED for and never reads the Transport header
                // back, so answering on a hardcoded 0 here would be a silent black screen.
                assertEquals('$'.code, s.input.readUnsignedByte())
                assertEquals(2, s.input.readUnsignedByte())
                val len = s.input.readUnsignedShort(); s.input.readFully(ByteArray(len))
            }
        } finally { server.stop() }
    }

    @Test fun `connections past the cap are refused and the accept loop survives`() {
        val hub = CameraShareHub(FakePipeline(), { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        val held = mutableListOf<Conn>()
        try {
            // Every connection costs two threads and 72 KB of buffers BEFORE any password is
            // checked, so an uncapped server is a one-command process kill from any LAN device.
            val cap = 8
            repeat(cap) { held.add(Conn(Socket("127.0.0.1", port))) }
            assertTrue(waitUntil { server.clientCount() == cap })
            Conn(Socket("127.0.0.1", port)).use { extra ->
                extra.socket.soTimeout = 5_000
                assertTrue(extra.isClosed())
            }
            assertEquals(cap, server.clientCount())
            // A refusal must not end the accept loop: freeing a slot lets the next viewer in.
            held.removeAt(0).close()
            assertTrue(waitUntil { server.clientCount() == cap - 1 })
            Conn(Socket("127.0.0.1", port)).use { next ->
                next.socket.soTimeout = 5_000
                assertEquals(200, next.rtsp("OPTIONS rtsp://127.0.0.1:$port/live RTSP/1.0\r\nCSeq: 1\r\n\r\n").first)
            }
        } finally {
            held.forEach { runCatching { it.close() } }
            server.stop()
        }
    }

    @Test fun `a backwards PTS rebases instead of wrapping the RTP timestamp`() {
        val pipeline = FakePipeline()
        val hub = CameraShareHub(pipeline, { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                val base = "rtsp://127.0.0.1:$port/live"
                s.rtsp("DESCRIBE $base RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                val session = s.rtsp("SETUP $base/track0 RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                    .second["SESSION"]!!.substringBefore(';')
                assertEquals(200, s.rtsp("PLAY $base RTSP/1.0\r\nCSeq: 3\r\nSession: $session\r\n\r\n").first)
                assertTrue(waitUntil { hub.viewerCount() == 1 })
                fun readTs(): Long {
                    assertEquals('$'.code, s.input.readUnsignedByte()); assertEquals(0, s.input.readUnsignedByte())
                    val len = s.input.readUnsignedShort(); val pkt = ByteArray(len); s.input.readFully(pkt)
                    return ((pkt[4].toLong() and 0xFF) shl 24) or ((pkt[5].toLong() and 0xFF) shl 16) or
                        ((pkt[6].toLong() and 0xFF) shl 8) or (pkt[7].toLong() and 0xFF)
                }
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x65, 7)), 5_000_000, keyframe = true))
                repeat(3) { assertEquals(0L, readTs()) }        // sps, pps, idr
                // The pipeline's clock restarts under us. Subtracting the old base would make the
                // difference hugely negative and wrap the 32-bit timestamp field.
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x41, 8)), 1_000_000, keyframe = false))
                assertEquals(0L, readTs())
                pipeline.listener!!.onAccessUnit(AccessUnit(listOf(byteArrayOf(0x41, 9)), 1_066_000, keyframe = false))
                assertEquals(5_940L, readTs())
            }
        } finally { server.stop() }
    }

    @Test fun `stop closes a live client and the hub loses the viewer`() {
        val pipeline = FakePipeline()
        val hub = CameraShareHub(pipeline, { CameraShareSettings.Lens.FRONT }, { encoding }, { _, _ -> {} }, {}, clock = { 0L })
        val port = freePort(); val server = RtspServer(port, hub, { null }); assertTrue(server.start())
        try {
            Conn(Socket("127.0.0.1", port)).use { s ->
                s.socket.soTimeout = 5_000
                val base = "rtsp://127.0.0.1:$port/live"
                s.rtsp("DESCRIBE $base RTSP/1.0\r\nCSeq: 1\r\n\r\n")
                val session = s.rtsp("SETUP $base/track0 RTSP/1.0\r\nCSeq: 2\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n\r\n")
                    .second["SESSION"]!!.substringBefore(';')
                assertEquals(200, s.rtsp("PLAY $base RTSP/1.0\r\nCSeq: 3\r\nSession: $session\r\n\r\n").first)
                assertTrue(waitUntil { hub.viewerCount() == 1 })
                assertEquals(1, server.clientCount())
                // Turning the share off must take the camera indicator with it: no viewer may
                // outlive stop(), least of all until its 120 s read timeout.
                server.stop()
                assertEquals(0, server.clientCount())
                assertEquals(0, hub.viewerCount())
                assertTrue(s.isClosed())
            }
        } finally { server.stop() }
    }
}
