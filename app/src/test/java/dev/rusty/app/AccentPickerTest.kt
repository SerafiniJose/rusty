package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccentPickerTest {
    private val fallback = 0xFF1DB954.toInt()

    @Test fun returnsFallbackWhenNoCandidates() {
        assertEquals(fallback, AccentPicker.pick(listOf(null, null), fallback))
    }

    @Test fun picksFirstNonNullCandidate() {
        val orange = 0xFFE8893B.toInt()
        val blue = 0xFF5AA9E6.toInt()
        assertEquals(orange, AccentPicker.pick(listOf(null, orange, blue), fallback))
    }

    @Test fun lightensTooDarkCandidateForContrast() {
        val nearBlack = 0xFF101014.toInt()
        val result = AccentPicker.pick(listOf(nearBlack), fallback)
        assertTrue(
            "expected lightened result, luminance ${AccentPicker.luminance(result)}",
            AccentPicker.luminance(result) >= AccentPicker.MIN_LUMINANCE
        )
    }

    @Test fun keepsBrightCandidateUnchanged() {
        val bright = 0xFFE8893B.toInt()
        assertEquals(bright, AccentPicker.pick(listOf(bright), fallback))
    }
}
