package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnvifDiscoveryProtocolTest {

    private val probeMatchXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"
                      xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                      xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                      xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
          <env:Header>
            <wsa:MessageID>urn:uuid:11111111-2222-3333-4444-555555555555</wsa:MessageID>
            <wsa:RelatesTo>urn:uuid:99999999-8888-7777-6666-555555555555</wsa:RelatesTo>
            <wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To>
            <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
          </env:Header>
          <env:Body>
            <d:ProbeMatches>
              <d:ProbeMatch>
                <wsa:EndpointReference>
                  <wsa:Address>urn:uuid:4d454930-0000-1000-8000-00805f9b34fb</wsa:Address>
                </wsa:EndpointReference>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/name/Reolink%20RLC-520A onvif://www.onvif.org/hardware/RLC-520A onvif://www.onvif.org/location/unknown onvif://www.onvif.org/Profile/Streaming</d:Scopes>
                <d:XAddrs>http://[fe80::1]/onvif/device_service http://192.168.2.44/onvif/device_service</d:XAddrs>
                <d:MetadataVersion>1</d:MetadataVersion>
              </d:ProbeMatch>
            </d:ProbeMatches>
          </env:Body>
        </env:Envelope>
    """.trimIndent()

    // A second ProbeMatch for the same device (e.g. a duplicate multicast reply) with a
    // different name, to prove dedupe keeps the first-seen entry.
    private val duplicateProbeMatchXml = probeMatchXml.replace(
        "Reolink%20RLC-520A",
        "Duplicate%20Reply",
    )

    private val helloXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope"
                       xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                       xmlns:wsdd="http://schemas.xmlsoap.org/ws/2005/04/discovery">
          <soap:Header>
            <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/Hello</wsa:Action>
            <wsa:MessageID>urn:uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee</wsa:MessageID>
            <wsa:To>urn:schemas-xmlsoap-org:ws:2005:04:discovery</wsa:To>
          </soap:Header>
          <soap:Body>
            <wsdd:Hello>
              <wsa:EndpointReference>
                <wsa:Address>urn:uuid:4d454930-0000-1000-8000-00805f9b34fb</wsa:Address>
              </wsa:EndpointReference>
              <wsdd:Types>dn:NetworkVideoTransmitter</wsdd:Types>
              <wsdd:XAddrs>http://192.168.2.44/onvif/device_service</wsdd:XAddrs>
              <wsdd:MetadataVersion>1</wsdd:MetadataVersion>
            </wsdd:Hello>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    // Two ProbeMatch elements in one envelope: a legitimate WS-Discovery shape when a device
    // answers on multiple interfaces, or a responder batches several devices' matches together.
    // The second match belongs to a *different* device — fields must never mix across elements.
    private val twoProbeMatchXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <env:Envelope xmlns:env="http://www.w3.org/2003/05/soap-envelope"
                      xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing"
                      xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery"
                      xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
          <env:Header>
            <wsa:MessageID>urn:uuid:11111111-2222-3333-4444-555555555555</wsa:MessageID>
            <wsa:To>http://schemas.xmlsoap.org/ws/2004/08/addressing/role/anonymous</wsa:To>
            <wsa:Action>http://schemas.xmlsoap.org/ws/2005/04/discovery/ProbeMatches</wsa:Action>
          </env:Header>
          <env:Body>
            <d:ProbeMatches>
              <d:ProbeMatch>
                <wsa:EndpointReference>
                  <wsa:Address>urn:uuid:4d454930-0000-1000-8000-00805f9b34fb</wsa:Address>
                </wsa:EndpointReference>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/name/Reolink%20RLC-520A onvif://www.onvif.org/hardware/RLC-520A</d:Scopes>
                <d:XAddrs>http://192.168.2.44/onvif/device_service</d:XAddrs>
                <d:MetadataVersion>1</d:MetadataVersion>
              </d:ProbeMatch>
              <d:ProbeMatch>
                <wsa:EndpointReference>
                  <wsa:Address>urn:uuid:99999999-8888-7777-6666-555555555555</wsa:Address>
                </wsa:EndpointReference>
                <d:Types>dn:NetworkVideoTransmitter</d:Types>
                <d:Scopes>onvif://www.onvif.org/name/Other%20Camera onvif://www.onvif.org/hardware/OTHER-1</d:Scopes>
                <d:XAddrs>http://192.168.2.99/onvif/device_service</d:XAddrs>
                <d:MetadataVersion>1</d:MetadataVersion>
              </d:ProbeMatch>
            </d:ProbeMatches>
          </env:Body>
        </env:Envelope>
    """.trimIndent()

    private val garbageXml = "not even xml { }"

    @Test
    fun `parseProbeMatch extracts uuid name hardware and xaddrs`() {
        val camera = OnvifDiscoveryProtocol.parseProbeMatch(probeMatchXml)

        assertNotNull(camera)
        assertEquals("urn:uuid:4d454930-0000-1000-8000-00805f9b34fb", camera!!.endpointUuid)
        assertEquals("Reolink RLC-520A", camera.name)
        assertEquals("RLC-520A", camera.hardware)
        assertEquals(
            listOf(
                "http://[fe80::1]/onvif/device_service",
                "http://192.168.2.44/onvif/device_service",
            ),
            camera.xaddrs,
        )
    }

    @Test
    fun `parseProbeMatch returns null for garbage xml`() {
        assertNull(OnvifDiscoveryProtocol.parseProbeMatch(garbageXml))
    }

    @Test
    fun `parseProbeMatch returns null for a Hello envelope`() {
        assertNull(OnvifDiscoveryProtocol.parseProbeMatch(helloXml))
    }

    @Test
    fun `parseProbeMatch returns null when name scope is absent`() {
        val xml = probeMatchXml.replace(
            "onvif://www.onvif.org/name/Reolink%20RLC-520A ",
            "",
        )
        val camera = OnvifDiscoveryProtocol.parseProbeMatch(xml)
        assertNotNull(camera)
        assertNull(camera!!.name)
        assertEquals("RLC-520A", camera.hardware)
    }

    @Test
    fun `parseProbeMatch returns null hardware when hardware scope is absent`() {
        val xml = probeMatchXml.replace(
            "onvif://www.onvif.org/hardware/RLC-520A ",
            "",
        )
        val camera = OnvifDiscoveryProtocol.parseProbeMatch(xml)
        assertNotNull(camera)
        assertEquals("Reolink RLC-520A", camera!!.name)
        assertNull(camera.hardware)
    }

    @Test
    fun `parseProbeMatch does not mix fields across multiple ProbeMatch elements`() {
        val camera = OnvifDiscoveryProtocol.parseProbeMatch(twoProbeMatchXml)

        assertNotNull(camera)
        assertEquals("urn:uuid:4d454930-0000-1000-8000-00805f9b34fb", camera!!.endpointUuid)
        assertEquals(listOf("http://192.168.2.44/onvif/device_service"), camera.xaddrs)
        assertEquals("Reolink RLC-520A", camera.name)
        assertEquals("RLC-520A", camera.hardware)
    }

    @Test
    fun `dedupe keeps first of duplicate uuids`() {
        val first = OnvifDiscoveryProtocol.parseProbeMatch(probeMatchXml)!!
        val second = OnvifDiscoveryProtocol.parseProbeMatch(duplicateProbeMatchXml)!!
        assertEquals(first.endpointUuid, second.endpointUuid)
        assertTrue(first.name != second.name)

        val deduped = OnvifDiscoveryProtocol.dedupe(listOf(first, second))

        assertEquals(1, deduped.size)
        assertEquals(first.name, deduped[0].name)
    }

    @Test
    fun `dedupe keeps distinct uuids separate`() {
        val first = OnvifDiscoveryProtocol.parseProbeMatch(probeMatchXml)!!
        val other = DiscoveredCamera(
            endpointUuid = "urn:uuid:00000000-0000-0000-0000-000000000000",
            xaddrs = listOf("http://192.168.2.99/onvif/device_service"),
            name = "Other Camera",
            hardware = null,
        )

        val deduped = OnvifDiscoveryProtocol.dedupe(listOf(first, other))

        assertEquals(2, deduped.size)
    }

    @Test
    fun `pickXAddr prefers ipv4 over ipv6 on same subnet`() {
        val picked = OnvifDiscoveryProtocol.pickXAddr(
            listOf("http://[fe80::1]/onvif", "http://192.168.2.44/onvif"),
            "192.168.2.222",
            24,
        )
        assertEquals("http://192.168.2.44/onvif", picked)
    }

    @Test
    fun `pickXAddr returns off-subnet ipv4 when it is the only ipv4`() {
        val picked = OnvifDiscoveryProtocol.pickXAddr(
            listOf("http://[fe80::1]/onvif", "http://10.0.0.5/onvif"),
            "192.168.2.222",
            24,
        )
        assertEquals("http://10.0.0.5/onvif", picked)
    }

    @Test
    fun `pickXAddr prefers same-subnet ipv4 over off-subnet ipv4`() {
        val picked = OnvifDiscoveryProtocol.pickXAddr(
            listOf("http://10.0.0.5/onvif", "http://192.168.2.44/onvif"),
            "192.168.2.222",
            24,
        )
        assertEquals("http://192.168.2.44/onvif", picked)
    }

    @Test
    fun `pickXAddr returns null for ipv6-only candidates`() {
        val picked = OnvifDiscoveryProtocol.pickXAddr(
            listOf("http://[fe80::1]/onvif", "https://[fe80::2]/onvif"),
            "192.168.2.222",
            24,
        )
        assertNull(picked)
    }

    @Test
    fun `pickXAddr returns null for empty list`() {
        assertNull(OnvifDiscoveryProtocol.pickXAddr(emptyList(), "192.168.2.222", 24))
    }

    @Test
    fun `buildProbe contains message id target type and probe action`() {
        val bytes = OnvifDiscoveryProtocol.buildProbe("deadbeef-0000-1111-2222-333344445555")
        val xml = String(bytes, Charsets.UTF_8)

        assertTrue(xml.contains("urn:uuid:deadbeef-0000-1111-2222-333344445555"))
        assertTrue(xml.contains("NetworkVideoTransmitter"))
        assertTrue(xml.contains("http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe"))
        assertTrue(xml.contains("urn:schemas-xmlsoap-org:ws:2005:04:discovery"))
    }
}
