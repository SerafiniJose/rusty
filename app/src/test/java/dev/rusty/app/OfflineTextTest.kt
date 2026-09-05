package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflineTextTest {
    @Test
    fun `under a minute counts seconds`() {
        assertEquals("0 s", OfflineText.duration(-5_000))
        assertEquals("45 s", OfflineText.duration(45_400))
        assertEquals("59 s", OfflineText.duration(59_999))
    }

    @Test
    fun `under an hour counts whole minutes`() {
        assertEquals("1 min", OfflineText.duration(60_000))
        assertEquals("12 min", OfflineText.duration(12 * 60_000L + 59_000))
        assertEquals("59 min", OfflineText.duration(59 * 60_000L + 59_999))
    }

    @Test
    fun `under a day counts whole hours, then days`() {
        assertEquals("1 h", OfflineText.duration(3_600_000))
        assertEquals("3 h", OfflineText.duration(3 * 3_600_000L + 1_800_000))
        assertEquals("23 h", OfflineText.duration(24 * 3_600_000L - 1))
        assertEquals("1 d", OfflineText.duration(24 * 3_600_000L))
        assertEquals("2 d", OfflineText.duration(2 * 24 * 3_600_000L + 3_600_000))
    }
}
