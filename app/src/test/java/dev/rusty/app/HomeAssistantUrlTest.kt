package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantUrlTest {

    @Test fun blankBecomesNull() {
        assertNull(HomeAssistantUrl.normalize(null))
        assertNull(HomeAssistantUrl.normalize("   "))
    }

    @Test fun prependsHttpWhenNoScheme() {
        assertEquals("http://homeassistant.local:8123", HomeAssistantUrl.normalize("homeassistant.local:8123"))
    }

    @Test fun keepsExistingScheme() {
        assertEquals("https://ha.example.com", HomeAssistantUrl.normalize("https://ha.example.com"))
        assertEquals("http://1.2.3.4:8123/lovelace/0", HomeAssistantUrl.normalize("http://1.2.3.4:8123/lovelace/0"))
    }

    @Test fun trimsWhitespace() {
        assertEquals("http://x", HomeAssistantUrl.normalize("  http://x  "))
    }

    @Test fun rejectsNonHttpSchemes() {
        assertNull(HomeAssistantUrl.normalize("javascript://alert(1)"))
        assertNull(HomeAssistantUrl.normalize("ftp://host/x"))
        assertNull(HomeAssistantUrl.normalize("file:///etc/passwd"))
    }

    @Test fun rejectsSchemeWithoutHost() {
        assertNull(HomeAssistantUrl.normalize("http://"))
    }

    @Test fun originStripsPathAndDefaultPort() {
        assertEquals("http://ha.local:8123", HomeAssistantUrl.origin("http://ha.local:8123/lovelace/0"))
        assertEquals("https://ha.example.com", HomeAssistantUrl.origin("https://ha.example.com:443/x"))
        assertEquals("http://ha.local", HomeAssistantUrl.origin("HTTP://HA.local:80/"))
    }

    @Test fun originOfInvalidIsNull() {
        assertNull(HomeAssistantUrl.origin("javascript://x"))
        assertNull(HomeAssistantUrl.origin(null))
    }

    @Test fun sameOriginMatchesIgnoringPath() {
        val origin = HomeAssistantUrl.origin("http://ha.local:8123")
        assertTrue(HomeAssistantUrl.isSameOrigin("http://ha.local:8123/lovelace/1", origin))
        assertFalse(HomeAssistantUrl.isSameOrigin("http://evil.test/x", origin))
        assertFalse(HomeAssistantUrl.isSameOrigin("http://ha.local:8124/", origin))
        assertFalse(HomeAssistantUrl.isSameOrigin("http://ha.local:8123/x", null))
    }

    // ---- HomeAssistantUrl.childUrlOrNull -------------------------------------

    private val childBase = "http://192.168.2.78:8123"

    @Test fun childUrlOrNull_assemblesSimplePath() {
        assertEquals("http://192.168.2.78:8123/security", HomeAssistantUrl.childUrlOrNull(childBase, "/security"))
    }

    @Test fun childUrlOrNull_preservesQueryAndFragment() {
        assertEquals("http://192.168.2.78:8123/a?b=1#c", HomeAssistantUrl.childUrlOrNull(childBase, "/a?b=1#c"))
    }

    @Test fun childUrlOrNull_rejectsUserinfoEscape() {
        // "@evil.example/" has no leading '/', so naive base+path concatenation turns the trusted
        // host into userinfo and evil.example into the real host — must be rejected outright.
        assertNull(HomeAssistantUrl.childUrlOrNull(childBase, "@evil.example/"))
    }

    @Test fun childUrlOrNull_rejectsProtocolRelative() {
        // Lands in path position (not exploitable), but rejected anyway: never a legitimate
        // HA-reported path.
        assertNull(HomeAssistantUrl.childUrlOrNull(childBase, "//evil.example/"))
    }

    @Test fun childUrlOrNull_rejectsAbsoluteUrl() {
        assertNull(HomeAssistantUrl.childUrlOrNull(childBase, "https://evil.example"))
    }

    @Test fun childUrlOrNull_rejectsBlankOrNullPath() {
        assertNull(HomeAssistantUrl.childUrlOrNull(childBase, null))
        assertNull(HomeAssistantUrl.childUrlOrNull(childBase, ""))
    }

    @Test fun childUrlOrNull_rejectsInvalidBase() {
        assertNull(HomeAssistantUrl.childUrlOrNull(null, "/security"))
        assertNull(HomeAssistantUrl.childUrlOrNull("javascript://x", "/security"))
    }

    @Test fun childUrlOrNull_resultIsAlwaysSameOriginAsBase() {
        val url = HomeAssistantUrl.childUrlOrNull(childBase, "/lovelace/0")
        assertEquals(HomeAssistantUrl.origin(childBase), HomeAssistantUrl.origin(url))
    }
}
