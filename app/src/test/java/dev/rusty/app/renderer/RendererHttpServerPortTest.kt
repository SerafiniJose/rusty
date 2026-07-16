package dev.rusty.app.renderer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
}
