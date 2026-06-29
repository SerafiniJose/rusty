package dev.rusty.app

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpotifyTokenProviderTest {

    @Test fun returnsCurrentTokenWithoutRefresh() = runTest {
        var refreshes = 0
        val provider = SpotifyTokenProvider(
            source = { "good" },
            refresh = { _ -> refreshes++; true },
        )
        assertEquals("good", provider.token(forceRefresh = false))
        assertEquals(0, refreshes)
    }

    @Test fun refreshesWhenNoTokenThenReturnsNew() = runTest {
        var current: String? = null
        var refreshes = 0
        val provider = SpotifyTokenProvider(
            source = { current },
            refresh = { _ -> refreshes++; current = "fresh"; true },
        )
        assertEquals("fresh", provider.token(forceRefresh = false))
        assertEquals(1, refreshes)
    }

    @Test fun forceRefreshTriggersEvenWithCurrentToken() = runTest {
        var current: String? = "stale"
        var refreshes = 0
        val provider = SpotifyTokenProvider(
            source = { current },
            refresh = { _ -> refreshes++; current = "rotated"; true },
        )
        assertEquals("rotated", provider.token(forceRefresh = true))
        assertEquals(1, refreshes)
    }

    @Test fun nullWhenSignalTimesOut() = runTest {
        var refreshes = 0
        val provider = SpotifyTokenProvider(
            source = { null },
            refresh = { _ -> refreshes++; false },
        )
        assertNull(provider.token(forceRefresh = false))
        assertEquals(1, refreshes)
    }
}
