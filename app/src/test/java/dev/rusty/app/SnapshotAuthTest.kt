package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot fetcher's 401 handling: a Reolink-style camera answers a Basic (or anonymous)
 * request to `cgi-bin/api.cgi?cmd=onvifSnapPic` with a Digest challenge and only serves the JPEG
 * to a digest-authenticated retry.
 */
class SnapshotAuthTest {

    private val challenge =
        """Digest realm="IPCamera", nonce="0a1b2c3d4e5f6071", qop="auth", opaque="deadbeef""""

    @Test
    fun `digest challenge yields a digest authorization for the retry`() {
        val header = SnapshotAuth.digestRetry(
            user = "admin",
            pass = "secret",
            method = "GET",
            uri = "/cgi-bin/api.cgi?cmd=onvifSnapPic&channel=0",
            challenge = challenge,
            cnonce = "00112233aabbccdd",
        )

        assertNotNull(header)
        val value = header!!
        assertTrue(value, value.startsWith("Digest "))
        assertTrue(value, value.contains("""username="admin""""))
        assertTrue(value, value.contains("""realm="IPCamera""""))
        assertTrue(value, value.contains("""nonce="0a1b2c3d4e5f6071""""))
        assertTrue(value, value.contains("""uri="/cgi-bin/api.cgi?cmd=onvifSnapPic&channel=0""""))
        assertTrue(value, value.contains("""response="""))
        assertTrue(value, value.contains("qop=auth"))
        assertTrue(value, value.contains("nc=00000001"))
        assertTrue(value, value.contains("""cnonce="00112233aabbccdd""""))
        assertTrue(value, value.contains("""opaque="deadbeef""""))
    }

    @Test
    fun `the retry header is exactly what the ONVIF digest builder produces`() {
        val expected = OnvifSoap.httpDigestAuthorization(
            username = "admin",
            password = "secret",
            method = "GET",
            uri = "/cgi-bin/api.cgi?cmd=onvifSnapPic&channel=0",
            challenge = challenge,
            cnonce = "00112233aabbccdd",
        )

        assertEquals(
            expected,
            SnapshotAuth.digestRetry(
                user = "admin",
                pass = "secret",
                method = "GET",
                uri = "/cgi-bin/api.cgi?cmd=onvifSnapPic&channel=0",
                challenge = challenge,
                cnonce = "00112233aabbccdd",
            ),
        )
    }

    @Test
    fun `a missing password still answers the challenge`() {
        assertNotNull(
            SnapshotAuth.digestRetry("admin", null, "GET", "/snap.jpg", challenge, "aabb"),
        )
    }

    @Test
    fun `a Basic challenge is not retried`() {
        assertNull(
            SnapshotAuth.digestRetry("admin", "secret", "GET", "/snap.jpg", """Basic realm="x"""", "aabb"),
        )
    }

    @Test
    fun `no credentials means no retry`() {
        assertNull(SnapshotAuth.digestRetry(null, null, "GET", "/snap.jpg", challenge, "aabb"))
        assertNull(SnapshotAuth.digestRetry("", "secret", "GET", "/snap.jpg", challenge, "aabb"))
    }

    @Test
    fun `a missing challenge header means no retry`() {
        assertNull(SnapshotAuth.digestRetry("admin", "secret", "GET", "/snap.jpg", null, "aabb"))
        assertNull(SnapshotAuth.digestRetry("admin", "secret", "GET", "/snap.jpg", "  ", "aabb"))
    }

    @Test
    fun `a lowercase or padded scheme name is still a digest challenge`() {
        assertNotNull(
            SnapshotAuth.digestRetry("admin", "secret", "GET", "/snap.jpg", """  digest realm="x", nonce="y"""", "aabb"),
        )
    }

    @Test
    fun `the request target is the path plus query`() {
        assertEquals(
            "/cgi-bin/api.cgi?cmd=onvifSnapPic&channel=0",
            SnapshotAuth.requestTarget("http://192.168.4.90:80/cgi-bin/api.cgi?cmd=onvifSnapPic&channel=0"),
        )
        assertEquals("/snap.jpg", SnapshotAuth.requestTarget("http://cam.local/snap.jpg"))
        assertEquals("/", SnapshotAuth.requestTarget("http://cam.local"))
        assertEquals("/", SnapshotAuth.requestTarget("http://cam.local:8000"))
    }

    @Test
    fun `each cnonce is fresh hex`() {
        val a = SnapshotAuth.cnonce()
        val b = SnapshotAuth.cnonce()
        assertEquals(16, a.length)
        assertTrue(a, a.all { it in "0123456789abcdef" })
        assertTrue("cnonce repeated", a != b)
    }
}
