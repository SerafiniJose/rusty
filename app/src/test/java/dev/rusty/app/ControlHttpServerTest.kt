package dev.rusty.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.Socket

/** Minimal [ControlRuntime] stand-in exercised over real sockets; only [snapshot] is wired
 *  to be configurably slow/throwing since that's the one route these tests drive. (The fake in
 *  ControlProtocolTest is file-private, hence a second, differently-named one here — two
 *  same-named top-level classes in one package clash even when both are file-private.) */
private class FakeControlHttpRuntime : ControlRuntime {
    var snap = ControlSnapshot(
        deviceId = "abc", deviceName = "Rusty Speaker", version = "2.3.0",
        screen = ControlScreen(on = true, brightness = 80, mode = "system", writable = true, available = true),
        volume = ControlVolume(value = 47, fixed = false),
        playing = ControlPlaying(spotify = true, dlna = false),
        slideshowEnabled = true,
        panel = ControlPanel(
            active = ControlPanelId.SPOTIFY,
            available = ControlPanelId.values().toList(),
            lockscreen = ControlLockscreen(ScreensaverThemeId.CLOCK, ScreensaverThemeId.values().toList()),
        ),
        app = ControlApp(foreground = true, canBringForward = true),
    )

    /** Lets a test simulate a runtime failure (e.g. a prefs read blowing up) to prove the
     *  accept loop survives it — mirrors ControlProtocolTest's screenThrows. */
    var snapshotThrows: Throwable? = null

    override fun snapshot(): ControlSnapshot {
        snapshotThrows?.let { throw it }
        return snap
    }

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
        current = "2.3.0", status = "up_to_date", latest = null,
        install = InstallSnapshot(InstallPhase.IDLE, null, null),
    )
    override fun startUpdateInstall(): ControlInstallStart = ControlInstallStart.NO_UPDATE
}

/** Real loopback sockets, no Android APIs — runs on the JVM, mirroring
 *  RendererHttpServerPortTest's approach for the renderer's twin. Every server binds `start(0)`
 *  (an OS-assigned ephemeral port), so tests never collide with a real 8765 or each other. */
class ControlHttpServerTest {

    private val servers = mutableListOf<ControlHttpServer>()
    private val sockets = mutableListOf<Socket>()

    @After
    fun tearDown() {
        servers.forEach { runCatching { it.stop() } }
        sockets.forEach { runCatching { it.close() } }
    }

    private fun newServer(
        runtime: ControlRuntime = FakeControlHttpRuntime(),
        localHosts: () -> Set<String> = { setOf("localhost") },
    ): ControlHttpServer =
        ControlHttpServer(runtime, localHosts).also { servers += it }

    /** Sends a well-formed HTTP/1.1 request and returns the full response text, read until the
     *  peer closes the connection — every response here is `Connection: close`, so EOF marks
     *  the end of the response with no risk of hanging on a short read. */
    private fun sendRequest(
        port: Int,
        method: String = "GET",
        path: String = "/api/state",
        host: String? = "localhost",
        body: String = "",
    ): String {
        Socket("127.0.0.1", port).use { socket ->
            sockets += socket
            socket.soTimeout = 5_000
            val bodyBytes = body.toByteArray(Charsets.UTF_8)
            val sb = StringBuilder()
            sb.append(method).append(' ').append(path).append(" HTTP/1.1\r\n")
            if (host != null) sb.append("Host: ").append(host).append("\r\n")
            if (bodyBytes.isNotEmpty()) sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            sb.append("\r\n")
            socket.getOutputStream().write(sb.toString().toByteArray(Charsets.UTF_8))
            socket.getOutputStream().write(bodyBytes)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    /** Sends raw bytes that never form a valid HTTP request line (no space => `parts.size < 2` in
     *  [dev.rusty.app.renderer.RendererHttpProtocol.parseRequest]), so the server's first
     *  `readLine` deterministically yields a parse failure rather than relying on a timeout. */
    private fun sendGarbage(port: Int, raw: String): String {
        Socket("127.0.0.1", port).use { socket ->
            sockets += socket
            socket.soTimeout = 5_000
            socket.getOutputStream().write(raw.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    @Test fun `GET api state returns 200 with the snapshot JSON body`() {
        val rt = FakeControlHttpRuntime()
        val server = newServer(rt)
        val port = server.start(0)

        val response = sendRequest(port)

        assertTrue("expected a well-formed status line, got: $response", response.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("expected a Content-Length header, got: $response", response.contains("\r\nContent-Length: "))
        val body = response.substringAfter("\r\n\r\n")
        assertEquals(rt.snap.toJson(), body)
    }

    @Test fun `malformed request gets 400 rather than hanging`() {
        val server = newServer()
        val port = server.start(0)

        val response = sendGarbage(port, "NOTAREQUEST\r\n\r\n")

        assertTrue(response.startsWith("HTTP/1.1 400"))
    }

    @Test fun `foreign Host header gets 403 over the wire`() {
        val server = newServer(localHosts = { setOf("localhost") })
        val port = server.start(0)

        val response = sendRequest(port, host = "evil.example.com")

        assertTrue(response.startsWith("HTTP/1.1 403"))
    }

    @Test fun `localHostsProvider is consulted per request, not captured once at start`() {
        var current = setOf("localhost")
        val server = newServer(localHosts = { current })
        val port = server.start(0)

        val first = sendRequest(port, host = "localhost")
        assertTrue("expected 200 while localhost is allowed, got: $first", first.startsWith("HTTP/1.1 200"))

        current = setOf("otherhost")
        val second = sendRequest(port, host = "localhost")
        assertTrue("expected 403 once localhost is no longer allowed, got: $second", second.startsWith("HTTP/1.1 403"))
    }

    @Test fun `server survives a bad request and keeps serving afterwards`() {
        val server = newServer()
        val port = server.start(0)

        val bad = sendGarbage(port, "GARBAGE\r\n\r\n")
        assertTrue(bad.startsWith("HTTP/1.1 400"))

        val good = sendRequest(port)
        assertTrue("accept loop should still be serving after a bad request, got: $good", good.startsWith("HTTP/1.1 200"))
    }

    @Test fun `a throwing ControlRuntime does not kill the server`() {
        val rt = FakeControlHttpRuntime()
        rt.snapshotThrows = IllegalStateException("boom")
        val server = newServer(rt)
        val port = server.start(0)

        val first = sendRequest(port)
        assertTrue("ControlProtocol.route converts a throw to 500, got: $first", first.startsWith("HTTP/1.1 500"))

        rt.snapshotThrows = null
        val second = sendRequest(port)
        assertTrue("server should still serve after the earlier throw, got: $second", second.startsWith("HTTP/1.1 200"))
    }

    @Test fun `stop closes the listener and is safe to call twice`() {
        val server = newServer()
        val port = server.start(0)
        server.stop()

        try {
            Socket("127.0.0.1", port).use { sockets += it }
            fail("expected the connection attempt to fail once the listener is closed")
        } catch (_: IOException) {
            // expected: nothing is listening on `port` anymore
        }

        server.stop() // must not throw
    }

    @Test fun `start on an already-bound port throws IOException`() {
        val server1 = newServer()
        val boundPort = server1.start(0)

        val server2 = newServer()
        try {
            server2.start(boundPort)
            fail("expected IOException binding a port that's already in use")
        } catch (_: IOException) {
            // expected
        }
    }
}
