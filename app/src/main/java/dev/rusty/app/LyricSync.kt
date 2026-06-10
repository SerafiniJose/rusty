package dev.rusty.app

/** Pure helper: which synced lyric line is active for a playback position. */
object LyricSync {
    /**
     * Returns the index of the last line whose start time is ≤ [positionMs], or −1 if [positionMs]
     * precedes the first line (or the list is empty). [startTimesMs] must be ascending.
     */
    fun activeIndex(startTimesMs: List<Long>, positionMs: Long): Int {
        var result = -1
        for (i in startTimesMs.indices) {
            if (startTimesMs[i] <= positionMs) result = i else break
        }
        return result
    }
}
