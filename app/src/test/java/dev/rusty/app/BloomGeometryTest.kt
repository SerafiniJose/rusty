package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class BloomGeometryTest {

    @Test fun parksTopRightAccountingForCornerShrink() {
        // Parent 1000×1000, no padding. Clock 200×100, top-left at (400,450) → center (500,500).
        val (dx, dy) = BloomGeometry.cornerTranslation(
            parentWidth = 1000, parentPaddingRight = 0, parentPaddingTop = 0,
            clockX = 400f, clockY = 450f, clockWidth = 200, clockHeight = 100,
            cornerScale = 0.2f, marginPx = 24f,
        )
        // rightEdge=1000-0-24=976; targetCx=976-(200*0.2)/2=956; startCx=500 → dx=456
        assertEquals(456f, dx, 0.001f)
        // topEdge=0+24=24; targetCy=24+(100*0.2)/2=34; startCy=500 → dy=-466
        assertEquals(-466f, dy, 0.001f)
    }

    @Test fun honorsParentPadding() {
        val (dx, dy) = BloomGeometry.cornerTranslation(
            parentWidth = 1000, parentPaddingRight = 50, parentPaddingTop = 30,
            clockX = 400f, clockY = 450f, clockWidth = 200, clockHeight = 100,
            cornerScale = 0.2f, marginPx = 24f,
        )
        // rightEdge=1000-50-24=926; targetCx=926-20=906; dx=906-500=406
        assertEquals(406f, dx, 0.001f)
        // topEdge=30+24=54; targetCy=54+10=64; dy=64-500=-436
        assertEquals(-436f, dy, 0.001f)
    }
}
