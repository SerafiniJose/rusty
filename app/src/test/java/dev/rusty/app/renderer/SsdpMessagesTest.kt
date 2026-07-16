package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SsdpMessagesTest {
    private val id = SsdpIdentity(
        udn = "uuid:11111111-2222-3333-4444-555555555555",
        location = "http://192.168.7.50:49152/upnp/device.xml",
        bootId = 7,
        configId = 1,
    )

    @Test fun parse_extractsStAndMx() {
        val p = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 3\r\nST: ssdp:all\r\n\r\n"
        assertEquals(SsdpMSearch("ssdp:all", 3), SsdpMessages.parseMSearch(p))
    }

    @Test fun parse_rejectsNonDiscover() {
        assertNull(SsdpMessages.parseMSearch("NOTIFY * HTTP/1.1\r\nNTS: ssdp:alive\r\n\r\n"))
        assertNull(SsdpMessages.parseMSearch("M-SEARCH * HTTP/1.1\r\nST: ssdp:all\r\n\r\n")) // no MAN
    }

    @Test fun ssdpAll_yieldsSixResponses() {
        val rs = SsdpMessages.responsesFor(SsdpMSearch("ssdp:all", 1), id)
        assertEquals(6, rs.size)
        for (r in rs) {
            assertTrue(r.startsWith("HTTP/1.1 200 OK\r\n"))
            assertTrue(r.contains("CACHE-CONTROL: max-age=1800\r\n"))
            assertTrue(r.contains("EXT:\r\n"))
            assertTrue(r.contains("LOCATION: ${id.location}\r\n"))
            assertTrue(r.contains("SERVER: ${SsdpMessages.SERVER_HEADER}\r\n"))
            assertTrue(r.contains("BOOTID.UPNP.ORG: 7\r\n"))
            assertTrue(r.endsWith("\r\n\r\n"))
        }
    }

    @Test fun rootDeviceSearch_yieldsOneResponseWithComposedUsn() {
        val rs = SsdpMessages.responsesFor(SsdpMSearch("upnp:rootdevice", 1), id)
        assertEquals(1, rs.size)
        assertTrue(rs[0].contains("ST: upnp:rootdevice\r\n"))
        assertTrue(rs[0].contains("USN: ${id.udn}::upnp:rootdevice\r\n"))
    }

    @Test fun mediaRendererSearch_matches_unknownStDoesNot() {
        assertEquals(1, SsdpMessages.responsesFor(
            SsdpMSearch("urn:schemas-upnp-org:device:MediaRenderer:1", 1), id).size)
        assertEquals(0, SsdpMessages.responsesFor(
            SsdpMSearch("urn:schemas-upnp-org:device:ZonePlayer:1", 1), id).size)
    }

    @Test fun uuidSearch_usesBareUsn() {
        val rs = SsdpMessages.responsesFor(SsdpMSearch(id.udn, 1), id)
        assertEquals(1, rs.size)
        assertTrue(rs[0].contains("USN: ${id.udn}\r\n"))
    }

    @Test fun aliveAndByebye_coverAllSixTargets() {
        val alive = SsdpMessages.aliveNotifications(id)
        val bye = SsdpMessages.byebyeNotifications(id)
        assertEquals(6, alive.size); assertEquals(6, bye.size)
        assertTrue(alive.all { it.startsWith("NOTIFY * HTTP/1.1\r\n") &&
            it.contains("HOST: 239.255.255.250:1900\r\n") &&
            it.contains("NTS: ssdp:alive\r\n") && it.contains("LOCATION: ") })
        assertTrue(bye.all { it.contains("NTS: ssdp:byebye\r\n") })
        assertTrue(alive.any { it.contains("NT: upnp:rootdevice\r\n") })
        assertTrue(alive.any { it.contains("NT: urn:schemas-upnp-org:service:AVTransport:1\r\n") })
    }

    /**
     * Regression: MediaRendererService falls back to 0.0.0.0 when it cannot resolve a LAN address
     * (no site-local IPv4 on the default network — a VPN, or a Wi-Fi network Android did not
     * validate), and it used to build the SSDP identity from that sentinel and multicast
     * `LOCATION: http://0.0.0.0:49152/upnp/device.xml`. Every control point on the LAN accepts such
     * an alive burst, fails the GET (0.0.0.0 is not a destination) and caches the renderer as a
     * broken device for max-age=1800. An identity with no routable LOCATION is not advertisable.
     */
    @Test fun identityWithoutARoutableLocation_isNotAdvertisable() {
        val port = 49152
        assertFalse(
            "the 0.0.0.0 fallback must never be advertised",
            SsdpMessages.isAdvertisable(id.copy(location = "http://0.0.0.0:$port/upnp/device.xml")),
        )
        assertFalse(SsdpMessages.isAdvertisable(id.copy(location = "http://[::]:$port/upnp/device.xml")))
        assertFalse(SsdpMessages.isAdvertisable(id.copy(location = "")))
        assertFalse("the placeholder identity is not a device", SsdpMessages.isAdvertisable(id.copy(udn = "")))

        assertTrue(SsdpMessages.isAdvertisable(id))
        assertTrue(SsdpMessages.isAdvertisable(id.copy(location = "http://10.0.0.8:$port/upnp/device.xml")))
    }

    @Test fun locationHost_isTheHostSsdpMustTransmitFrom() {
        assertEquals("192.168.7.50", SsdpMessages.locationHost(id))
        assertNull(SsdpMessages.locationHost(id.copy(location = "not a url")))
    }

    @Test fun advertisements_carryTheIdentitysConfigId() {
        val id = SsdpIdentity("uuid:x", "http://1.2.3.4:49152/upnp/device.xml", bootId = 3, configId = 7)

        val alive = SsdpMessages.aliveNotifications(id)
        val byebye = SsdpMessages.byebyeNotifications(id)
        val responses = SsdpMessages.responsesFor(SsdpMSearch("ssdp:all", 1), id)

        (alive + byebye + responses).forEach { msg ->
            assertTrue("missing CONFIGID in:\n$msg", msg.contains("CONFIGID.UPNP.ORG: 7\r\n"))
            assertTrue("missing BOOTID in:\n$msg", msg.contains("BOOTID.UPNP.ORG: 3\r\n"))
        }
    }
}
