package dev.rusty.app

/**
 * Pure half of the Cameras section's reorder mode: list moves and the "where does this land on the
 * wall" subline. No Android types, so it is unit-tested like [CameraSettingsModel].
 */
object CameraOrder {
    /** [cameras] sorted by position with [id] placed at [targetIndex] (clamped) and positions
     *  renumbered 0..n-1 in that order. Unknown id → the sorted list, renumbered. */
    fun moveTo(cameras: List<CameraRecord>, id: String, targetIndex: Int): List<CameraRecord> {
        val sorted = cameras.sortedBy { it.position }.toMutableList()
        val from = sorted.indexOfFirst { it.id == id }
        if (from >= 0) {
            val moving = sorted.removeAt(from)
            sorted.add(targetIndex.coerceIn(0, sorted.size), moving)
        }
        return sorted.mapIndexed { i, cam -> if (cam.position == i) cam else cam.copy(position = i) }
    }

    /**
     * Plain-words grid spot for the camera at [index] of [total]: "Top left", "Bottom right",
     * "Middle centre", "Row 2 · column 3", or "Page 2 · spot 3" once the layout is paged and there
     * is more than one page. [columns] is the column count the wall would use for the tiles it
     * shows at once ([pageSize] tiles, or all of them).
     */
    fun positionLabel(index: Int, total: Int, pageSize: Int?, columns: Int): String {
        if (total <= 1) return "Full screen"
        val shown = pageSize?.coerceAtLeast(1) ?: total
        val pages = Pager.pageCount(total, shown)
        if (pageSize != null && pages > 1) {
            return "Page ${index / shown + 1} · spot ${index % shown + 1}"
        }
        val cols = columns.coerceAtLeast(1)
        val rows = (shown + cols - 1) / cols
        val row = index / cols
        val col = index % cols
        val vertical = when (rows) {
            1 -> ""
            2 -> if (row == 0) "Top" else "Bottom"
            3 -> listOf("Top", "Middle", "Bottom")[row]
            else -> "Row ${row + 1}"
        }
        val horizontal = when (cols) {
            1 -> ""
            2 -> if (col == 0) "left" else "right"
            3 -> listOf("left", "centre", "right")[col]
            else -> "column ${col + 1}"
        }
        // Corner words read as one phrase ("Top left"); numbered parts get a separator.
        val sep = if (rows > 3 || cols > 3) " · " else " "
        val words = listOf(vertical, horizontal).filter { it.isNotEmpty() }.joinToString(sep)
        return words.replaceFirstChar { it.uppercase() }
    }
}
