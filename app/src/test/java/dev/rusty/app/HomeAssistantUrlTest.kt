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
}
