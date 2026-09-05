package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrderTest {
    private fun cam(id: String, position: Int) = CameraRecord(
        id = id, name = id, rtspUrl = "rtsp://h/$id", mainRtspUrl = null, snapshotUrl = null,
        audioEnabled = false, forceTcp = true, position = position,
    )
    // Deliberately unsorted with gaps, as a stored list can be after deletes.
    private val list = listOf(cam("c", 7), cam("a", 0), cam("b", 3), cam("d", 9))

    @Test fun moveToPlacesTheCameraAndRenumbers() {
        val moved = CameraOrder.moveTo(list, "d", 1)
        assertEquals(listOf("a", "d", "b", "c"), moved.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), moved.map { it.position })
    }

    @Test fun moveToClampsTheTarget() {
        assertEquals(listOf("b", "c", "d", "a"), CameraOrder.moveTo(list, "a", 99).map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), CameraOrder.moveTo(list, "d", -5).map { it.id })
    }

    @Test fun moveToUnknownIdOnlyRenumbers() {
        val moved = CameraOrder.moveTo(list, "zzz", 1)
        assertEquals(listOf("a", "b", "c", "d"), moved.map { it.id })
        assertEquals(listOf(0, 1, 2, 3), moved.map { it.position })
    }

    @Test fun twoByTwoUsesCornerWords() {
        assertEquals("Top left", CameraOrder.positionLabel(0, 4, null, 2))
        assertEquals("Top right", CameraOrder.positionLabel(1, 4, null, 2))
        assertEquals("Bottom left", CameraOrder.positionLabel(2, 4, null, 2))
        assertEquals("Bottom right", CameraOrder.positionLabel(3, 4, null, 2))
    }

    @Test fun singleRowAndThreeColumns() {
        assertEquals("Left", CameraOrder.positionLabel(0, 2, null, 2))
        assertEquals("Right", CameraOrder.positionLabel(1, 2, null, 2))
        assertEquals("Top centre", CameraOrder.positionLabel(1, 6, null, 3))
        assertEquals("Bottom right", CameraOrder.positionLabel(5, 6, null, 3))
        assertEquals("Row 4 · column 4", CameraOrder.positionLabel(15, 16, null, 4))
        assertEquals("Full screen", CameraOrder.positionLabel(0, 1, null, 1))
    }

    @Test fun pagedLayoutNamesPageAndSpot() {
        assertEquals("Page 2 · spot 3", CameraOrder.positionLabel(6, 7, 4, 2))
        // One page only: the paged layout reads like a plain grid.
        assertEquals("Bottom left", CameraOrder.positionLabel(2, 4, 4, 2))
    }
}
