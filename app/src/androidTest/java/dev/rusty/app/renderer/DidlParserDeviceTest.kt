package dev.rusty.app.renderer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs [DidlParser] on the real Android XML stack (not host Xerces) to catch namespace/DOM
 * divergences the JVM unit test cannot. This is why the DLNA metadata screen needs on-device
 * verification.
 */
@RunWith(AndroidJUnit4::class)
class DidlParserDeviceTest {

    @Test
    fun parsesTitleArtistAlbumArtOnDevice() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
            xmlns:dc="http://purl.org/dc/elements/1.1/"
            xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
            <item><dc:title>T</dc:title><upnp:artist>A</upnp:artist>
            <upnp:album>Alb</upnp:album>
            <upnp:albumArtURI>https://art/x.jpg</upnp:albumArtURI></item></DIDL-Lite>"""
        val m = DidlParser.parse(didl)
        assertEquals("T", m.title)
        assertEquals("A", m.artist)
        assertEquals("Alb", m.album)
        assertEquals("https://art/x.jpg", m.albumArtUri)
    }
}
