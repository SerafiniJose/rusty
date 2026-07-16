package dev.rusty.app.renderer

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The address rule the SSDP LOCATION and the SSDP transmit interface now BOTH answer to.
 *
 * Regression context: MediaRendererService resolved the LOCATION host from
 * `ConnectivityManager.activeNetwork` only. When the default network is a VPN (CGNAT 100.64/10 —
 * routable-looking, but NOT site-local) or cellular (because Android leaves an internet-less Wi-Fi
 * network non-default), that yields nothing, the service fell back to 0.0.0.0 and advertised it,
 * while SsdpEndpoint transmitted happily from wlan0. Both now select the same site-local IPv4.
 */
class LanAddressTest {

    private fun addrs(vararg hosts: String) = hosts.map { InetAddress.getByName(it) }

    @Test fun picksTheSiteLocalIpv4() {
        assertEquals("192.168.1.50", LanAddress.siteLocalIpv4(addrs("fe80::1", "192.168.1.50"))?.hostAddress)
        assertEquals("10.0.0.8", LanAddress.siteLocalIpv4(addrs("10.0.0.8"))?.hostAddress)
        assertEquals("172.16.4.2", LanAddress.siteLocalIpv4(addrs("172.16.4.2"))?.hostAddress)
    }

    @Test fun rejectsAddressesNoLanDeviceCanReachUsAt() {
        assertNull("a VPN/tunnel CGNAT address is not a LAN address", LanAddress.siteLocalIpv4(addrs("100.64.1.2")))
        assertNull(LanAddress.siteLocalIpv4(addrs("127.0.0.1")))
        assertNull("link-local is not site-local", LanAddress.siteLocalIpv4(addrs("169.254.3.4")))
        assertNull("IPv6 only — we advertise an IPv4 LOCATION", LanAddress.siteLocalIpv4(addrs("fe80::1", "::1")))
        assertNull(LanAddress.siteLocalIpv4(emptyList()))
    }

    /** The exact state the review reproduced: tunnel first, LAN second — the LAN address must win. */
    @Test fun aVpnAddressDoesNotShadowTheLanAddress() {
        assertEquals(
            "192.168.7.116",
            LanAddress.siteLocalIpv4(addrs("100.64.0.7", "192.168.7.116"))?.hostAddress,
        )
    }
}
