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
    fun `portrait - one or two cameras are a single column, three to eight two, nine plus three`() {
        assertEquals(1, portrait(1))
        assertEquals(1, portrait(2))
        for (n in 3..8) assertEquals("n=$n", 2, portrait(n))
        // Corrected per hand-verified arithmetic: with content box 780x1260 and gap 12, 2 columns
        // give tile width 378, tile height 212, row pitch 224. n=9 and n=10 are 5 rows (1120 <=
        // 1260), so 2 columns still fit; 3 columns is only needed from n=11 (6 rows = 1344 > 1260).
        for (n in 9..10) assertEquals("n=$n", 2, portrait(n))
        for (n in 11..15) assertEquals("n=$n", 3, portrait(n))
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
