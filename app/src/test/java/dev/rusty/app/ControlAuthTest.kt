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

    @Test fun authorizedAllowingBasic_whenNoPasswordIsRequired_everythingPasses() {
        assertTrue(ControlAuth.authorizedAllowingBasic(requiredPassword = null, authorizationHeader = null))
        assertTrue(ControlAuth.authorizedAllowingBasic(requiredPassword = null, authorizationHeader = "Bearer whatever"))
    }

    @Test fun authorizedAllowingBasic_matchingPasswordPasses() {
        assertTrue(ControlAuth.authorizedAllowingBasic("hunter2", "Bearer hunter2"))
    }

    @Test fun authorizedAllowingBasic_missingOrWrongPasswordFails() {
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", null))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "Bearer hunter3"))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "Basic hunter2"))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "hunter2"))
    }

    @Test fun authorizedAllowingBasic_passwordComparisonIsExact() {
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "Bearer Hunter2"))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "Bearer hunter"))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "Bearer hunter22"))
    }

    // -- basic parsing (the local-snapshot fetcher) --------------------------

    @Test fun authorizedAllowingBasic_acceptsBasicForUserRusty() {
        val good = "Basic " + java.util.Base64.getEncoder().encodeToString("rusty:hunter2".toByteArray())
        val badUser = "Basic " + java.util.Base64.getEncoder().encodeToString("admin:hunter2".toByteArray())
        val badPass = "Basic " + java.util.Base64.getEncoder().encodeToString("rusty:nope".toByteArray())
        assertTrue(ControlAuth.authorizedAllowingBasic("hunter2", good))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", badUser))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", badPass))
        assertFalse(ControlAuth.authorizedAllowingBasic("hunter2", "Basic %%%"))
        assertEquals("hunter2", ControlAuth.basicPassword(good))
        assertNull(ControlAuth.basicPassword(badUser))
    }

    // -- route scoping: authorized is the general gate and never takes Basic -------------------

    @Test fun authorized_neverAcceptsBasic_evenWithCorrectCredentials() {
        val basic = "Basic " + java.util.Base64.getEncoder().encodeToString("rusty:hunter2".toByteArray())
        assertFalse(ControlAuth.authorized("hunter2", basic))
        assertTrue(ControlAuth.authorized("hunter2", "Bearer hunter2"))
        assertTrue(ControlAuth.authorized(requiredPassword = null, authorizationHeader = null))
    }
}
