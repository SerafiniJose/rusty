package dev.rusty.app.renderer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpEndpointLifecycleTest {

    private val identity = SsdpIdentity("uuid:x", "http://1.2.3.4:49152/upnp/device.xml", bootId = 1, configId = 1)

    /**
     * Regression: a rename queued on the service's event executor can run AFTER teardown
     * (ExecutorService.shutdown() still drains queued tasks), and its announceNow() used to fall
     * into the "not running ⇒ start()" branch — resurrecting the multicast socket, the receive
     * thread and the periodic ssdp:alive announcer for a renderer whose HTTP server was gone.
     * stop() is terminal, so a post-teardown announceNow() must be a no-op. (No socket is opened
     * on the correct path, which is what keeps this a JVM test.)
     */
    @Test
    fun `announceNow after stop does not resurrect the endpoint`() {
        val endpoint = SsdpEndpoint { identity }

        endpoint.stop(sendByebye = false)
        endpoint.announceNow()

        assertFalse("stopped endpoint restarted itself", endpoint.isRunning)
    }

    /** start() after stop() is equally dead — teardown is not undoable. */
    @Test
    fun `start after stop is a no-op`() {
        val endpoint = SsdpEndpoint { identity }

        endpoint.stop(sendByebye = false)
        endpoint.start()

        assertFalse("stopped endpoint restarted itself", endpoint.isRunning)
    }

    /**
     * Regression: the service starts with `currentIp = resolveLocalIp() ?: "0.0.0.0"` and used to
     * build the identity and start SSDP from that sentinel unconditionally — multicasting
     * `LOCATION: http://0.0.0.0:49152/upnp/device.xml` to the whole LAN whenever the default network
     * had no site-local IPv4 (VPN up, or unvalidated Wi-Fi). Nothing goes on the wire — not the
     * alive burst, not an M-SEARCH reply — until a routable LOCATION exists. (No socket is opened on
     * the correct path, which is what keeps this a JVM test.)
     */
    @Test
    fun `an endpoint with no routable address never goes on the wire`() {
        val unaddressed = SsdpIdentity("uuid:x", "http://0.0.0.0:49152/upnp/device.xml", bootId = 1, configId = 1)
        val endpoint = SsdpEndpoint { unaddressed }

        endpoint.start()
        assertFalse("SSDP announced a LOCATION no control point can GET", endpoint.isRunning)

        endpoint.announceNow()
        assertFalse("SSDP announced a LOCATION no control point can GET", endpoint.isRunning)

        assertTrue(
            "an M-SEARCH must not be answered with an unreachable LOCATION",
            endpoint.buildSearchResponses(SsdpMSearch("ssdp:all", 1)).isEmpty(),
        )
    }
}
