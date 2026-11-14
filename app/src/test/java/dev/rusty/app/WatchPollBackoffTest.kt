package dev.rusty.app

import dev.rusty.app.WatchTheme.Companion.rateLimitBackoff
import org.junit.Assert.assertEquals
import org.junit.Test

class WatchPollBackoffTest {
    @Test fun `first 429 with a server hint uses the hint`() {
        assertEquals(16_000L, rateLimitBackoff(retryAfterMs = 16_000L, waits = 1))
    }

    @Test fun `first 429 without a hint waits the base interval`() {
        assertEquals(30_000L, rateLimitBackoff(retryAfterMs = null, waits = 1))
    }

    @Test fun `repeated 429s double the wait`() {
        assertEquals(40_000L, rateLimitBackoff(retryAfterMs = null, waits = 3))
    }

    @Test fun `the doubling stops at five minutes`() {
        assertEquals(300_000L, rateLimitBackoff(retryAfterMs = null, waits = 9))
    }
}
