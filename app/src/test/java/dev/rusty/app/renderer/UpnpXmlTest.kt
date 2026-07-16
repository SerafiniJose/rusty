package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

class UpnpXmlTest {
    @Test fun escape_escapesTheFiveEntities() {
        assertEquals("&lt;a b=&quot;1&amp;2&quot;&gt;&apos;", UpnpXml.escape("<a b=\"1&2\">'"))
    }

    @Test fun deviceDescription_hasMediaRendererRootAndExactServiceIds() {
        val xml = UpnpXml.deviceDescription("Rusty Speaker", "uuid:11111111-2222-3333-4444-555555555555", configId = 1)
        assertTrue(xml.contains("<deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>"))
        assertTrue(xml.contains("<friendlyName>Rusty Speaker</friendlyName>"))
        assertTrue(xml.contains("<UDN>uuid:11111111-2222-3333-4444-555555555555</UDN>"))
        for (id in listOf("AVTransport", "RenderingControl", "ConnectionManager")) {
            assertTrue(xml.contains("<serviceId>urn:upnp-org:serviceId:$id</serviceId>"))
            assertTrue(xml.contains("<serviceType>urn:schemas-upnp-org:service:$id:1</serviceType>"))
        }
        assertTrue(xml.contains("<SCPDURL>/upnp/scpd/avtransport.xml</SCPDURL>"))
        assertTrue(xml.contains("<controlURL>/upnp/control/avtransport</controlURL>"))
        assertTrue(xml.contains("<eventSubURL>/upnp/event/avtransport</eventSubURL>"))
    }

    @Test fun deviceDescription_escapesFriendlyName() {
        val xml = UpnpXml.deviceDescription("A <&> name", "uuid:x", configId = 1)
        assertTrue(xml.contains("<friendlyName>A &lt;&amp;&gt; name</friendlyName>"))
    }

    @Test fun deviceDescription_carriesTheConfigId() {
        val xml = UpnpXml.deviceDescription("Rusty Media Player", "uuid:x", configId = 9)
        assertTrue(xml, xml.contains("configId=\"9\""))
        assertTrue(xml, xml.contains("<friendlyName>Rusty Media Player</friendlyName>"))
    }

    @Test fun avTransportScpd_declaresTheImplementedActions() {
        for (a in listOf("SetAVTransportURI", "GetMediaInfo", "GetTransportInfo", "GetPositionInfo",
            "GetDeviceCapabilities", "GetTransportSettings", "Stop", "Play", "Pause", "Seek",
            "GetCurrentTransportActions")) {
            assertTrue("missing action $a", UpnpXml.AVTRANSPORT_SCPD.contains("<name>$a</name>"))
        }
        assertTrue(UpnpXml.AVTRANSPORT_SCPD.contains("<name>LastChange</name>"))
        assertFalse(UpnpXml.AVTRANSPORT_SCPD.contains("<name>Next</name>"))
    }

    @Test fun renderingControlScpd_omitsVolumeOnFixedVolumeDevices() {
        val normal = UpnpXml.renderingControlScpd(volumeFixed = false)
        val fixed = UpnpXml.renderingControlScpd(volumeFixed = true)
        for (a in listOf("GetVolume", "SetVolume", "GetMute", "SetMute")) {
            assertTrue(normal.contains("<name>$a</name>"))
            assertFalse(fixed.contains("<name>$a</name>"))
        }
        assertTrue(fixed.contains("<name>LastChange</name>"))
        assertTrue(normal.contains("<maximum>100</maximum>")) // declared volume range 0..100
    }

    @Test fun connectionManagerScpd_declaresProtocolInfo() {
        for (a in listOf("GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo")) {
            assertTrue(UpnpXml.CONNECTIONMANAGER_SCPD.contains("<name>$a</name>"))
        }
    }

    // --- Extra sanity check beyond the contract tests: every produced document must be
    // well-formed XML, since malformed output would otherwise only surface on-device
    // against a real UPnP control point.
    private fun assertWellFormed(xml: String, expectedRootLocalName: String) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        assertEquals(expectedRootLocalName, doc.documentElement.localName)
    }

    @Test fun allDocuments_areWellFormedXml() {
        assertWellFormed(
            UpnpXml.deviceDescription("Rusty Speaker", "uuid:11111111-2222-3333-4444-555555555555", configId = 1),
            "root",
        )
        assertWellFormed(UpnpXml.AVTRANSPORT_SCPD, "scpd")
        assertWellFormed(UpnpXml.CONNECTIONMANAGER_SCPD, "scpd")
        assertWellFormed(UpnpXml.renderingControlScpd(volumeFixed = false), "scpd")
        assertWellFormed(UpnpXml.renderingControlScpd(volumeFixed = true), "scpd")
    }
}
