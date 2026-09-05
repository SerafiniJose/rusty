package dev.rusty.app

import kotlin.math.max

/**
 * Pure column fitting for the camera grid: the smallest column count (from [minCols] up to
 * [MAX_COLS]) whose rows of 16:9 tiles fit the content box; [MAX_COLS] when none does, in which
 * case the grid simply scrolls. See GridFitTest for the geometry model (tiles carry a 6 dp margin
 * each side, so [gapPx] is 12 dp).
 */
object GridFit {
    const val MAX_COLS = 4

    fun columns(count: Int, widthPx: Int, heightPx: Int, gapPx: Int, minCols: Int): Int {
        val floor = minCols.coerceIn(1, MAX_COLS)
        if (count <= 0 || widthPx <= 0 || heightPx <= 0) return floor
        for (cols in floor..MAX_COLS) {
            if (contentHeightPx(count, cols, widthPx, gapPx) <= heightPx) return cols
        }
        return MAX_COLS
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
