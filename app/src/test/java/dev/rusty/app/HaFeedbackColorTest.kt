package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JVM test for [haFeedbackColorRes]: the HA feedback status text must map to the app's brand
 * color tokens, not the old hardcoded off-brand hex. Compares returned @ColorRes ids; no Android
 * runtime needed (R.color.* are compile-time int constants on the unit-test classpath).
 */
class HaFeedbackColorTest {

    @Test
    fun success_uses_brand_green() {
        assertEquals(R.color.dot_green, haFeedbackColorRes(HaFeedbackKind.SUCCESS))
    }

    @Test
    fun neutral_uses_muted_dim() {
        assertEquals(R.color.muted_dim, haFeedbackColorRes(HaFeedbackKind.NEUTRAL))
    }

    @Test
    fun error_uses_dot_red() {
        assertEquals(R.color.dot_red, haFeedbackColorRes(HaFeedbackKind.ERROR))
    }

    @Test
    fun kinds_map_to_distinct_colors() {
        val distinct = setOf(
            haFeedbackColorRes(HaFeedbackKind.SUCCESS),
            haFeedbackColorRes(HaFeedbackKind.NEUTRAL),
            haFeedbackColorRes(HaFeedbackKind.ERROR),
        )
        assertEquals(3, distinct.size)
    }
}
