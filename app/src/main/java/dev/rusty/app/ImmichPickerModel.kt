package dev.rusty.app

/** The three filter categories, with every piece of user-facing copy that varies by kind. */
enum class ImmichFilterKind(
    val title: String,
    val allLabel: String,       // row state when nothing is selected
    val unitSingular: String,   // summary-line units
    val unitPlural: String,
    val unknownLabel: String,   // synthetic row for a selected id the server no longer returns
) {
    ALBUMS("Albums", "Everything", "album", "albums", "Unknown album"),
    PEOPLE("People", "Everyone", "person", "people", "Unknown person"),
    TAGS("Tags", "All tags", "tag", "tags", "Unknown tag"),
}

/**
 * One category's fetch state, tagged with the connection generation it was fetched under
 * (SlideshowSettings.connectionGeneration). Rows render from THIS plus the persisted
 * selection — never from prefs alone — so loading/failed/stale-server states can't be
 * papered over by a prefs-only re-render.
 */
sealed class ImmichCategoryState {
    object Unavailable : ImmichCategoryState()
    data class Loading(val gen: Int) : ImmichCategoryState()
    data class Loaded(val gen: Int, val items: List<ImmichPickerItem>) : ImmichCategoryState()
    data class Failed(val gen: Int) : ImmichCategoryState()
}

/** Pure decision logic for the filter summary rows and picker dialog. No Android imports. */
object ImmichPickerModel {

    fun stateLine(state: ImmichCategoryState, selectedCount: Int, kind: ImmichFilterKind): String =
        when (state) {
            ImmichCategoryState.Unavailable -> ""
            is ImmichCategoryState.Loading -> "Loading…"
            is ImmichCategoryState.Loaded ->
                if (selectedCount == 0) kind.allLabel else "$selectedCount selected"
            is ImmichCategoryState.Failed ->
                if (selectedCount == 0) "Couldn't load — tap to retry"
                else "$selectedCount selected — couldn't refresh"
        }

    /**
     * "3 albums" / "1 album" — the single place the singular/plural choice is made, so the
     * summary line and the picker's search hint cannot drift apart grammatically.
     */
    fun unitCount(count: Int, kind: ImmichFilterKind): String =
        "$count ${if (count == 1) kind.unitSingular else kind.unitPlural}"

    /** "Showing: 2 albums · everyone · all tags" — one segment per category. */
    fun summaryLine(albums: Int, people: Int, tags: Int): String {
        fun seg(count: Int, kind: ImmichFilterKind): String =
            if (count == 0) kind.allLabel.lowercase() else unitCount(count, kind)
        return "Showing: ${seg(albums, ImmichFilterKind.ALBUMS)} · " +
            "${seg(people, ImmichFilterKind.PEOPLE)} · ${seg(tags, ImmichFilterKind.TAGS)}"
    }

    /**
     * The dialog's frozen base order: synthetic rows for selected-but-missing ids first
     * (they must stay unselectable-off-able — "a filter you cannot see is a filter you
     * cannot turn off"), then selected items, then the rest in server order. Computed once
     * at dialog open; toggling while open never re-sorts (no rows jumping under focus).
     * With items = emptyList() this doubles as the Failed-category selected-only list.
     */
    fun pickerOrder(
        items: List<ImmichPickerItem>, selectedIds: Set<String>, kind: ImmichFilterKind,
    ): List<ImmichPickerItem> {
        val known = items.mapTo(HashSet()) { it.id }
        val unknown = selectedIds.filter { it !in known }.sorted().map { ImmichPickerItem(it, kind.unknownLabel) }
        val (selected, rest) = items.partition { it.id in selectedIds }
        return unknown + selected + rest
    }

    /** Case-insensitive substring filter over the frozen base order. Runs off-main (Task 4). */
    fun filterItems(ordered: List<ImmichPickerItem>, query: String): List<ImmichPickerItem> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return ordered
        return ordered.filter { q in it.label.lowercase() }
    }

    /**
     * Coil memory+disk cache key for picker thumbnails, namespaced by connection
     * generation. URL-only keys would wrongly survive an API-key-only change (same URLs);
     * the generation makes any in-flight old-connection response unreachable after a
     * change. Deliberately free of key material.
     */
    fun thumbCacheKey(generation: Int, url: String): String = "immich-picker-$generation-$url"
}
