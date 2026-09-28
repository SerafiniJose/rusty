package dev.rusty.app

/**
 * Pure timing and sizing rules for the announcement card — the panel that shows what the control
 * page asked the device to say while it is saying it.
 *
 * The card's life is the announcement's life: it goes up when [ControlService] hands the clip to a
 * playback pipeline and comes down when that pipeline settles. Two guards straddle that, and both
 * live here so they can be exercised without a device:
 *
 * - [MIN_DWELL_MS], because "Dinner!" settles faster than anyone across the room can read it, and a
 *   card that flashes is worse than no card;
 * - [MAX_DWELL_MS], because a wedged pipeline must never pin the card to the screen forever.
 *
 * Android-free on purpose (a plain JVM test), and time is a parameter rather than a clock read so
 * the rules are stated rather than observed.
 */
object AnnouncementCardModel {

    /** Floor on time-on-screen, measured from the moment the clip was handed off. */
    const val MIN_DWELL_MS = 2_500L

    /** Ceiling on time-on-screen, whatever the pipeline claims it is still doing. */
    const val MAX_DWELL_MS = 60_000L

    /** Announcement text size, in sp. */
    const val TEXT_SIZE_SP = 20f

    /** The step down once the text no longer fits [MAX_FULL_SIZE_LINES] — a long announcement
     *  should stay a card rather than growing into a takeover of the whole screen. */
    const val TEXT_SIZE_COMPACT_SP = 17f

    /** The most lines that keep [TEXT_SIZE_SP]. */
    const val MAX_FULL_SIZE_LINES = 3

    /**
     * Whether the card belongs on screen. [publishedAtMs] is null when nothing has been announced;
     * [settled] is the voicing pipeline reporting it has nothing left to finish.
     */
    fun visible(publishedAtMs: Long?, settled: Boolean, nowMs: Long): Boolean {
        if (publishedAtMs == null) return false
        val shown = nowMs - publishedAtMs
        if (shown >= MAX_DWELL_MS) return false
        return !settled || shown < MIN_DWELL_MS
    }

    /**
     * How long until [visible] could next change on its own, for a caller that can only re-check on
     * a timer. A settle arriving earlier is an event, not a timeout, so it is not this function's
     * business.
     */
    fun nextCheckDelayMs(publishedAtMs: Long?, settled: Boolean, nowMs: Long): Long {
        if (publishedAtMs == null) return 0
        val shown = nowMs - publishedAtMs
        val deadline = if (shown < MIN_DWELL_MS) MIN_DWELL_MS else MAX_DWELL_MS
        return (deadline - shown).coerceAtLeast(0)
    }

    /** The size [lineCount] lines of announcement should be set at, once measured at [TEXT_SIZE_SP]. */
    fun textSizeSp(lineCount: Int): Float =
        if (lineCount > MAX_FULL_SIZE_LINES) TEXT_SIZE_COMPACT_SP else TEXT_SIZE_SP
}
