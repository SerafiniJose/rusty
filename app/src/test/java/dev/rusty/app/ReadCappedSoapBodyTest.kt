package dev.rusty.app

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * [readCappedSoapBody] is [AndroidSoapTransport]'s bound on an ONVIF SOAP response (I3): the
 * transport itself stays thin, untested I/O (`HttpURLConnection`), but the cap logic it delegates
 * to is a pure function over any [java.io.InputStream] and is exercised directly here rather than
 * through real sockets.
 */
class ReadCappedSoapBodyTest {

    @Test
    fun `a body under the cap is read in full`() {
        val body = "hello world".toByteArray(Charsets.UTF_8)
        val result = readCappedSoapBody(ByteArrayInputStream(body), maxBytes = 1024L)
        assertArrayEquals(body, result)
    }

    @Test
    fun `a body exactly at the cap is accepted`() {
        val body = ByteArray(1024) { 'x'.code.toByte() }
        val result = readCappedSoapBody(ByteArrayInputStream(body), maxBytes = 1024L)
        assertEquals(1024, result.size)
    }

    @Test
    fun `a body one byte over the cap is rejected rather than truncated`() {
        val body = ByteArray(1025) { 'x'.code.toByte() }
        try {
            readCappedSoapBody(ByteArrayInputStream(body), maxBytes = 1024L)
            fail("expected an IOException once the cap is exceeded")
        } catch (e: IOException) {
            // expected: over-cap is a transport failure, not a truncated read.
        }
    }

    @Test
    fun `a never-ending stream is aborted at the cap instead of growing without bound`() {
        // Simulates the failure scenario in the finding: a firmware that never stops sending.
        val infinite = object : java.io.InputStream() {
            override fun read(): Int = 'a'.code
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                java.util.Arrays.fill(b, off, off + len, 'a'.code.toByte())
                return len
            }
        }
        try {
            readCappedSoapBody(infinite, maxBytes = 4096L)
            fail("expected an IOException before the stream (which never ends) was fully read")
        } catch (e: IOException) {
            // expected
        }
    }

    @Test
    fun `an empty body reads as an empty array`() {
        val result = readCappedSoapBody(ByteArrayInputStream(ByteArray(0)), maxBytes = 1024L)
        assertEquals(0, result.size)
    }
}
