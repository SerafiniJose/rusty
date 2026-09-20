package dev.rusty.app

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspAuthTest {
    private val nonce = "dcd98b7102dd2f0e8b11d0f600bfb0c093"

    @Test fun `md5 matches the RFC 1321 vector`() {
        assertEquals("9e107d9d372bb6826bd81d3542a419d6", RtspAuth.md5Hex("The quick brown fox jumps over the lazy dog"))
    }

    @Test fun `digest response follows RFC 2617 without qop`() {
        // HA1 = md5("rusty:rusty:hunter2"), HA2 = md5("DESCRIBE:rtsp://h/live"), response = md5(HA1:nonce:HA2).
        // Hardcoded (computed independently, not via RtspAuth.md5Hex) so this can't pass by
        // mirroring a bug shared between the assertion and the function it's pinning.
        assertEquals("d85b1bb0964ba2de865cd2ea67e73a0f", RtspAuth.digestResponse("rusty", "rusty", "hunter2", "DESCRIBE", "rtsp://h/live", nonce))
    }

    @Test fun `valid digest header is accepted, wrong password or user is not`() {
        fun header(pw: String, user: String = "rusty") =
            "Digest username=\"$user\", realm=\"rusty\", nonce=\"$nonce\", uri=\"rtsp://h/live\", response=\"${RtspAuth.digestResponse(user, "rusty", pw, "DESCRIBE", "rtsp://h/live", nonce)}\""
        val uri = "rtsp://h/live"
        assertTrue(RtspAuth.authorized(header("hunter2"), "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized(header("wrong"), "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized(header("hunter2", user = "admin"), "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized(header("hunter2"), "SETUP", uri, nonce, "hunter2"))     // method is part of HA2
        assertFalse(RtspAuth.authorized(header("hunter2"), "DESCRIBE", "rtsp://h/other", nonce, "hunter2"))  // header uri must match the request
        assertFalse(RtspAuth.authorized(header("hunter2"), "DESCRIBE", uri, "othernonce", "hunter2"))
    }

    @Test fun `basic header is accepted for user rusty only`() {
        val good = "Basic " + Base64.getEncoder().encodeToString("rusty:hunter2".toByteArray())
        val badUser = "Basic " + Base64.getEncoder().encodeToString("admin:hunter2".toByteArray())
        val uri = "rtsp://h/live"
        assertTrue(RtspAuth.authorized(good, "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized(badUser, "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized("Basic notbase64!!", "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized(null, "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized("Bearer hunter2", "DESCRIBE", uri, nonce, "hunter2"))
    }

    @Test fun `challenge carries digest then basic`() {
        val h = RtspAuth.challengeHeaders(nonce)
        assertEquals("WWW-Authenticate" to "Digest realm=\"rusty\", nonce=\"$nonce\"", h[0])
        assertEquals("WWW-Authenticate" to "Basic realm=\"rusty\"", h[1])
    }

    @Test fun `digest header missing the uri parameter is rejected`() {
        val uri = "rtsp://h/live"
        val response = RtspAuth.digestResponse("rusty", "rusty", "hunter2", "DESCRIBE", uri, nonce)
        val noUri = "Digest username=\"rusty\", realm=\"rusty\", nonce=\"$nonce\", response=\"$response\""
        assertFalse(RtspAuth.authorized(noUri, "DESCRIBE", uri, nonce, "hunter2"))
    }

    @Test fun `digest header with an empty response is rejected`() {
        val uri = "rtsp://h/live"
        val emptyResponse = "Digest username=\"rusty\", realm=\"rusty\", nonce=\"$nonce\", uri=\"$uri\", response=\"\""
        assertFalse(RtspAuth.authorized(emptyResponse, "DESCRIBE", uri, nonce, "hunter2"))
    }

    @Test fun `unquoted digest parameter values are accepted`() {
        val uri = "rtsp://h/live"
        val response = RtspAuth.digestResponse("rusty", "rusty", "hunter2", "DESCRIBE", uri, nonce)
        val unquoted = "Digest username=rusty, realm=rusty, nonce=$nonce, uri=$uri, response=$response"
        assertTrue(RtspAuth.authorized(unquoted, "DESCRIBE", uri, nonce, "hunter2"))
    }

    @Test fun `uppercase scheme and parameter names are accepted`() {
        val uri = "rtsp://h/live"
        val response = RtspAuth.digestResponse("rusty", "rusty", "hunter2", "DESCRIBE", uri, nonce)
        val upper = "DIGEST USERNAME=\"rusty\", REALM=\"rusty\", NONCE=\"$nonce\", URI=\"$uri\", RESPONSE=\"$response\""
        assertTrue(RtspAuth.authorized(upper, "DESCRIBE", uri, nonce, "hunter2"))
    }

    @Test fun `a scheme with no space and a whitespace-only header are rejected without throwing`() {
        val uri = "rtsp://h/live"
        assertFalse(RtspAuth.authorized("Digest", "DESCRIBE", uri, nonce, "hunter2"))
        assertFalse(RtspAuth.authorized("   ", "DESCRIBE", uri, nonce, "hunter2"))
    }
}
