package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpFormatsTest {
    @Test fun sinkProtocolInfo_isFourPartCommaList() {
        val info = UpnpFormats.sinkProtocolInfo()
        assertTrue(info.split(",").all { it.matches(Regex("http-get:\\*:audio/[\\w.+-]+:\\*")) })
        assertTrue(info.contains("http-get:*:audio/mpeg:*"))
        assertTrue(info.contains("http-get:*:audio/flac:*"))
    }

    @Test fun validate_acceptsHttpAudio() {
        val v = UpnpFormats.validateUri("http://ha.local:8123/tts.mp3", "audio/mpeg")
        assertTrue(v is UpnpFormats.Validation.Ok)
    }

    @Test fun validate_rejectsNonHttpScheme() {
        assertTrue(UpnpFormats.validateUri("rtsp://x/y", null) is UpnpFormats.Validation.BadUri)
        assertTrue(UpnpFormats.validateUri(null, null) is UpnpFormats.Validation.BadUri)
        assertTrue(UpnpFormats.validateUri("", null) is UpnpFormats.Validation.BadUri)
    }

    @Test fun validate_rejectsUnsupportedMime() {
        assertTrue(UpnpFormats.validateUri("http://x/a.mp4", "video/mp4") is UpnpFormats.Validation.UnsupportedMime)
        assertTrue(UpnpFormats.validateUri("http://x/a.m3u8", "application/vnd.apple.mpegurl") is UpnpFormats.Validation.UnsupportedMime)
    }

    @Test fun validate_acceptsRadioCodecsExoPlayerCanDecode() {
        // HA Radio Browser stations commonly advertise these; ExoPlayer decodes them, but the old
        // 7-type allowlist faulted them with 714 → "no sound". Accepted now, with no format hint so
        // ExoPlayer sniffs the real container.
        listOf("audio/aacp", "audio/x-aac", "application/ogg", "audio/x-flac", "audio/mp3",
            "application/octet-stream").forEach { mime ->
            val v = UpnpFormats.validateUri("http://radio.example/live", mime)
            assertTrue("$mime should be accepted", v is UpnpFormats.Validation.Ok)
            // Unreliable/alias types are handed to ExoPlayer with NO hint (null) to sniff.
            assertEquals("$mime should pass no hint", null, (v as UpnpFormats.Validation.Ok).mime)
        }
    }

    @Test fun validate_keepsReliableHintForKnownTypes() {
        val v = UpnpFormats.validateUri("http://x/a", "audio/mpeg") as UpnpFormats.Validation.Ok
        assertEquals("audio/mpeg", v.mime)
    }

    @Test fun validate_rejectsPlaylistsHlsDashAndNonAudio() {
        listOf("audio/x-mpegurl", "application/x-mpegurl", "application/vnd.apple.mpegurl",
            "audio/x-scpls", "application/pls+xml", "application/dash+xml", "video/mp4",
            "image/png").forEach { mime ->
            assertTrue("$mime should be rejected",
                UpnpFormats.validateUri("http://x/s", mime) is UpnpFormats.Validation.UnsupportedMime)
        }
    }

    @Test fun validate_rejectsPlaylistExtensionsWhenNoHint() {
        listOf("m3u", "m3u8", "pls", "mpd").forEach { ext ->
            assertTrue("$ext should be rejected",
                UpnpFormats.validateUri("http://x/s.$ext", null) is UpnpFormats.Validation.UnsupportedMime)
        }
    }

    @Test fun validate_guessesFromExtensionWhenNoHint() {
        val v = UpnpFormats.validateUri("https://radio.example/stream.aac", null) as UpnpFormats.Validation.Ok
        assertEquals("audio/aac", v.mime)
        // Extensionless + no hint stays Ok with null mime (ExoPlayer sniffs progressive streams).
        val v2 = UpnpFormats.validateUri("http://radio.example/live", null) as UpnpFormats.Validation.Ok
        assertEquals(null, v2.mime)
    }

    @Test fun time_roundTrips() {
        assertEquals("0:00:00", UpnpFormats.formatTime(0))
        assertEquals("1:02:03", UpnpFormats.formatTime(3_723_000))
        assertEquals("NOT_IMPLEMENTED", UpnpFormats.formatTime(null))
        assertEquals(3_723_000L, UpnpFormats.parseTime("1:02:03"))
        assertEquals(83_000L, UpnpFormats.parseTime("00:01:23"))
        assertEquals(null, UpnpFormats.parseTime("NOT_IMPLEMENTED"))
        assertEquals(null, UpnpFormats.parseTime(null))
        assertEquals(null, UpnpFormats.parseTime("garbage"))
    }
}
