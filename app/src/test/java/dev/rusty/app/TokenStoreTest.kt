package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TokenStoreTest {
    @Before
    fun setUp() {
        TokenStore.clear()
    }

    @Test
    fun validWithinExpiryWindow() {
        TokenStore.update("abc", expiresInSecs = 3600, nowMs = 1_000_000L)
        assertEquals("abc", TokenStore.accessToken)
        assertTrue(TokenStore.isValid(nowMs = 1_000_000L))
    }

    @Test
    fun sixtySecondTokenConsideredExpiredImmediately() {
        TokenStore.update("abc", expiresInSecs = 60, nowMs = 0L)
        // 60s token minus the 60s safety margin => already considered expired.
        assertFalse(TokenStore.isValid(nowMs = 1_000L))
    }

    @Test
    fun clearedTokenIsInvalid() {
        TokenStore.update("abc", expiresInSecs = 3600, nowMs = 0L)
        TokenStore.clear()
        assertFalse(TokenStore.isValid(nowMs = 0L))
        assertEquals(null, TokenStore.accessToken)
    }
}
