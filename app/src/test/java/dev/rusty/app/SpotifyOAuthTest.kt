package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyOAuthTest {
    @Test
    fun pkceChallengeMatchesRfc7636Vector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            SpotifyOAuth.challenge(verifier),
        )
    }

    @Test
    fun authorizationUrlCarriesClientRedirectScopeAndPkce() {
        val url = SpotifyOAuth.authorizationUrl("state123", "verifier")
        assertTrue(url.startsWith("https://accounts.spotify.com/authorize?"))
        assertTrue(url.contains("client_id=${SpotifyOAuth.CLIENT_ID}"))
        assertTrue(url.contains("response_type=code"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A25566%2Fcallback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("state=state123"))
    }

    @Test
    fun redirectExtractsCodeOnlyWhenStateMatches() {
        val url = "http://127.0.0.1:25566/callback?code=abc123&state=xyz"
        val result = SpotifyOAuth.parseRedirect(url, "xyz")
        assertTrue(result is SpotifyOAuth.Redirect.Code)
        assertEquals("abc123", (result as SpotifyOAuth.Redirect.Code).code)
    }

    @Test
    fun redirectRejectsWrongStateAndSpotifyErrors() {
        val wrongState = SpotifyOAuth.parseRedirect(
            "http://127.0.0.1:25566/callback?code=abc&state=other",
            "xyz",
        )
        assertTrue(wrongState is SpotifyOAuth.Redirect.Failure)

        val denied = SpotifyOAuth.parseRedirect(
            "http://127.0.0.1:25566/callback?error=access_denied&state=xyz",
            "xyz",
        )
        assertTrue(denied is SpotifyOAuth.Redirect.Failure)
        assertTrue((denied as SpotifyOAuth.Redirect.Failure).message.contains("cancelled"))
    }

    @Test
    fun ignoresUnrelatedLoginUrls() {
        val result = SpotifyOAuth.parseRedirect("https://accounts.spotify.com/en/login", "xyz")
        assertTrue(result is SpotifyOAuth.Redirect.Ignored)
    }

    @Test
    fun parsesAccessAndRotatedRefreshTokens() {
        val body = """{"access_token":"new-access","token_type":"Bearer","expires_in":3600}"""
        val tokens = SpotifyOAuth.parseTokenResponse(body, nowMs = 1_000L, previousRefreshToken = "old-refresh")
        assertNotNull(tokens)
        assertEquals("new-access", tokens!!.accessToken)
        assertEquals("old-refresh", tokens.refreshToken)
        assertEquals(1_000L + 3_600_000L, tokens.expiresAtMs)
        assertTrue(tokens.isValid(1_000L))
        assertTrue(!tokens.isValid(1_000L + 3_600_000L))
    }

    @Test
    fun unreadableTokenResponseIsRejected() {
        assertEquals(null, SpotifyOAuth.parseTokenResponse("{}", 0L))
        assertEquals(null, SpotifyOAuth.parseTokenResponse("not json", 0L))
    }
}
