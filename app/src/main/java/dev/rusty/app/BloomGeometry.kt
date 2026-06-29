package dev.rusty.app

/**
 * Pure geometry for parking the bloom clock in the top-right corner of its padded parent box.
 * Android-free so it is unit-testable; shared by [BloomController]'s animated morph and the shell's
 * static park of the clock over a non-Spotify feature, so the two always agree.
 */
object BloomGeometry {
    /** The corner shrink the clock animates to (and parks at). */
    const val CORNER_SCALE: Float = 0.22f

    /**
     * Translation (dx, dy) in px to move the clock from its current center to the top-right corner of
     * the padded content box, accounting for the corner shrink [cornerScale].
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
    ): Pair<Float, Float> {
        val startCx = clockX + clockWidth / 2f
        val startCy = clockY + clockHeight / 2f
        val rightEdge = parentWidth - parentPaddingRight - marginPx
        val topEdge = parentPaddingTop + marginPx
        val targetCx = rightEdge - (clockWidth * cornerScale) / 2f
        val targetCy = topEdge + (clockHeight * cornerScale) / 2f
        return (targetCx - startCx) to (targetCy - startCy)
    }
}
