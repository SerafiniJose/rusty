package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DidlMetadataTest {

    private val full = """
        <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                   xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">
          <item id="0" parentID="-1" restricted="1">
            <dc:title>Song Name</dc:title>
            <upnp:artist>The Artist</upnp:artist>
            <upnp:album>The Album</upnp:album>
            <upnp:albumArtURI>https://art.example/cover.jpg</upnp:albumArtURI>
            <upnp:class>object.item.audioItem.musicTrack</upnp:class>
          </item>
        </DIDL-Lite>
    """.trimIndent()

    @Test fun parsesAllFields() {
        val m = DidlParser.parse(full)
        assertEquals("Song Name", m.title)
        assertEquals("The Artist", m.artist)
        assertEquals("The Album", m.album)
        assertEquals("https://art.example/cover.jpg", m.albumArtUri)
    }

    @Test fun creatorFallsBackWhenNoArtist() {
        val didl = full.replace(Regex("<upnp:artist>.*?</upnp:artist>"),
            "<dc:creator>Creator Name</dc:creator>")
        assertEquals("Creator Name", DidlParser.parse(didl).artist)
    }

    @Test fun firstOfRepeatedFieldsWins() {
        val didl = full.replace("<upnp:artist>The Artist</upnp:artist>",
            "<upnp:artist>First</upnp:artist><upnp:artist>Second</upnp:artist>")
        assertEquals("First", DidlParser.parse(didl).artist)
    }

    @Test fun blankFieldsBecomeNull() {
        val didl = full.replace("<dc:title>Song Name</dc:title>", "<dc:title>   </dc:title>")
        assertNull(DidlParser.parse(didl).title)
    }

    @Test fun nonHttpArtUriIgnored() {
        val didl = full.replace("https://art.example/cover.jpg", "file:///etc/passwd")
        assertNull(DidlParser.parse(didl).albumArtUri)
    }

    @Test fun uppercaseSchemeArtUriIsAccepted() {
        val didl = full.replace("https://art.example/cover.jpg", "HTTPS://art.example/cover.jpg")
        assertEquals("HTTPS://art.example/cover.jpg", DidlParser.parse(didl).albumArtUri)
    }

    @Test fun nullAndBlankInputYieldEmpty() {
        assertEquals(DidlMetadata.EMPTY, DidlParser.parse(null))
        assertEquals(DidlMetadata.EMPTY, DidlParser.parse(""))
    }

    @Test fun malformedXmlYieldsEmptyNotThrow() {
        assertEquals(DidlMetadata.EMPTY, DidlParser.parse("<DIDL-Lite><item>"))
    }

    @Test fun xxeExternalEntityIsNotExpanded() {
        val didl = """
            <!DOCTYPE DIDL-Lite [ <!ENTITY xxe SYSTEM "file:///etc/hostname"> ]>
            <DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/"
                       xmlns:dc="http://purl.org/dc/elements/1.1/">
              <item><dc:title>&xxe;</dc:title></item>
            </DIDL-Lite>
        """.trimIndent()
        // DOCTYPE is rejected outright -> empty, and certainly no file contents leak.
        val m = DidlParser.parse(didl)
        assertEquals(DidlMetadata.EMPTY, m)
    }
}
