package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class GridFitTest {
    private val gap = 12

    private fun landscape(n: Int) = GridFit.columns(n, 1260, 780, gap, minCols = 2)
    private fun portrait(n: Int) = GridFit.columns(n, 780, 1260, gap, minCols = 1)

    @Test
    fun `landscape - up to four cameras keep two columns`() {
        for (n in 0..4) assertEquals("n=$n", 2, landscape(n))
    }

    @Test
    fun `landscape - five to nine need three columns, ten to sixteen four`() {
        for (n in 5..9) assertEquals("n=$n", 3, landscape(n))
        for (n in 10..16) assertEquals("n=$n", 4, landscape(n))
    }

    @Test
    fun `landscape - beyond what four columns can fit stays at four (grid scrolls)`() {
        assertEquals(4, landscape(17))
        assertEquals(4, landscape(40))
    }

    @Test
    fun `portrait - up to three cameras stack in one column, four to twelve two, thirteen plus three`() {
        assertEquals(1, portrait(1))
        assertEquals(1, portrait(2))
        // Three stack: the tiles shrink to 94 % of the width rather than go 2 + 1.
        assertEquals(1, portrait(3))
        for (n in 4..8) assertEquals("n=$n", 2, portrait(n))
        // Content box 780x1260, gap 12: two full-width columns give 378 px tiles, 224 px row pitch,
        // so five rows (n = 9, 10) fit outright. Six rows (n = 11, 12) fit by shrinking the tiles
        // to 352 px, 93 % of full, still inside the tolerance; seven rows (n = 13) would need 79 %,
        // so three columns take over there.
        for (n in 9..12) assertEquals("n=$n", 2, portrait(n))
        for (n in 13..15) assertEquals("n=$n", 3, portrait(n))
    }

    @Test
    fun `a page of four on a 16 by 10 screen keeps two columns and shrinks the tiles to the height`() {
        // Echo Show: 1260 px wide box, but only 640 px tall once the clock strip and the page row
        // are taken. Two full-width 16:9 tiles per row need 718 px; instead of 3 + 1 columns the
        // tiles shrink to 547 px (89 % of 618, within tolerance) and the grid centres at 1118 px.
        assertEquals(2, GridFit.columns(4, 1260, 640, gap, minCols = 2))
        assertEquals(618, GridFit.fullTileWidthPx(2, 1260, gap))
        assertEquals(547, GridFit.tileWidthPx(4, 2, 1260, 640, gap))
        assertEquals(1118, GridFit.fittedWidthPx(4, 2, 1260, 640, gap))
        // Nothing to shrink when the rows fit: the content is as wide as the box.
        assertEquals(1260, GridFit.fittedWidthPx(4, 2, 1260, 780, gap))
        // Too tight to shrink (five tiles in two columns would be 72 % wide): the next count wins.
        assertEquals(3, GridFit.columns(5, 1260, 780, gap, minCols = 2))
    }

    @Test
    fun `content height and centring padding`() {
        // 2 cols in 1260: tile 618 wide, 347 tall (integer), pitch 359, two rows = 718.
        assertEquals(718, GridFit.contentHeightPx(4, 2, 1260, gap))
        assertEquals((780 - 718) / 2, GridFit.topPaddingPx(4, 2, 1260, 780, gap))
        // Does not fit -> no extra padding.
        assertEquals(0, GridFit.topPaddingPx(17, 4, 1260, 780, gap))
        // Nothing to show -> nothing to centre.
        assertEquals(0, GridFit.topPaddingPx(0, 2, 1260, 780, gap))
    }

    @Test
    fun `pager slices in order and clamps the page`() {
        val items = (1..7).toList()
        assertEquals(2, Pager.pageCount(7, 4))
        assertEquals(1, Pager.pageCount(0, 4))
        assertEquals(listOf(1, 2, 3, 4), Pager.slice(items, 4, 0))
        assertEquals(listOf(5, 6, 7), Pager.slice(items, 4, 1))
        assertEquals(listOf(5, 6, 7), Pager.slice(items, 4, 9))
        assertEquals(1, Pager.clamp(9, 7, 4))
        assertEquals(0, Pager.clamp(-1, 7, 4))
        assertEquals(0, Pager.clamp(3, 0, 4))
    }
}
