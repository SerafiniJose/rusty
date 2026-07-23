package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ImmichPickerModelTest {

    private fun item(id: String, label: String = id) = ImmichPickerItem(id, label)

    // ---- state line ---------------------------------------------------------

    @Test fun stateLineCoversEveryStateAndSelectionCombination() {
        val loaded = ImmichCategoryState.Loaded(1, emptyList())
        assertEquals("Everything", ImmichPickerModel.stateLine(loaded, 0, ImmichFilterKind.ALBUMS))
        assertEquals("Everyone", ImmichPickerModel.stateLine(loaded, 0, ImmichFilterKind.PEOPLE))
        assertEquals("All tags", ImmichPickerModel.stateLine(loaded, 0, ImmichFilterKind.TAGS))
        assertEquals("2 selected", ImmichPickerModel.stateLine(loaded, 2, ImmichFilterKind.ALBUMS))
        assertEquals("Loading…", ImmichPickerModel.stateLine(ImmichCategoryState.Loading(1), 2, ImmichFilterKind.ALBUMS))
        assertEquals("Couldn't load — tap to retry",
            ImmichPickerModel.stateLine(ImmichCategoryState.Failed(1), 0, ImmichFilterKind.ALBUMS))
        assertEquals("2 selected — couldn't refresh",
            ImmichPickerModel.stateLine(ImmichCategoryState.Failed(1), 2, ImmichFilterKind.ALBUMS))
        assertEquals("", ImmichPickerModel.stateLine(ImmichCategoryState.Unavailable, 0, ImmichFilterKind.ALBUMS))
    }

    // ---- summary line -------------------------------------------------------

    @Test fun summaryLineMixesCountsAndAllLabels() {
        assertEquals("Showing: 2 albums · everyone · all tags", ImmichPickerModel.summaryLine(2, 0, 0))
        assertEquals("Showing: everything · 1 person · 3 tags", ImmichPickerModel.summaryLine(0, 1, 3))
        assertEquals("Showing: 1 album · everyone · all tags", ImmichPickerModel.summaryLine(1, 0, 0))
        assertEquals("Showing: everything · everyone · all tags", ImmichPickerModel.summaryLine(0, 0, 0))
    }

    // The picker's search hint is built from this, so a one-row category must not read
    // "Search 1 albums".
    @Test fun unitCountPicksSingularOnlyForExactlyOne() {
        assertEquals("1 album", ImmichPickerModel.unitCount(1, ImmichFilterKind.ALBUMS))
        assertEquals("2 albums", ImmichPickerModel.unitCount(2, ImmichFilterKind.ALBUMS))
        assertEquals("0 albums", ImmichPickerModel.unitCount(0, ImmichFilterKind.ALBUMS))
        assertEquals("1 person", ImmichPickerModel.unitCount(1, ImmichFilterKind.PEOPLE))
        assertEquals("3 people", ImmichPickerModel.unitCount(3, ImmichFilterKind.PEOPLE))
        assertEquals("1 tag", ImmichPickerModel.unitCount(1, ImmichFilterKind.TAGS))
    }

    // ---- picker ordering ----------------------------------------------------

    @Test fun pickerOrderIsUnknownsThenSelectedThenRestInServerOrder() {
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        val ordered = ImmichPickerModel.pickerOrder(items, setOf("c", "gone", "a"), ImmichFilterKind.ALBUMS)
        assertEquals(listOf("gone", "a", "c", "b", "d"), ordered.map { it.id })
        assertEquals("Unknown album", ordered[0].label)
    }

    @Test fun pickerOrderWithNoSelectionKeepsServerOrder() {
        val items = listOf(item("b"), item("a"))
        assertEquals(listOf("b", "a"),
            ImmichPickerModel.pickerOrder(items, emptySet(), ImmichFilterKind.TAGS).map { it.id })
    }

    @Test fun selectedOnlyModeSynthesizesEveryUnknown() {
        val ordered = ImmichPickerModel.pickerOrder(emptyList(), setOf("x", "y"), ImmichFilterKind.PEOPLE)
        assertEquals(2, ordered.size)
        assertEquals("Unknown person", ordered[0].label)
    }

    // ---- search filtering ---------------------------------------------------

    @Test fun filterIsCaseInsensitiveSubstringAndPreservesOrder() {
        val ordered = listOf(item("1", "Summer 2023"), item("2", "Beach"), item("3", "summit"))
        assertEquals(listOf("1", "3"), ImmichPickerModel.filterItems(ordered, "SUm").map { it.id })
        assertEquals(ordered, ImmichPickerModel.filterItems(ordered, "  "))
        assertEquals(emptyList<ImmichPickerItem>(), ImmichPickerModel.filterItems(ordered, "zzz"))
    }

    // ---- cache keys ---------------------------------------------------------

    @Test fun thumbCacheKeyIsGenerationNamespacedAndSecretFree() {
        val key = ImmichPickerModel.thumbCacheKey(3, "http://immich.local/api/people/p1/thumbnail")
        assertEquals("immich-picker-3-http://immich.local/api/people/p1/thumbnail", key)
        assertFalse(key == ImmichPickerModel.thumbCacheKey(4, "http://immich.local/api/people/p1/thumbnail"))
    }
}
