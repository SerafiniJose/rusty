package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoapProtocolTest {
    private val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"><item id="0" parentID="-1" restricted="1"><dc:title xmlns:dc="http://purl.org/dc/elements/1.1/">TTS &amp; chimes</dc:title></item></DIDL-Lite>"""

    private fun setUriBody(uri: String, meta: String) = """
        <?xml version="1.0"?>
        <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">
        <s:Body><u:SetAVTransportURI xmlns:u="urn:schemas-upnp-org:service:AVTransport:1">
        <InstanceID>0</InstanceID>
        <CurrentURI>${UpnpXml.escape(uri)}</CurrentURI>
        <CurrentURIMetaData>${UpnpXml.escape(meta)}</CurrentURIMetaData>
        </u:SetAVTransportURI></s:Body></s:Envelope>
    """.trimIndent()

    @Test fun parse_extractsActionAndUnescapedArgs() {
        val req = SoapProtocol.parse(
            "\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"",
            setUriBody("http://ha:8123/tts.mp3?a=1&b=2", didl),
        )!!
        assertEquals(UpnpService.AVTRANSPORT, req.service)
        assertEquals("SetAVTransportURI", req.action)
        assertEquals("0", req.args["InstanceID"])
        assertEquals("http://ha:8123/tts.mp3?a=1&b=2", req.args["CurrentURI"])
        assertEquals(didl, req.args["CurrentURIMetaData"]) // DIDL retained verbatim, unescaped
    }

    @Test fun parse_rejectsUnknownServiceAndMalformedXml() {
        assertNull(SoapProtocol.parse("\"urn:x:svc:Bogus:1#Play\"", setUriBody("http://x", "")))
        assertNull(SoapProtocol.parse("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", "<not-xml"))
        assertNull(SoapProtocol.parse(null, setUriBody("http://x", "")))
    }

    @Test fun parse_rejectsDoctype() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" +
            setUriBody("http://x", "").substringAfter("?>")
        assertNull(SoapProtocol.parse("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", evil))
    }

    @Test fun parse_doctypeRejectedCaseInsensitive() {
        val evil = "<?xml version=\"1.0\"?><!doctype r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" +
            setUriBody("http://x", "").substringAfter("?>")
        assertNull(SoapProtocol.parse("\"urn:schemas-upnp-org:service:AVTransport:1#SetAVTransportURI\"", evil))
    }

    @Test fun response_wrapsAndEscapesOutArgs() {
        val xml = SoapProtocol.response(UpnpService.CONNECTIONMANAGER, "GetProtocolInfo",
            listOf("Source" to "", "Sink" to "http-get:*:audio/mpeg:*"))
        assertTrue(xml.contains("<u:GetProtocolInfoResponse xmlns:u=\"urn:schemas-upnp-org:service:ConnectionManager:1\">"))
        assertTrue(xml.contains("<Source></Source>"))
        assertTrue(xml.contains("<Sink>http-get:*:audio/mpeg:*</Sink>"))
        val meta = SoapProtocol.response(UpnpService.AVTRANSPORT, "GetMediaInfo",
            listOf("CurrentURIMetaData" to didl))
        assertTrue(meta.contains("&lt;DIDL-Lite")) // re-escaped on the way out
    }

    @Test fun fault_carriesUpnpErrorCode() {
        val xml = SoapProtocol.fault(SoapProtocol.ERR_RESOURCE_NOT_FOUND, "Resource not found")
        assertTrue(xml.contains("<faultcode>s:Client</faultcode>"))
        assertTrue(xml.contains("<faultstring>UPnPError</faultstring>"))
        assertTrue(xml.contains("<errorCode>716</errorCode>"))
        assertTrue(xml.contains("urn:schemas-upnp-org:control-1-0"))
    }
}
