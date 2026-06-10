package dev.rusty.app

/** Outcome category for a lyrics lookup. */
enum class LyricsKind { SYNCED, UNSYNCED, NONE, ERROR }

/** One lyric line: its start time (ms) and text. */
data class LyricLine(val startMs: Long, val words: String)

/**
 * Parsed lyrics for a track. [bgColor]/[highlightColor]/[dimColor] are raw ARGB ints from the API
 * (may be null when absent — the UI then falls back to the artwork accent).
 */
data class LyricsResult(
    val kind: LyricsKind,
    val lines: List<LyricLine> = emptyList(),
    val bgColor: Int? = null,
    val highlightColor: Int? = null,
    val dimColor: Int? = null,
    val provider: String? = null
)
