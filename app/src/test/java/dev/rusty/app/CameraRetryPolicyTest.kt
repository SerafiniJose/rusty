package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for [CameraRetryPolicy] and [CameraUri]. */
class CameraRetryPolicyTest {

    // ---- classify: io range (2000..2008), message-based on real device shape ----

    @Test
    fun classify2004WithSpaced401IsFatalAuth() {
        assertEquals(StreamErrorKind.FATAL_AUTH, CameraRetryPolicy.classify(2004, " 401 "))
    }

    @Test
    fun classify2000WithRtspDescribe401IsFatalAuth() {
        // Real-device shape: errorCode is always the unspecified 2000, status folded into message.
        assertEquals(
            StreamErrorKind.FATAL_AUTH,
            CameraRetryPolicy.classify(2000, "RTSP DESCRIBE 401 Unauthorized"),
        )
    }

    @Test
    fun classify2000WithNullMessageIsTransient() {
        assertEquals(StreamErrorKind.TRANSIENT, CameraRetryPolicy.classify(2000, null))
    }

    @Test
    fun classify2000With403IsFatalAuth() {
        assertEquals(StreamErrorKind.FATAL_AUTH, CameraRetryPolicy.classify(2000, "403 Forbidden"))
    }

    @Test
    fun classify2000WithUnauthorizedWordIsFatalAuth() {
        assertEquals(
            StreamErrorKind.FATAL_AUTH,
            CameraRetryPolicy.classify(2005, "server returned unauthorized"),
        )
    }

    @Test
    fun classify2000With404IsFatalNotFound() {
        assertEquals(StreamErrorKind.FATAL_NOT_FOUND, CameraRetryPolicy.classify(2000, "404 Not Found"))
    }

    @Test
    fun classify2000With454IsFatalNotFound() {
        assertEquals(
            StreamErrorKind.FATAL_NOT_FOUND,
            CameraRetryPolicy.classify(2003, "454 Session Not Found"),
        )
    }

    @Test
    fun classify2000WithNotFoundPhraseIsFatalNotFound() {
        assertEquals(
            StreamErrorKind.FATAL_NOT_FOUND,
            CameraRetryPolicy.classify(2000, "rtsp session not found on server"),
        )
    }

    @Test
    fun classify2000With461IsFatalUnsupported() {
        assertEquals(StreamErrorKind.FATAL_UNSUPPORTED, CameraRetryPolicy.classify(2000, "461 Unsupported Transport"))
    }

    @Test
    fun classify2000WithUnsupportedTransportPhraseIsFatalUnsupported() {
        assertEquals(
            StreamErrorKind.FATAL_UNSUPPORTED,
            CameraRetryPolicy.classify(2008, "Unsupported transport requested"),
        )
    }

    @Test
    fun classify2000WithUnmatchedMessageIsTransient() {
        assertEquals(
            StreamErrorKind.TRANSIENT,
            CameraRetryPolicy.classify(2000, "connection reset by peer"),
        )
    }

    @Test
    fun classify2008BoundaryWithoutMarkerIsTransient() {
        assertEquals(StreamErrorKind.TRANSIENT, CameraRetryPolicy.classify(2008, "timeout"))
    }

    // ---- classify: digit-boundary anchoring — status codes must not false-positive on embedded numbers ----

    @Test
    fun classifyPortEmbedding401DoesNotFalsePositiveOnAuth() {
        assertEquals(
            StreamErrorKind.TRANSIENT,
            CameraRetryPolicy.classify(2000, "Unable to connect to rtsp://cam.local:8401/live"),
        )
    }

    @Test
    fun classifyMillisecondCountEmbedding401DoesNotFalsePositiveOnAuth() {
        assertEquals(StreamErrorKind.TRANSIENT, CameraRetryPolicy.classify(2000, "reconnect in 1401ms"))
    }

    @Test
    fun classifyPortNumberDoesNotFalsePositiveOnUnsupported() {
        assertEquals(StreamErrorKind.TRANSIENT, CameraRetryPolicy.classify(2000, "connecting on port 4540"))
    }

    @Test
    fun classifyRealDescribe401ShapeStillMatchesFatalAuth() {
        assertEquals(
            StreamErrorKind.FATAL_AUTH,
            CameraRetryPolicy.classify(2000, "RTSP DESCRIBE 401 Unauthorized"),
        )
    }

    @Test
    fun classifyRealDescribe404ShapeStillMatchesFatalNotFound() {
        assertEquals(
            StreamErrorKind.FATAL_NOT_FOUND,
            CameraRetryPolicy.classify(2000, "RTSP/1.0 404 Not Found"),
        )
    }

    @Test
    fun classifyReal461ShapeStillMatchesFatalUnsupported() {
        assertEquals(
            StreamErrorKind.FATAL_UNSUPPORTED,
            CameraRetryPolicy.classify(2000, "461 Unsupported Transport"),
        )
    }

    // ---- classify: parsing (3001..3004) and decoder (4001..4005) ranges ----

    @Test
    fun classify3003IsFatalUnsupported() {
        assertEquals(StreamErrorKind.FATAL_UNSUPPORTED, CameraRetryPolicy.classify(3003, "malformed SDP"))
    }

    @Test
    fun classify3001IsFatalUnsupportedRegardlessOfMessage() {
        assertEquals(StreamErrorKind.FATAL_UNSUPPORTED, CameraRetryPolicy.classify(3001, null))
    }

    @Test
    fun classify4001IsFatalUnsupported() {
        assertEquals(StreamErrorKind.FATAL_UNSUPPORTED, CameraRetryPolicy.classify(4001, "decoder init failed"))
    }

    @Test
    fun classify4005IsFatalUnsupportedRegardlessOfMessage() {
        assertEquals(StreamErrorKind.FATAL_UNSUPPORTED, CameraRetryPolicy.classify(4005, null))
    }

    // ---- classify: unknown codes ----

    @Test
    fun classifyUnknownCode1000IsTransient() {
        assertEquals(StreamErrorKind.TRANSIENT, CameraRetryPolicy.classify(1000, "whatever"))
    }

    // ---- nextDelayMs backoff sequence ----

    @Test
    fun backoffSequenceIsTwoFourEightThenFifteenSeconds() {
        val delays = (0..4).map { CameraRetryPolicy.nextDelayMs(it) }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L), delays)
    }

    // ---- shouldResetAttempts threshold ----

    @Test
    fun resetThresholdIsFalseJustBelowThirtySeconds() {
        assertFalse(CameraRetryPolicy.shouldResetAttempts(29_999L))
    }

    @Test
    fun resetThresholdIsTrueAtThirtySeconds() {
        assertTrue(CameraRetryPolicy.shouldResetAttempts(30_000L))
    }

    // ---- CameraUri.withCredentials ----

    @Test
    fun withCredentialsPercentEncodesUserinfo() {
        assertEquals(
            "rtsp://ad%40min:p%3Aw@h:554/s",
            CameraUri.withCredentials("rtsp://h:554/s", "ad@min", "p:w"),
        )
    }

    @Test
    fun withCredentialsReplacesExistingUserinfo() {
        assertEquals(
            "rtsp://newuser:newpass@h:554/s",
            CameraUri.withCredentials("rtsp://olduser:oldpass@h:554/s", "newuser", "newpass"),
        )
    }

    @Test
    fun withCredentialsWithNullUsernameLeavesUrlUnchanged() {
        val url = "rtsp://h:554/s"
        assertEquals(url, CameraUri.withCredentials(url, null, "somepass"))
    }

    @Test
    fun withCredentialsWithBlankUsernameLeavesUrlUnchanged() {
        val url = "rtsp://h:554/s"
        assertEquals(url, CameraUri.withCredentials(url, "  ", "somepass"))
    }

    @Test
    fun withCredentialsWithNullPasswordOmitsColon() {
        assertEquals(
            "rtsp://user@h:554/s",
            CameraUri.withCredentials("rtsp://h:554/s", "user", null),
        )
    }

    @Test
    fun withCredentialsPercentEncodesNonAsciiPassword() {
        assertEquals(
            "rtsp://user:%C3%A9@h:554/s",
            CameraUri.withCredentials("rtsp://h:554/s", "user", "é"),
        )
    }

    @Test
    fun withCredentialsPercentEncodesUnescapedAtInPassword() {
        assertEquals(
            "rtsp://user:p%40ss@h:554/s",
            CameraUri.withCredentials("rtsp://h:554/s", "user", "p@ss"),
        )
    }

    // ---- CameraUri.redact ----

    @Test
    fun redactHidesUserinfoAndNeverEqualsInputWhenCredsPresent() {
        val url = "rtsp://user:pass@host:554/stream"
        val redacted = CameraUri.redact(url)
        assertEquals("rtsp://•••@host:554/stream", redacted)
        assertNotEquals(url, redacted)
    }

    @Test
    fun redactWorksForHttpAndHttps() {
        assertEquals("http://•••@host/path", CameraUri.redact("http://user:pass@host/path"))
        assertEquals("https://•••@host/path", CameraUri.redact("https://user:pass@host/path"))
    }

    @Test
    fun redactHandlesUnescapedAtInPasswordViaLastIndexOf() {
        // An unescaped '@' inside the password is ambiguous per RFC 3986, but redact must still
        // treat everything up to the LAST '@' as userinfo, so the host is never leaked.
        assertEquals(
            "rtsp://•••@host:554/stream",
            CameraUri.redact("rtsp://user:p@ss@host:554/stream"),
        )
    }

    @Test
    fun redactLeavesUrlWithoutUserinfoUnchanged() {
        val url = "rtsp://host:554/stream"
        assertEquals(url, CameraUri.redact(url))
    }
}
