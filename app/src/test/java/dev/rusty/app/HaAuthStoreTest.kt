package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HaAuthStoreTest {

    private val tokens = HaAuth.HaTokens("at", "rt", 99L)
    private val json = HaAuth.serializeTokens(tokens)

    @Test fun tokensReturnedForMatchingOrigin() {
        assertEquals(tokens,
            HaAuthStore.tokensForOrigin("http://ha.local:8123", json, "http://ha.local:8123"))
    }

    @Test fun originMismatchYieldsNothing() {
        // A token minted for one server must never be offered to another.
        assertNull(HaAuthStore.tokensForOrigin("http://ha.local:8123", json, "http://other:8123"))
    }

    @Test fun missingPiecesYieldNothing() {
        assertNull(HaAuthStore.tokensForOrigin(null, json, "http://ha.local:8123"))
        assertNull(HaAuthStore.tokensForOrigin("http://ha.local:8123", null, "http://ha.local:8123"))
        assertNull(HaAuthStore.tokensForOrigin("http://ha.local:8123", json, null))
    }
}
