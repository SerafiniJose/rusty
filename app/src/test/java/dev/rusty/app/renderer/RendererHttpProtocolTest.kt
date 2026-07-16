package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeRuntime : RendererRuntime {
    override val friendlyName = "Rusty Speaker"
    override val udn = "uuid:test-udn"
    override val configId: Long = 1
    override val volumeFixed = false
    var state = RendererState()
    override val rendererState get() = state
    val dispatched = mutableListOf<RendererEvent>()
    override fun dispatch(event: RendererEvent) { dispatched.add(event) }
    override fun positionMs() = 5_000L
    override fun spotifySnapshot() = true to 9L
    override fun mixMode() = SpotifyInterruption.PAUSE
    override fun fadeMs() = 500L
    var vol = 40; var mute = false
    override fun volumePercent() = vol
    override fun setVolumePercent(v: Int) { vol = v }
    override fun muted() = mute
    override fun setMuted(m: Boolean) { mute = m }
    var volumePokes = 0
    override fun onVolumeChanged() { volumePokes++ }
    private val table = GenaSubscriptions({ 0L }, { "uuid:sid-1" })
    override fun gena() = table
    val subscribed = mutableListOf<GenaSubscriptions.Sub>()
    override fun onSubscribed(sub: GenaSubscriptions.Sub) { subscribed.add(sub) }
}

class RendererHttpProtocolTest {
    private fun soap(action: String, svc: UpnpService, args: String) = HttpRequest(
        "POST", UpnpXml.controlPath(svc),
        mapOf("SOAPACTION" to "\"${svc.serviceType}#$action\"", "CONTENT-TYPE" to "text/xml"),
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
           <u:$action xmlns:u="${svc.serviceType}"><InstanceID>0</InstanceID>$args</u:$action>
           </s:Body></s:Envelope>""",
    )

    @Test fun parseRequest_readsBodyByContentLength() {
        val raw = "POST /upnp/control/avtransport HTTP/1.1\r\nContent-Length: 5\r\nSOAPAction: \"x#y\"\r\n\r\nhello"
        val req = RendererHttpProtocol.parseRequest(raw.byteInputStream())!!
        assertEquals("POST", req.method); assertEquals("hello", req.body)
        assertEquals("\"x#y\"", req.headers["SOAPACTION"])
    }

    @Test fun deviceDescription_isServed() {
        val r = RendererHttpProtocol.route(HttpRequest("GET", "/upnp/device.xml", emptyMap(), ""), FakeRuntime(), "10.0.0.2")
        assertEquals(200, r.status)
        assertTrue(r.body.contains("<friendlyName>Rusty Speaker</friendlyName>"))
    }

    @Test fun setUri_validAudio_dispatches_badMime_faults714() {
        val rt = FakeRuntime()
        val ok = RendererHttpProtocol.route(soap("SetAVTransportURI", UpnpService.AVTRANSPORT,
            "<CurrentURI>http://ha:8123/tts.mp3</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"), rt, "10.0.0.2")
        assertEquals(200, ok.status)
        assertTrue(rt.dispatched[0] is RendererEvent.SoapSetUri)
        val bad = RendererHttpProtocol.route(soap("SetAVTransportURI", UpnpService.AVTRANSPORT,
            "<CurrentURI>http://ha/x.mkv</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"), rt, "10.0.0.2")
        assertEquals(500, bad.status)
        assertTrue(bad.body.contains("<errorCode>714</errorCode>"))
    }

    @Test fun play_capturesSpotifySnapshot_noMedia_faults701() {
        val rt = FakeRuntime()
        val noMedia = RendererHttpProtocol.route(soap("Play", UpnpService.AVTRANSPORT,
            "<Speed>1</Speed>"), rt, "10.0.0.2")
        assertTrue(noMedia.body.contains("<errorCode>701</errorCode>"))
        rt.state = rt.state.copy(media = RendererMedia("http://x", "", null), transport = RendererTransport.STOPPED)
        RendererHttpProtocol.route(soap("Play", UpnpService.AVTRANSPORT, "<Speed>1</Speed>"), rt, "10.0.0.2")
        assertEquals(
            RendererEvent.SoapPlay(spotifyPlaying = true, spotifySessionGen = 9L, mixMode = SpotifyInterruption.PAUSE, fadeMs = 500L),
            rt.dispatched.last(),
        )
    }

    @Test fun soapPlay_stampsFadeMsFromRuntime() {
        val rt = FakeRuntime()
        rt.state = rt.state.copy(media = RendererMedia("http://x", "", null), transport = RendererTransport.STOPPED)
        RendererHttpProtocol.route(soap("Play", UpnpService.AVTRANSPORT, "<Speed>1</Speed>"), rt, "10.0.0.2")
        assertEquals(500L, (rt.dispatched.last() as RendererEvent.SoapPlay).fadeMs)
    }

    @Test fun seek_nonRelTime_faults710() {
        val rt = FakeRuntime()
        rt.state = rt.state.copy(media = RendererMedia("http://x", "", null),
            transport = RendererTransport.PLAYING, seekable = true)
        val r = RendererHttpProtocol.route(soap("Seek", UpnpService.AVTRANSPORT,
            "<Unit>TRACK_NR</Unit><Target>2</Target>"), rt, "10.0.0.2")
        assertTrue(r.body.contains("<errorCode>710</errorCode>"))
        val ok = RendererHttpProtocol.route(soap("Seek", UpnpService.AVTRANSPORT,
            "<Unit>REL_TIME</Unit><Target>0:00:30</Target>"), rt, "10.0.0.2")
        assertEquals(200, ok.status)
        assertEquals(RendererEvent.SoapSeek(30_000L), rt.dispatched.last())
    }

    @Test fun getPositionInfo_reportsLivePosition() {
        val rt = FakeRuntime()
        rt.state = rt.state.copy(media = RendererMedia("http://x/a.mp3", "<DIDL-Lite/>", null),
            transport = RendererTransport.PLAYING, durationMs = 60_000)
        val r = RendererHttpProtocol.route(soap("GetPositionInfo", UpnpService.AVTRANSPORT, ""), rt, "10.0.0.2")
        assertTrue(r.body.contains("<RelTime>0:00:05</RelTime>"))
        assertTrue(r.body.contains("<TrackDuration>0:01:00</TrackDuration>"))
        assertTrue(r.body.contains("&lt;DIDL-Lite/&gt;")) // metadata echoed, re-escaped
    }

    @Test fun setVolume_setsAndPokes() {
        val rt = FakeRuntime()
        val r = RendererHttpProtocol.route(soap("SetVolume", UpnpService.RENDERINGCONTROL,
            "<Channel>Master</Channel><DesiredVolume>65</DesiredVolume>"), rt, "10.0.0.2")
        assertEquals(200, r.status); assertEquals(65, rt.vol); assertEquals(1, rt.volumePokes)
    }

    @Test fun subscribe_returnsSidAndTriggersInitialNotify_foreignCallback412() {
        val rt = FakeRuntime()
        val ok = RendererHttpProtocol.route(HttpRequest("SUBSCRIBE", "/upnp/event/avtransport",
            mapOf("CALLBACK" to "<http://10.0.0.2:39201/n>", "NT" to "upnp:event", "TIMEOUT" to "Second-300"), ""), rt, "10.0.0.2")
        assertEquals(200, ok.status)
        assertTrue(ok.headers.any { it.first == "SID" && it.second == "uuid:sid-1" })
        assertTrue(ok.headers.any { it.first == "TIMEOUT" && it.second == "Second-300" })
        assertEquals(1, rt.subscribed.size)
        val bad = RendererHttpProtocol.route(HttpRequest("SUBSCRIBE", "/upnp/event/avtransport",
            mapOf("CALLBACK" to "<http://8.8.8.8:1/n>", "NT" to "upnp:event"), ""), rt, "10.0.0.2")
        assertEquals(412, bad.status)
    }

    @Test fun unknownPath_404_unknownAction_fault401() {
        assertEquals(404, RendererHttpProtocol.route(HttpRequest("GET", "/nope", emptyMap(), ""), FakeRuntime(), "1.2.3.4").status)
        val r = RendererHttpProtocol.route(soap("Record", UpnpService.AVTRANSPORT, ""), FakeRuntime(), "1.2.3.4")
        assertTrue(r.body.contains("<errorCode>401</errorCode>"))
    }
}
