package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAuthTest {

    // -- bearer parsing -----------------------------------------------------

    @Test fun bearerToken_extractsThePassword() {
        assertEquals("hunter2", ControlAuth.bearerToken("Bearer hunter2"))
    }

    @Test fun bearerToken_schemeIsCaseInsensitive() {
        assertEquals("hunter2", ControlAuth.bearerToken("bearer hunter2"))
        assertEquals("hunter2", ControlAuth.bearerToken("BEARER hunter2"))
    }

    @Test fun bearerToken_keepsInnerSpacesTrimsOuter() {
        // A password may legitimately contain spaces; only the framing whitespace goes.
        assertEquals("two words", ControlAuth.bearerToken("Bearer  two words "))
    }

    @Test fun bearerToken_rejectsOtherSchemesAndGarbage() {
        assertNull(ControlAuth.bearerToken("Basic aHVudGVyMg=="))
        assertNull(ControlAuth.bearerToken("Bearer"))
        assertNull(ControlAuth.bearerToken("Bearer   "))
        assertNull(ControlAuth.bearerToken(""))
        assertNull(ControlAuth.bearerToken(null))
    }

    // -- authorization decision ---------------------------------------------

    @Test fun authorized_whenNoPasswordIsRequired_everythingPasses() {
        assertTrue(ControlAuth.authorized(requiredPassword = null, authorizationHeader = null))
        assertTrue(ControlAuth.authorized(requiredPassword = null, authorizationHeader = "Bearer whatever"))
    }

    @Test fun authorized_matchingPasswordPasses() {
        assertTrue(ControlAuth.authorized("hunter2", "Bearer hunter2"))
    }

    @Test fun authorized_missingOrWrongPasswordFails() {
        assertFalse(ControlAuth.authorized("hunter2", null))
        assertFalse(ControlAuth.authorized("hunter2", "Bearer hunter3"))
        assertFalse(ControlAuth.authorized("hunter2", "Basic hunter2"))
        assertFalse(ControlAuth.authorized("hunter2", "hunter2"))
    }

    @Test fun authorized_passwordComparisonIsExact() {
        assertFalse(ControlAuth.authorized("hunter2", "Bearer Hunter2"))
        assertFalse(ControlAuth.authorized("hunter2", "Bearer hunter"))
        assertFalse(ControlAuth.authorized("hunter2", "Bearer hunter22"))
    }
}
