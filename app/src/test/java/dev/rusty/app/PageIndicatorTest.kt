package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page indicator's model: what the numbered row shows for a given camera count, page size and
 * current page. Keeps the row's visibility and labelling out of the fragment's view code.
 */
class PageIndicatorTest {

    @Test
    fun `numbers one per page, current one selected`() {
        val model = PageIndicator.model(count = 9, pageSize = 4, page = 1)
        assertTrue(model.visible)
        assertEquals(listOf("1", "2", "3"), model.labels)
        assertEquals(1, model.selected)
    }

    @Test
    fun `hidden when the layout is not paged`() {
        val model = PageIndicator.model(count = 12, pageSize = null, page = 0)
        assertFalse(model.visible)
        assertEquals(emptyList<String>(), model.labels)
    }

    @Test
    fun `hidden when everything fits on a single page`() {
        assertFalse(PageIndicator.model(count = 4, pageSize = 4, page = 0).visible)
        assertFalse(PageIndicator.model(count = 1, pageSize = 8, page = 0).visible)
    }

    @Test
    fun `hidden when there are no cameras at all`() {
        assertFalse(PageIndicator.model(count = 0, pageSize = 4, page = 0).visible)
    }

    @Test
    fun `a page beyond the end selects the last page rather than nothing`() {
        val model = PageIndicator.model(count = 5, pageSize = 4, page = 7)
        assertEquals(listOf("1", "2"), model.labels)
        assertEquals(1, model.selected)
    }

    @Test
    fun `bottom row is where a down press should leave the grid for the page row`() {
        // 5 tiles over 2 columns: rows are [0,1] [2,3] [4].
        assertFalse(GridFit.isInBottomRow(position = 0, count = 5, cols = 2))
        assertFalse(GridFit.isInBottomRow(position = 3, count = 5, cols = 2))
        assertTrue(GridFit.isInBottomRow(position = 4, count = 5, cols = 2))
    }

    @Test
    fun `a full bottom row counts every one of its tiles`() {
        // 6 tiles over 3 columns: rows are [0,1,2] [3,4,5].
        for (p in 3..5) assertTrue("p=$p", GridFit.isInBottomRow(p, count = 6, cols = 3))
        for (p in 0..2) assertFalse("p=$p", GridFit.isInBottomRow(p, count = 6, cols = 3))
    }

    @Test
    fun `a single row is also the bottom row`() {
        assertTrue(GridFit.isInBottomRow(position = 1, count = 3, cols = 4))
    }

    @Test
    fun `a short last page still gets its own number`() {
        val model = PageIndicator.model(count = 5, pageSize = 4, page = 0)
        assertEquals(listOf("1", "2"), model.labels)
        assertEquals(0, model.selected)
    }
}
