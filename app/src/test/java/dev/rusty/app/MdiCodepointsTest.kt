package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit coverage for the pure MDI name→codepoint parser/resolver. The Typeface load and glyph drawing
 * are Android-dependent and verified on-device; this guards the parsing + name-normalization logic.
 */
class MdiCodepointsTest {

    @Test fun parsesValidNameCodepointLines() {
        val map = MdiCodepoints.parse(
            sequenceOf("ab-testing F01C9", "baby-face-outline F0E7D", "map F034D")
        )
        assertEquals(0xF01C9, map["ab-testing"])
        assertEquals(0xF0E7D, map["baby-face-outline"])
        assertEquals(0xF034D, map["map"])
        assertEquals(3, map.size)
    }

    @Test fun skipsBlankAndMalformedLines() {
        val map = MdiCodepoints.parse(
            sequenceOf("", "   ", "onlyname", "name TWO PARTS EXTRA", "bad NOTHEX", "good F1234")
        )
        // Only the well-formed single "name HEX" line survives.
        assertEquals(mapOf("good" to 0xF1234), map)
    }

    @Test fun normalizeNameStripsMdiAndHassPrefixes() {
        assertEquals("baby-face-outline", MdiCodepoints.normalizeName("mdi:baby-face-outline"))
        assertEquals("baby-bottle", MdiCodepoints.normalizeName("hass:baby-bottle"))
        assertEquals("map", MdiCodepoints.normalizeName("  mdi:map  "))
        assertEquals("glasses", MdiCodepoints.normalizeName("glasses"))
    }

    @Test fun normalizeNameReturnsNullForBlankOrNull() {
        assertNull(MdiCodepoints.normalizeName(null))
        assertNull(MdiCodepoints.normalizeName(""))
        assertNull(MdiCodepoints.normalizeName("   "))
        assertNull(MdiCodepoints.normalizeName("mdi:"))
    }

    @Test fun codepointForResolvesViaNormalization() {
        val map = mapOf("baby-face-outline" to 0xF0E7D, "map" to 0xF034D)
        assertEquals(0xF0E7D, MdiCodepoints.codepointFor(map, "mdi:baby-face-outline"))
        assertEquals(0xF034D, MdiCodepoints.codepointFor(map, "hass:map"))
        // Brand/custom icons not in MDI (e.g. hacs:hacs, mdi:esphome when absent) → null.
        assertNull(MdiCodepoints.codepointFor(map, "hacs:hacs"))
        assertNull(MdiCodepoints.codepointFor(map, null))
    }
}
