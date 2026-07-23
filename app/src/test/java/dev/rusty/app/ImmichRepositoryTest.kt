package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmichRepositoryTest {

    private val cfg = ImmichConfig("http://immich.local", "KEY")
    private val noFilters = ImmichFilters(emptyList(), emptyList(), emptyList())

    /** Scripted fake: records requests, returns queued responses per URL-substring match. */
    private class FakeHttp(private val script: (String, String) -> ImmichHttpResponse) : ImmichHttp {
        val requests = mutableListOf<Triple<String, String, String?>>()  // method, url, body
        override fun request(method: String, url: String, apiKey: String, body: String?): ImmichHttpResponse {
            requests.add(Triple(method, url, body))
            require(apiKey == "KEY") { "x-api-key must be threaded through" }
            return script(method, url)
        }
    }

    @Test fun fetchRandomAssetsPostsToSearchRandomAndParses() {
        val http = FakeHttp { _, _ ->
            ImmichHttpResponse(200, """[{"id":"a1","type":"IMAGE"}]""")
        }
        val result = ImmichRepository(http).fetchRandomAssets(cfg, noFilters, 30)
        assertEquals(listOf("a1"), (result as ImmichResult.Ok).value.map { it.id })
        val (method, url, body) = http.requests.single()
        assertEquals("POST", method)
        assertEquals("http://immich.local/api/search/random", url)
        assertTrue(body!!.contains("\"IMAGE\""))
    }

    @Test fun errorMapping401And403ToAuthElseUnreachable() {
        assertEquals(ImmichErrorKind.AUTH,
            (ImmichRepository { _, _, _, _ -> ImmichHttpResponse(401, null) }
                .fetchRandomAssets(cfg, noFilters, 1) as ImmichResult.Error).kind)
        assertEquals(ImmichErrorKind.AUTH,
            (ImmichRepository { _, _, _, _ -> ImmichHttpResponse(403, null) }
                .fetchRandomAssets(cfg, noFilters, 1) as ImmichResult.Error).kind)
        assertEquals(ImmichErrorKind.UNREACHABLE,
            (ImmichRepository { _, _, _, _ -> ImmichHttpResponse(500, null) }
                .fetchRandomAssets(cfg, noFilters, 1) as ImmichResult.Error).kind)
        assertEquals(ImmichErrorKind.UNREACHABLE,
            (ImmichRepository { _, _, _, _ -> throw RuntimeException("timeout") }
                .fetchRandomAssets(cfg, noFilters, 1) as ImmichResult.Error).kind)
    }

    @Test fun fetchPeoplePaginatesUntilExhausted() {
        val http = FakeHttp { _, url ->
            when {
                url.contains("page=1") -> ImmichHttpResponse(200,
                    """{"hasNextPage":true,"people":[{"id":"p1","name":"Ana","isHidden":false}]}""")
                url.contains("page=2") -> ImmichHttpResponse(200,
                    """{"hasNextPage":false,"people":[{"id":"p2","name":"Bo","isHidden":false}]}""")
                else -> ImmichHttpResponse(404, null)
            }
        }
        val result = ImmichRepository(http).fetchPeople(cfg)
        assertEquals(listOf("p1", "p2"), (result as ImmichResult.Ok).value.map { it.id })
        assertTrue(http.requests.all { it.second.contains("withHidden=false") })
    }

    @Test fun testConnectionProbesAllFourCapabilities() {
        val http = FakeHttp { _, url ->
            if (url.contains("/api/tags")) ImmichHttpResponse(403, null)
            else ImmichHttpResponse(200, if (url.contains("random")) "[]" else
                if (url.contains("people")) """{"hasNextPage":false,"people":[]}""" else "[]")
        }
        val probes = ImmichRepository(http).testConnection(cfg, noFilters)
        assertEquals(listOf("Photos", "Albums", "People", "Tags"), probes.map { it.label })
        assertEquals(listOf(true, true, true, false), probes.map { it.ok })
    }

    @Test fun previewUrlShape() {
        assertEquals("http://immich.local/api/assets/a1/thumbnail?size=preview",
            ImmichRepository { _, _, _, _ -> ImmichHttpResponse(200, null) }.previewUrl(cfg, "a1"))
    }

    @Test fun personThumbUrlIsPeopleEndpointWithoutCredentials() {
        val cfg = ImmichConfig("http://immich.local:2283", "secret")
        val url = ImmichRepository.shared.personThumbUrl(cfg, "p1")
        assertEquals("http://immich.local:2283/api/people/p1/thumbnail", url)
        assertFalse(url.contains("secret"))
    }

    // Structural guarantee: exchange() must be total -- a throw from the parse step (not just
    // http.request) must map to Error(UNREACHABLE) rather than propagate. This can't be pinned
    // through a public fetch method with real ImmichApi parse functions, since every parseXxx
    // self-catches and never actually throws -- that's an external fact about ImmichApi.kt, not a
    // guarantee this class makes. `exchange` is `internal` (not private) specifically so this test
    // can exercise the exact shipped function with a synthetic throwing parse lambda, rather than
    // relying on ImmichApi's incidental behavior or reflection into a private member.
    @Test fun exchangeMapsThrowingParseToUnreachable() {
        val http = FakeHttp { _, _ -> ImmichHttpResponse(200, "ok") }
        val result = ImmichRepository(http).exchange(cfg, "GET", "http://immich.local/api/albums", null) {
            throw RuntimeException("boom")
        }
        assertEquals(ImmichErrorKind.UNREACHABLE, (result as ImmichResult.Error).kind)
    }

    // Pagination must not run unbounded: a server that always answers hasNextPage=true must stop
    // at a sane page cap and return whatever was collected so far, not loop/grow forever.
    @Test fun fetchPeopleStopsAtPageCapWhenServerNeverTerminates() {
        val http = FakeHttp { _, url ->
            val page = Regex("page=(\\d+)").find(url)!!.groupValues[1]
            ImmichHttpResponse(200,
                """{"hasNextPage":true,"people":[{"id":"p$page","name":"N$page","isHidden":false}]}""")
        }
        val result = ImmichRepository(http).fetchPeople(cfg)
        val items = (result as ImmichResult.Ok).value
        assertEquals(ImmichRepository.PEOPLE_PAGE_CAP, items.size)
        assertEquals(ImmichRepository.PEOPLE_PAGE_CAP, http.requests.size)
    }

    // Controller resolution: testConnection's People probe must use the FIRST PAGE ONLY, never
    // fetchPeople's full pagination loop -- otherwise "Test connection" on a large library becomes
    // N sequential HTTP round trips behind a settings button.
    @Test fun testConnectionPeopleProbeIssuesExactlyOneRequestEvenWhenMorePagesExist() {
        val http = FakeHttp { _, url ->
            if (url.contains("/api/tags")) ImmichHttpResponse(200, "[]")
            else if (url.contains("/api/albums")) ImmichHttpResponse(200, "[]")
            else if (url.contains("random")) ImmichHttpResponse(200, "[]")
            else if (url.contains("/api/people")) ImmichHttpResponse(200,
                """{"hasNextPage":true,"people":[{"id":"p1","name":"Ana","isHidden":false}]}""")
            else ImmichHttpResponse(404, null)
        }
        val probes = ImmichRepository(http).testConnection(cfg, noFilters)
        assertEquals(true, probes.single { it.label == "People" }.ok)
        val peopleRequests = http.requests.filter { it.second.contains("/api/people") }
        assertEquals(1, peopleRequests.size)
    }

    // ---- shared albums ------------------------------------------------------
    //
    // Two Immich server facts drive every test below (verified against the server source, not
    // guessed):
    //   1. searchAssetBuilder applies `asset.ownerId IN (me, ...partners)` ALONGSIDE albumIds, so
    //      /search/random can never return an asset owned by whoever shared an album with you.
    //   2. inAlbums applies `having count(distinct albumId) = albumIds.length`, so passing several
    //      albums at once asks for their INTERSECTION.
    // Hence: one request per selected album, unioned, and shared albums routed through /timeline.

    private val sharedAlbumsJson = """[{"id":"shared1","albumName":"Ana's trip"}]"""

    @Test fun fetchSharedAlbumIdsAsksForIsOwnedFalse() {
        val http = FakeHttp { _, _ -> ImmichHttpResponse(200, sharedAlbumsJson) }
        val ids = ImmichRepository(http).fetchSharedAlbumIds(cfg)
        assertEquals(setOf("shared1"), (ids as ImmichResult.Ok).value)
        assertEquals("http://immich.local/api/albums?isOwned=false", http.requests.single().second)
    }

    @Test fun sharedAlbumGoesThroughTimelineAndOwnedAlbumThroughSearchRandom() {
        val http = FakeHttp { _, url ->
            when {
                url.contains("isOwned=false") -> ImmichHttpResponse(200, sharedAlbumsJson)
                url.contains("/api/timeline/buckets") ->
                    ImmichHttpResponse(200, """[{"timeBucket":"2024-01-01","count":5}]""")
                url.contains("/api/timeline/bucket") -> ImmichHttpResponse(200,
                    """{"id":["s1"],"ratio":[1.5],"isImage":[true],"isTrashed":[false]}""")
                url.contains("/search/random") -> ImmichHttpResponse(200, """[{"id":"o1"}]""")
                else -> ImmichHttpResponse(404, null)
            }
        }
        val filters = ImmichFilters(listOf("owned1", "shared1"), emptyList(), emptyList())
        val result = ImmichRepository(http).fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(setOf("o1", "s1"), (result as ImmichResult.Ok).value.map { it.id }.toSet())

        // The shared album must NEVER be posted to /search/random -- that is the whole bug.
        val searchBodies = http.requests.filter { it.second.contains("/search/random") }.map { it.third!! }
        assertTrue(searchBodies.none { it.contains("shared1") })
        assertTrue(searchBodies.any { it.contains("owned1") })
        assertTrue(http.requests.any { it.second.contains("albumId=shared1") })
    }

    /** Several albums = one request each, unioned; never one request listing them all (= AND). */
    @Test fun multipleOwnedAlbumsAreQueriedSeparatelyAndUnioned() {
        // The album id rides in the POST body, not the URL, so the per-album calls are told apart
        // by arrival order (fetchSlideshowAssets walks filters.albumIds in order).
        var call = 0
        val http = FakeHttp { _, url ->
            if (url.contains("isOwned=false")) ImmichHttpResponse(200, "[]")
            else ImmichHttpResponse(200, if (call++ == 0) """[{"id":"a"}]""" else """[{"id":"b"}]""")
        }
        val filters = ImmichFilters(listOf("albumA", "albumB"), emptyList(), emptyList())
        val result = ImmichRepository(http).fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(setOf("a", "b"), (result as ImmichResult.Ok).value.map { it.id }.toSet())
        val searchBodies = http.requests.filter { it.second.contains("/search/random") }.map { it.third!! }
        assertEquals(2, searchBodies.size)
        assertTrue(searchBodies.none { it.contains("albumA") && it.contains("albumB") })
    }

    @Test fun noAlbumFilterKeepsTheSingleSearchRandomCallAndSkipsOwnershipLookup() {
        val http = FakeHttp { _, _ -> ImmichHttpResponse(200, """[{"id":"x"}]""") }
        val filters = ImmichFilters(emptyList(), listOf("p1"), emptyList())
        val result = ImmichRepository(http).fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(listOf("x"), (result as ImmichResult.Ok).value.map { it.id })
        assertEquals(1, http.requests.size)
        assertTrue(http.requests.single().second.contains("/search/random"))
    }

    /** A partial failure keeps the frame alive; only a total failure is an error. */
    @Test fun oneFailingAlbumStillReturnsTheOtherButAllFailingIsAnError() {
        var call = 0
        val partial = FakeHttp { _, url ->
            if (url.contains("isOwned=false")) ImmichHttpResponse(200, "[]")
            else if (call++ == 0) ImmichHttpResponse(200, """[{"id":"a"}]""") else ImmichHttpResponse(500, null)
        }
        val filters = ImmichFilters(listOf("albumA", "albumB"), emptyList(), emptyList())
        val ok = ImmichRepository(partial).fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(listOf("a"), (ok as ImmichResult.Ok).value.map { it.id })

        val allFail = FakeHttp { _, url ->
            if (url.contains("isOwned=false")) ImmichHttpResponse(200, "[]") else ImmichHttpResponse(401, null)
        }
        val err = ImmichRepository(allFail).fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(ImmichErrorKind.AUTH, (err as ImmichResult.Error).kind)
    }

    /** Ownership can't be resolved -> degrade to the old behaviour, never fail the slideshow. */
    @Test fun unresolvableOwnershipFallsBackToSearchRandomPerAlbum() {
        val http = FakeHttp { _, url ->
            if (url.contains("isOwned=false")) ImmichHttpResponse(500, null)
            else ImmichHttpResponse(200, """[{"id":"a"}]""")
        }
        val filters = ImmichFilters(listOf("albumA"), emptyList(), emptyList())
        val result = ImmichRepository(http).fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(listOf("a"), (result as ImmichResult.Ok).value.map { it.id })
    }

    /** Ownership is resolved once per config, not once per batch. */
    @Test fun sharedAlbumLookupIsCachedAcrossBatches() {
        val http = FakeHttp { _, url ->
            if (url.contains("isOwned=false")) ImmichHttpResponse(200, "[]")
            else ImmichHttpResponse(200, """[{"id":"a"}]""")
        }
        val repo = ImmichRepository(http)
        val filters = ImmichFilters(listOf("albumA"), emptyList(), emptyList())
        repo.fetchSlideshowAssets(cfg, filters, 30)
        repo.fetchSlideshowAssets(cfg, filters, 30)
        assertEquals(1, http.requests.count { it.second.contains("isOwned=false") })
    }

    @Test fun fetchCurrentUserOkReturnsUser() {
        val http = FakeHttp { _, url ->
            assertTrue(url.endsWith("/api/users/me"))
            ImmichHttpResponse(200, """{"id":"u1","email":"jose@mail.com","name":"Jose"}""")
        }
        val result = ImmichRepository(http).fetchCurrentUser(cfg)
        assertEquals(ImmichResult.Ok(ImmichUser("u1", "Jose", "jose@mail.com")), result)
    }

    @Test fun fetchCurrentUser401IsAuthError() {
        val result = (ImmichRepository { _, _, _, _ -> ImmichHttpResponse(401, null) }).fetchCurrentUser(cfg)
        assertEquals(ImmichResult.Error(ImmichErrorKind.AUTH), result)
    }

    @Test fun fetchCurrentUser200ButUnparseableIsUnreachable() {
        val result = (ImmichRepository { _, _, _, _ -> ImmichHttpResponse(200, "not json") }).fetchCurrentUser(cfg)
        assertEquals(ImmichResult.Error(ImmichErrorKind.UNREACHABLE), result)
    }
}
