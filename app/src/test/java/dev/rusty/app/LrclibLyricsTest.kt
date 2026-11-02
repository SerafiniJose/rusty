package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrclibLyricsTest {

    @Test
    fun parsesSyncedLrcIntoOrderedLines() {
        val lrc = """
            [ar:Kendrick Lamar]
            [ti:A.D.H.D]
            [00:12.34] First line
            [00:15.5]Second line
            [00:20:000] Third line
            [00:25.123][00:30.000] Repeated hook
        """.trimIndent()

        val lines = LrclibLyrics.parseLrc(lrc)

        assertEquals(5, lines.size)
        assertEquals(12_340L, lines[0].startMs)
        assertEquals("First line", lines[0].words)
        assertEquals(15_500L, lines[1].startMs)
        assertEquals("Second line", lines[1].words)
        assertEquals(20_000L, lines[2].startMs)
        assertEquals("Third line", lines[2].words)
        assertEquals(25_123L, lines[3].startMs)
        assertEquals("Repeated hook", lines[3].words)
        assertEquals(30_000L, lines[4].startMs)
    }

    @Test
    fun syncedLyricsWinOverPlainLyrics() {
        val json = """
            {"syncedLyrics":"[00:01.00] Hello\n[00:02.00] World","plainLyrics":"Hello\nWorld"}
        """.trimIndent()

        val result = LrclibLyrics.parse(json)

        assertEquals(LyricsKind.SYNCED, result.kind)
        assertEquals(2, result.lines.size)
        assertEquals("LRCLIB", result.provider)
    }

    @Test
    fun fallsBackToPlainLyrics() {
        val result = LrclibLyrics.parse("""{"plainLyrics":"One\n\nTwo"}""")

        assertEquals(LyricsKind.UNSYNCED, result.kind)
        assertEquals(listOf("One", "Two"), result.lines.map { it.words })
    }

    @Test
    fun missingLyricsAndErrorBodiesAreNone() {
        assertEquals(LyricsKind.NONE, LrclibLyrics.parse("""{"code":404,"name":"TrackNotFound"}""").kind)
        assertEquals(LyricsKind.NONE, LrclibLyrics.parse("""{"plainLyrics":""}""").kind)
        // A body that is not a JSON object is malformed rather than an empty result.
        assertEquals(LyricsKind.ERROR, LrclibLyrics.parse("[]").kind)
    }

    @Test
    fun metadataOnlyLrcIsEmpty() {
        assertTrue(LrclibLyrics.parseLrc("[ar:Kendrick]\n[by:someone]").isEmpty())
    }
}
