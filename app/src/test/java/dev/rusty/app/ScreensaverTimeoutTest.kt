package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreensaverTimeoutTest {

    @Test fun fromPrefSecondsMapsKnownValues() {
        assertEquals(ScreensaverTimeout.SEC_30, ScreensaverTimeout.fromPrefSeconds(30))
        assertEquals(ScreensaverTimeout.MIN_5, ScreensaverTimeout.fromPrefSeconds(300))
        assertEquals(ScreensaverTimeout.NEVER, ScreensaverTimeout.fromPrefSeconds(0))
    }

    @Test fun fromPrefSecondsDefaultsToTwoMinutes() {
        assertEquals(ScreensaverTimeout.MIN_2, ScreensaverTimeout.fromPrefSeconds(-1))
        assertEquals(ScreensaverTimeout.MIN_2, ScreensaverTimeout.DEFAULT)
    }

    @Test fun neverDoesNotArm() {
        assertNull(ScreensaverTimeout.NEVER.timeoutMs)
        assertFalse(ScreensaverTimeout.NEVER.shouldArm)
    }

    @Test fun finiteTimeoutArmsWithMillis() {
        assertEquals(120_000L, ScreensaverTimeout.MIN_2.timeoutMs)
        assertTrue(ScreensaverTimeout.MIN_2.shouldArm)
    }
}
