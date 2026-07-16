package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpEventXmlTest {
    @Test fun avtLastChange_isSingleEscapedWithValAttributes() {
        val state = RendererState(
            transport = RendererTransport.PLAYING, seekable = true,
            media = RendererMedia("http://x/a.mp3?q=1&r=2", "<DIDL-Lite/>", "audio/mpeg"),
            durationMs = 63_000,
        )
        val xml = UpnpEventXml.avTransportLastChange(state)
        assertTrue(xml.startsWith("<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"))
        assertTrue(xml.contains("<LastChange>&lt;Event xmlns=&quot;urn:schemas-upnp-org:metadata-1-0/AVT/&quot;&gt;"))
        assertTrue(xml.contains("&lt;InstanceID val=&quot;0&quot;&gt;"))
        assertTrue(xml.contains("&lt;TransportState val=&quot;PLAYING&quot;/&gt;"))
        // & inside the URI: escaped once for the attribute (&amp;), then the whole doc escaped once more.
        assertTrue(xml.contains("a.mp3?q=1&amp;amp;r=2"))
        assertTrue(xml.contains("&lt;CurrentTrackDuration val=&quot;0:01:03&quot;/&gt;"))
    }

    @Test fun rcsLastChange_usesMasterChannel() {
        val xml = UpnpEventXml.renderingControlLastChange(volumePercent = 37, muted = true)
        assertTrue(xml.contains("urn:schemas-upnp-org:metadata-1-0/RCS/"))
        assertTrue(xml.contains("&lt;Volume channel=&quot;Master&quot; val=&quot;37&quot;/&gt;"))
        assertTrue(xml.contains("&lt;Mute channel=&quot;Master&quot; val=&quot;1&quot;/&gt;"))
    }

    @Test fun connectionManagerInitial_listsSinkProtocolInfo() {
        val xml = UpnpEventXml.connectionManagerInitial()
        assertTrue(xml.contains("<SinkProtocolInfo>http-get:*:audio/mpeg:*"))
        assertTrue(xml.contains("<CurrentConnectionIDs>0</CurrentConnectionIDs>"))
    }
}
