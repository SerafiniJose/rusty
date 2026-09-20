package dev.rusty.app

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared Basic credential. The last test is the point of the whole object: the RTSP gate and
 * the snapshot gate must accept the SAME credential, and before [BasicAuth] each owned a username
 * constant and a decoder, so a change to one would have made them disagree with nothing to notice.
 */
class BasicAuthTest {

    private fun header(user: String, password: String): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))

    @Test
    fun `decodes the password for the fixed user`() {
        assertEquals("hunter2", BasicAuth.passwordFromHeader(header(BasicAuth.USER, "hunter2")))
    }

    @Test
    fun `keeps a password containing colons and spaces intact`() {
        assertEquals("a:b c", BasicAuth.passwordFromHeader(header(BasicAuth.USER, "a:b c")))
    }

    @Test
    fun `rejects another user, a non-Basic scheme, junk and absence`() {
        assertNull(BasicAuth.passwordFromHeader(header("admin", "hunter2")))
        assertNull(BasicAuth.passwordFromHeader("Bearer hunter2"))
        assertNull(BasicAuth.passwordFromHeader("Basic !!!not-base64!!!"))
        assertNull(BasicAuth.passwordFromHeader("Basic"))
        assertNull(BasicAuth.passwordFromHeader(null))
    }

    @Test
    fun `tolerates the line breaks Android's base64 encoder wraps a long credential with`() {
        val long = "p".repeat(120)
        val wrapped = Base64.getMimeEncoder().encodeToString("${BasicAuth.USER}:$long".toByteArray(Charsets.UTF_8))
        assertTrue("the fixture must actually wrap", wrapped.contains("\n"))
        assertEquals(long, BasicAuth.passwordFromHeader("Basic $wrapped"))
    }

    @Test
    fun `the RTSP gate and the snapshot gate accept the same credential`() {
        val credential = header(BasicAuth.USER, "hunter2")

        assertTrue(RtspAuth.authorized(credential, "DESCRIBE", "rtsp://d/live", "nonce", "hunter2"))
        assertTrue(ControlAuth.authorizedAllowingBasic("hunter2", credential))

        // ...and reject the same wrong one.
        val wrong = header(BasicAuth.USER, "hunter3")
        assertFalse(RtspAuth.authorized(wrong, "DESCRIBE", "rtsp://d/live", "nonce", "hunter2"))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", wrong))
    }
}
