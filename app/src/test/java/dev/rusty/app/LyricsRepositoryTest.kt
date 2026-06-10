package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRepositoryTest {
    @Test
    fun parsesLineSyncedLyricsWithColorsAndProvider() {
        val json = """
            {"lyrics":{"syncType":"LINE_SYNCED","provider":"musixmatch",
              "lines":[
                {"startTimeMs":"0","words":"First line"},
                {"startTimeMs":"2400","words":"Second line"}],
              "colors":{"background":-9211021,"text":-16777216,"highlightText":-1}}}
        """.trimIndent()
        val result = LyricsRepository.parse(json)
        assertEquals(LyricsKind.SYNCED, result.kind)
        assertEquals(2, result.lines.size)
        assertEquals(0L, result.lines[0].startMs)
        assertEquals("Second line", result.lines[1].words)
        assertEquals(2400L, result.lines[1].startMs)
        assertEquals("musixmatch", result.provider)
        assertEquals(-9211021, result.bgColor)
        assertEquals(-1, result.highlightColor)
        assertEquals(-16777216, result.dimColor)
    }

    @Test
    fun parsesUnsyncedLyrics() {
        val json = """
            {"lyrics":{"syncType":"UNSYNCED","lines":[
              {"startTimeMs":"0","words":"No timing here"}]}}
        """.trimIndent()
        val result = LyricsRepository.parse(json)
        assertEquals(LyricsKind.UNSYNCED, result.kind)
        assertEquals(1, result.lines.size)
    }

    @Test
    fun emptyOrMissingLyricsIsNone() {
        assertEquals(LyricsKind.NONE, LyricsRepository.parse("{}").kind)
        assertEquals(LyricsKind.NONE, LyricsRepository.parse("""{"lyrics":{"syncType":"LINE_SYNCED","lines":[]}}""").kind)
    }

    @Test
    fun malformedJsonIsError() {
        assertEquals(LyricsKind.ERROR, LyricsRepository.parse("not json").kind)
    }

    @Test
    fun missingColorsLeaveNullsButStillSynced() {
        val json = """{"lyrics":{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"10","words":"x"}]}}"""
        val result = LyricsRepository.parse(json)
        assertEquals(LyricsKind.SYNCED, result.kind)
        assertTrue(result.bgColor == null && result.highlightColor == null)
    }
}
