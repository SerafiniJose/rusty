package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OledBurnInTest {

    @Test fun breatheIsZeroAtCycleEnds() {
        assertEquals(0f, OledBurnIn.breatheAlpha(0f), 1e-4f)
        assertEquals(0f, OledBurnIn.breatheAlpha(1f), 1e-4f) // 1.0 wraps to phase 0
    }

    @Test fun breathePeaksAtMidpoint() {
        assertEquals(1f, OledBurnIn.breatheAlpha(0.5f), 1e-4f)
    }

    @Test fun breatheRisesToPeakThenFalls() {
        assertTrue(OledBurnIn.breatheAlpha(0.25f) < OledBurnIn.breatheAlpha(0.5f))
        assertTrue(OledBurnIn.breatheAlpha(0.75f) < OledBurnIn.breatheAlpha(0.5f))
        // Symmetric: quarter up and quarter down are equally bright.
        assertEquals(OledBurnIn.breatheAlpha(0.25f), OledBurnIn.breatheAlpha(0.75f), 1e-4f)
    }

    @Test fun breatheStaysInUnitRange() {
        var p = 0f
        while (p <= 1f) {
            assertTrue(OledBurnIn.breatheAlpha(p) in 0f..1f)
            p += 0.05f
        }
    }

    @Test fun maxTravelGivesSymmetricHalfExtent() {
        // 1000/2 - 50 - 200/2 = 350
        assertEquals(350f, OledBurnIn.maxTravel(1000, 200, 50f), 1e-4f)
    }

    @Test fun maxTravelClampsToZeroWhenGroupExceedsSafeArea() {
        assertEquals(0f, OledBurnIn.maxTravel(300, 400, 20f), 1e-4f)
    }
}
