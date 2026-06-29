package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootStartSupportTest {
    @Test fun reliableBelowApi35() { assertTrue(BootStartSupport.isReliable(34)) }
    @Test fun unreliableAtApi35Plus() {
        assertFalse(BootStartSupport.isReliable(35))
        assertFalse(BootStartSupport.isReliable(36))
    }
}
