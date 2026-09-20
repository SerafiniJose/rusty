package dev.rusty.app.renderer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket

/**
 * Minimal [RendererRuntime] stand-in: these tests only drive bind/teardown of the accept
 * loop, never a request, so every member is an inert stub. (The fake in
 * RendererHttpProtocolTest is file-private, hence a second one here.)
 */
private class FakePortTestRuntime : RendererRuntime {
    override val friendlyName = "Rusty Media Player"
    override val udn = "uuid:test-udn"
    override val configId: Long = 1
    override val volumeFixed = false
    override val rendererState = RendererState()
    override fun dispatch(event: RendererEvent) {}
    override fun positionMs() = 0L
    override fun spotifySnapshot() = false to 0L
    override fun mixMode() = SpotifyInterruption.PAUSE
    override fun fadeMs() = 0L
    override fun volumePercent() = 40
    override fun setVolumePercent(v: Int) {}
    override fun muted() = false
    override fun setMuted(m: Boolean) {}
    override fun onVolumeChanged() {}
    private val table = GenaSubscriptions({ 0L }, { "uuid:sid-1" })
    override fun gena() = table
    override fun onSubscribed(sub: GenaSubscriptions.Sub) {}
}

/** Real loopback sockets, no Android APIs — runs on the JVM. */
class RendererHttpServerPortTest {

    private val held = mutableListOf<ServerSocket>()
    private val servers = mutableListOf<RendererHttpServer>()

    private fun hold(port: Int) { held += ServerSocket(port) }

    /**
     * The ladder is 49152..49161, which sits inside Linux's ephemeral range (32768..60999), so a
     * process that has nothing to do with this test — classically a Gradle test worker left over
     * from an interrupted build — can be holding a rung. Nothing about the renderer is then under
     * test, and the failure it produces ("expected 49152 but was 49154") names neither the cause
     * nor the cure, so say both and skip rather than burn a CI re-run on someone else's socket.
     */
    @Before
    fun requireTheLadderToBeFree() {
        val taken = (RendererPortPicker.FIRST..RendererPortPicker.LAST)
            .filter { port -> runCatching { ServerSocket(port).close() }.isFailure }
        assumeTrue(
            "ports $taken are held by another process on this machine, not by the renderer; " +
                "`./gradlew --stop` clears a leftover test worker",
            taken.isEmpty(),
        )
    }

    @After
    fun tearDown() {
        servers.forEach { it.stop() }
        held.forEach { runCatching { it.close() } }
    }

    private fun newServer(): RendererHttpServer =
        RendererHttpServer(FakePortTestRuntime()).also { servers += it }

    @Test
    fun `binds 49152 by default`() {
        assertEquals(49152, newServer().start(preferredPort = null))
    }

    @Test
    fun `walks the ladder when 49152 is taken`() {
        hold(49152)
        assertEquals(49153, newServer().start(preferredPort = null))
    }

    @Test
    fun `prefers the persisted port`() {
        assertEquals(49157, newServer().start(preferredPort = 49157))
    }

    @Test
    fun `falls back to the ladder when the persisted port is taken`() {
        hold(49157)
        assertEquals(49152, newServer().start(preferredPort = 49157))
    }

    @Test
    fun `falls back to an ephemeral port when the whole ladder is taken`() {
        (49152..49161).forEach { hold(it) }
        val port = newServer().start(preferredPort = null)
        assertTrue("expected ephemeral, got $port", port > 0 && port !in 49152..49161)
    }

    /**
     * `ServerSocket.close()` does NOT release the port while another thread sits in `accept()`:
     * the JDK defers the real close until the blocked accepter returns, so a [stop] that does not
     * wait for its accept thread hands back a port that is still bound and still accepting. On a
     * restart the picker then walks past the renderer's sticky port — a LOCATION URL a user typed
     * into Home Assistant silently moves to 49153 — and, in this very class, the next test's bind
     * lands one rung further up the ladder.
     */
    @Test
    fun `a stopped server has really released its port, so a restart keeps it`() {
        val first = newServer()
        val port = first.start(preferredPort = null)
        first.stop()
        assertEquals(port, newServer().start(preferredPort = port))
    }
}
