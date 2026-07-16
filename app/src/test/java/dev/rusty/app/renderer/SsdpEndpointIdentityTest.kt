package dev.rusty.app.renderer

import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpEndpointIdentityTest {

    @Test
    fun `search responses are built from the CURRENT identity, not a captured one`() {
        var identity = SsdpIdentity("uuid:x", "http://1.2.3.4:49152/upnp/device.xml", bootId = 1, configId = 1)
        val endpoint = SsdpEndpoint { identity }

        // Simulates a rename landing between the M-SEARCH arriving and its delayed reply firing.
        identity = identity.copy(bootId = 2, configId = 2)

        val responses = endpoint.buildSearchResponses(SsdpMSearch("ssdp:all", 1))
        assertTrue(responses.isNotEmpty())
        responses.forEach { msg ->
            assertTrue("stale CONFIGID in:\n$msg", msg.contains("CONFIGID.UPNP.ORG: 2\r\n"))
            assertTrue("stale BOOTID in:\n$msg", msg.contains("BOOTID.UPNP.ORG: 2\r\n"))
        }
    }
}
