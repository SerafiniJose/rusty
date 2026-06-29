package dev.rusty.app

import com.spotify.canvaz.CanvazMetaProto
import com.spotify.canvaz.CanvazProto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CanvasRepositoryFetchTest {

    private fun foundResponseBytes(trackId: String, url: String): ByteArray =
        CanvazProto.EntityCanvazResponse.newBuilder()
            .addCanvases(
                CanvazProto.EntityCanvazResponse.Canvaz.newBuilder()
                    .setEntityUri("spotify:track:$trackId")
                    .setUrl(url)
                    .setType(CanvazMetaProto.Type.VIDEO_LOOPING)
            )
            .build()
            .toByteArray()

    /** Records calls and replays a scripted response. */
    private class FakeHttp(var response: CanvasHttpResponse) : CanvasHttp {
        var calls = 0
        var lastBody: ByteArray? = null
        override fun post(url: String, token: String, body: ByteArray): CanvasHttpResponse {
            calls++
            lastBody = body
            return response
        }
    }

    @Test fun fetch200ParsesAndCachesOnSuccess() {
        val http = FakeHttp(CanvasHttpResponse(200, foundResponseBytes("t1", "https://c/x.cnvs.mp4")))
        val repo = CanvasRepository(http)

        val first = repo.fetch("t1", "tok")
        assertEquals(CanvasFetch.Success(CanvasResult.Found("https://c/x.cnvs.mp4")), first)

        // Second call for the same track must hit the cache (no second HTTP call).
        val second = repo.fetch("t1", "tok")
        assertEquals(first, second)
        assertEquals(1, http.calls)
    }

    @Test fun fetch401IsUnauthorizedAndNotCached() {
        val http = FakeHttp(CanvasHttpResponse(401, null))
        val repo = CanvasRepository(http)
        assertEquals(CanvasFetch.Unauthorized, repo.fetch("t1", "tok"))
        // A later success for the same track must still go to the network (401 was not cached).
        http.response = CanvasHttpResponse(200, foundResponseBytes("t1", "https://c/y.cnvs.mp4"))
        assertEquals(CanvasFetch.Success(CanvasResult.Found("https://c/y.cnvs.mp4")), repo.fetch("t1", "tok"))
        assertEquals(2, http.calls)
    }

    @Test fun fetchOtherCodeIsError() {
        val repo = CanvasRepository(FakeHttp(CanvasHttpResponse(500, null)))
        assertEquals(CanvasFetch.Error, repo.fetch("t1", "tok"))
    }

    @Test fun fetchCachesNoneResult() {
        val emptyBytes = CanvazProto.EntityCanvazResponse.newBuilder().build().toByteArray()
        val http = FakeHttp(CanvasHttpResponse(200, emptyBytes))
        val repo = CanvasRepository(http)
        assertEquals(CanvasFetch.Success(CanvasResult.None), repo.fetch("t1", "tok"))
        repo.fetch("t1", "tok")
        assertEquals("None result should be cached to avoid refetch", 1, http.calls)
    }

    @Test fun fetchSendsEncodedTrackUri() {
        val http = FakeHttp(CanvasHttpResponse(200, foundResponseBytes("t1", "https://c/x.cnvs.mp4")))
        CanvasRepository(http).fetch("t1", "tok")
        val body = http.lastBody
        assertNotNull(body)
        val sent = CanvazProto.EntityCanvazRequest.parseFrom(body!!)
        assertTrue(sent.entitiesList.any { it.entityUri == "spotify:track:t1" })
    }
}
