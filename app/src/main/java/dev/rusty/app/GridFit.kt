package dev.rusty.app

import kotlin.math.max
import kotlin.math.min

/**
 * Pure column fitting for the camera grid: the smallest column count (from [minCols] up to
 * [MAX_COLS]) whose rows of 16:9 tiles fit the content box; [MAX_COLS] when none does, in which
 * case the grid simply scrolls. See GridFitTest for the geometry model (tiles carry a 6 dp margin
 * each side, so [gapPx] is 12 dp).
 *
 * Tiles are normally as wide as the columns allow. When the rows would overrun the box by only a
 * little — a 2×2 page of 16:9 tiles on a 16:10 screen once the page row has taken its strip — the
 * tiles shrink to the height instead of the grid jumping to a 3 + 1 layout: a column count is
 * accepted while the height-limited tile keeps at least [SHRINK_TOLERANCE] of the full width, and
 * [fittedWidthPx] says how wide the grid's content then is (the fragment centres it).
 */
object GridFit {
    const val MAX_COLS = 4

    /** Smallest fraction of the full column width a height-limited tile may shrink to before the
     *  next column count is tried instead. */
    const val SHRINK_TOLERANCE = 0.85

    fun columns(count: Int, widthPx: Int, heightPx: Int, gapPx: Int, minCols: Int): Int {
        val floor = minCols.coerceIn(1, MAX_COLS)
        if (count <= 0 || widthPx <= 0 || heightPx <= 0) return floor
        for (cols in floor..MAX_COLS) {
            val full = fullTileWidthPx(cols, widthPx, gapPx)
            if (tileWidthPx(count, cols, widthPx, heightPx, gapPx) >= full * SHRINK_TOLERANCE) return cols
        }
        return MAX_COLS
    }

    /** Tile width when the columns alone decide it. */
    fun fullTileWidthPx(cols: Int, widthPx: Int, gapPx: Int): Int =
        if (cols <= 0) 0 else max(0, (widthPx - cols * gapPx) / cols)

    /** Tile width at [cols]: the full column width, or less when the rows would not fit [heightPx]. */
    fun tileWidthPx(count: Int, cols: Int, widthPx: Int, heightPx: Int, gapPx: Int): Int {
        if (count <= 0 || cols <= 0) return 0
        val rows = (count + cols - 1) / cols
        val tileHByHeight = heightPx / rows - gapPx
        val byHeight = max(0, tileHByHeight * 16 / 9)
        return min(fullTileWidthPx(cols, widthPx, gapPx), byHeight)
    }

    /** Width the content occupies at [cols] — [widthPx] unless the tiles had to shrink to the height. */
    fun fittedWidthPx(count: Int, cols: Int, widthPx: Int, heightPx: Int, gapPx: Int): Int {
        if (count <= 0 || cols <= 0) return widthPx
        val tileW = tileWidthPx(count, cols, widthPx, heightPx, gapPx)
        return min(widthPx, cols * (tileW + gapPx))
    }

    fun contentHeightPx(count: Int, cols: Int, widthPx: Int, gapPx: Int): Int {
        if (count <= 0 || cols <= 0) return 0
        val tileW = (widthPx - cols * gapPx) / cols
        val tileH = tileW * 9 / 16
        val rows = (count + cols - 1) / cols
        return rows * (tileH + gapPx)
    }

    /**
     * Whether [position] sits in the grid's bottom row — the row a ▼ press has to leave the grid
     * from, since RecyclerView will not hand focus to the sibling below on its own.
     */
    fun isInBottomRow(position: Int, count: Int, cols: Int): Boolean {
        if (cols <= 0 || count <= 0 || position < 0) return false
        return position / cols == (count - 1) / cols
    }

    fun topPaddingPx(count: Int, cols: Int, widthPx: Int, heightPx: Int, gapPx: Int): Int {
        val content = contentHeightPx(count, cols, widthPx, gapPx)
        if (content <= 0 || content > heightPx) return 0
        return max(0, (heightPx - content) / 2)
    }
}

/**
 * What the grid's numbered page row shows. Pure so the fragment only has to build views from
 * [Model] — visibility, labelling and out-of-range pages are decided here, not in view code.
 */
object PageIndicator {

    /** [selected] indexes into [labels]; it is meaningless when [visible] is false. */
    data class Model(val visible: Boolean, val labels: List<String>, val selected: Int)

    private val HIDDEN = Model(visible = false, labels = emptyList(), selected = 0)

    /** [pageSize] is null for the "All" layout, which never pages. */
    fun model(count: Int, pageSize: Int?, page: Int): Model {
        if (pageSize == null || pageSize <= 0 || count <= 0) return HIDDEN
        val pages = Pager.pageCount(count, pageSize)
        if (pages <= 1) return HIDDEN
        return Model(
            visible = true,
            labels = (1..pages).map(Int::toString),
            selected = Pager.clamp(page, count, pageSize),
        )
    }
}

/** Fixed-size pages over the position-ordered camera list. */
object Pager {
    fun pageCount(count: Int, pageSize: Int): Int =
        if (count <= 0 || pageSize <= 0) 1 else (count + pageSize - 1) / pageSize

    fun clamp(page: Int, count: Int, pageSize: Int): Int = page.coerceIn(0, pageCount(count, pageSize) - 1)

    fun <T> slice(items: List<T>, pageSize: Int, page: Int): List<T> {
        if (pageSize <= 0) return items
        val p = clamp(page, items.size, pageSize)
        val from = p * pageSize
        return items.subList(from.coerceAtMost(items.size), (from + pageSize).coerceAtMost(items.size))
    }
}
