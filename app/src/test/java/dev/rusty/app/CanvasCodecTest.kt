package dev.rusty.app

import com.spotify.canvaz.CanvazMetaProto
import com.spotify.canvaz.CanvazProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasCodecTest {
    private val repo = CanvasRepository()

    @Test fun encodeRequestRoundTripsTrackUri() {
        val bytes = repo.encodeRequest("abc123")
        val decoded = CanvazProto.EntityCanvazRequest.parseFrom(bytes)
        assertEquals(1, decoded.entitiesCount)
        assertEquals("spotify:track:abc123", decoded.getEntities(0).entityUri)
    }

    @Test fun parsePicksVideoCanvasForRequestedTrack() {
        val resp = CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:abc123")
                    .setUrl("https://canvaz.scdn.co/abc.cnvs.mp4")
                    .setType(CanvazMetaProto.Type.VIDEO_LOOPING)
            )
            .build()
        val result = repo.parse(resp.toByteArray(), "abc123")
        assertEquals(CanvasResult.Found("https://canvaz.scdn.co/abc.cnvs.mp4"), result)
    }

    @Test fun parseReturnsNoneWhenCanvasAbsent() {
        val resp = CanvazProto.EntityCanvazResponse.newBuilder().build()
        assertEquals(CanvasResult.None, repo.parse(resp.toByteArray(), "abc123"))
    }

    @Test fun parseIgnoresImageOnlyCanvas() {
        val resp = CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:abc123")
                    .setUrl("https://canvaz.scdn.co/img.jpg")
                    .setType(CanvazMetaProto.Type.IMAGE)
            )
            .build()
        assertEquals(CanvasResult.None, repo.parse(resp.toByteArray(), "abc123"))
    }

    @Test fun parseIgnoresEmptyUrl() {
        val resp = CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:abc123")
                    .setUrl("")
                    .setType(CanvazMetaProto.Type.VIDEO)
            )
            .build()
        assertEquals(CanvasResult.None, repo.parse(resp.toByteArray(), "abc123"))
    }

    @Test fun parsePicksTheMatchingTrackAmongMultiple() {
        val resp = CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:other")
                    .setUrl("https://canvaz.scdn.co/other.cnvs.mp4")
                    .setType(CanvazMetaProto.Type.VIDEO)
            )
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:abc123")
                    .setUrl("https://canvaz.scdn.co/match.cnvs.mp4")
                    .setType(CanvazMetaProto.Type.VIDEO)
            )
            .build()
        assertEquals(CanvasResult.Found("https://canvaz.scdn.co/match.cnvs.mp4"), repo.parse(resp.toByteArray(), "abc123"))
    }

    @Test fun parseIgnoresADifferentTrackCanvas() {
        // A single canvas for a DIFFERENT track (entity_uri set, non-blank) must NOT be shown.
        val resp = CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:other")
                    .setUrl("https://canvaz.scdn.co/other.cnvs.mp4")
                    .setType(CanvazMetaProto.Type.VIDEO)
            )
            .build()
        assertEquals(CanvasResult.None, repo.parse(resp.toByteArray(), "abc123"))
    }

    @Test fun parseAcceptsSingleCanvasWithBlankEntityUri() {
        // Tolerate the endpoint omitting entity_uri for a single-track request.
        val resp = CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("")
                    .setUrl("https://canvaz.scdn.co/blank.cnvs.mp4")
                    .setType(CanvazMetaProto.Type.VIDEO)
            )
            .build()
        assertEquals(CanvasResult.Found("https://canvaz.scdn.co/blank.cnvs.mp4"), repo.parse(resp.toByteArray(), "abc123"))
    }

    @Test fun parseGarbageIsNone() {
        assertTrue(repo.parse(byteArrayOf(1, 2, 3, 4, 5), "abc123") == CanvasResult.None)
    }
}
