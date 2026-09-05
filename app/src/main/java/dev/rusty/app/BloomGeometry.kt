package dev.rusty.app

/**
 * Pure geometry for parking the bloom clock in the top-right corner of its padded parent box.
 * Android-free so it is unit-testable; shared by [BloomController]'s animated morph and the shell's
 * static park of the clock over a non-Spotify feature, so the two always agree.
 */
object BloomGeometry {
    /** The corner shrink the clock animates to (and parks at). */
    const val CORNER_SCALE: Float = 0.22f

    /** Gap between the parked clock's right edge and the padded content box's right edge. */
    const val CORNER_SIDE_MARGIN_DP: Float = 24f

    /**
     * Gap between the parked clock's TOP edge and the padded content box's top edge. Negative on
     * purpose: the clock's scaled text box carries ~7dp of leading above the digit ink, and the
     * Spotify status row ("Salotto · Spotify Connect", 16sp) centres ~10dp below the same padded
     * edge. -5dp puts the digit ink on that row's centre line (measured on the Echo Show: both
     * centre 33dp below the inset edge), so the corner clock reads as part of the top row over
     * Spotify and over every parked feature alike.
     */
    const val CORNER_TOP_MARGIN_DP: Float = -5f

    /**
     * Translation (dx, dy) in px to move the clock from its current center to the top-right corner of
     * the padded content box, accounting for the corner shrink [cornerScale]. [marginPx] is the
     * side gap, [topMarginPx] the top gap (see [CORNER_TOP_MARGIN_DP] for why they differ).
     */
    fun cornerTranslation(
        parentWidth: Int,
        parentPaddingRight: Int,
        parentPaddingTop: Int,
        clockX: Float,
        clockY: Float,
        clockWidth: Int,
        clockHeight: Int,
        cornerScale: Float,
        marginPx: Float,
        topMarginPx: Float = marginPx,
    ): Pair<Float, Float> {
        val startCx = clockX + clockWidth / 2f
        val startCy = clockY + clockHeight / 2f
        val rightEdge = parentWidth - parentPaddingRight - marginPx
        val topEdge = parentPaddingTop + topMarginPx
        val targetCx = rightEdge - (clockWidth * cornerScale) / 2f
        val targetCy = topEdge + (clockHeight * cornerScale) / 2f
        return (targetCx - startCx) to (targetCy - startCy)
    }
}
