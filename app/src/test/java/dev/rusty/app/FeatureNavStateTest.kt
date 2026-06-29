package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM unit tests for [FeatureNavState].
 *
 * Covers: ring order from enabled ids; restore-from-persisted; disabling the current feature
 * falls back to the next enabled / first enabled.
 */
class FeatureNavStateTest {

    // ---- ring order --------------------------------------------------------

    @Test
    fun `initial current is first enabled when no persisted id`() {
        val state = FeatureNavState(persisted = null, enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT))
        assertEquals(FeatureId.SPOTIFY, state.current)
    }

    @Test
    fun `next advances through ring in declaration order`() {
        val state = FeatureNavState(persisted = null, enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT))
        assertEquals(FeatureId.HOME_ASSISTANT, state.next())
    }

    @Test
    fun `next wraps around to first`() {
        val state = FeatureNavState(persisted = null, enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT))
        state.current = FeatureId.HOME_ASSISTANT
        assertEquals(FeatureId.SPOTIFY, state.next())
    }

    @Test
    fun `next is identity for singleton ring`() {
        val state = FeatureNavState(persisted = null, enabled = listOf(FeatureId.SPOTIFY))
        assertEquals(FeatureId.SPOTIFY, state.next())
    }

    // ---- restore/persist ---------------------------------------------------

    @Test
    fun `persisted id is restored when still enabled`() {
        val state = FeatureNavState(
            persisted = FeatureId.HOME_ASSISTANT,
            enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT),
        )
        assertEquals(FeatureId.HOME_ASSISTANT, state.current)
    }

    @Test
    fun `persisted id is ignored when no longer enabled`() {
        // HOME_ASSISTANT was persisted but only SPOTIFY is enabled → fall back to first enabled.
        val state = FeatureNavState(
            persisted = FeatureId.HOME_ASSISTANT,
            enabled = listOf(FeatureId.SPOTIFY),
        )
        assertEquals(FeatureId.SPOTIFY, state.current)
    }

    @Test
    fun `persisted null with empty enabled list defaults without crash`() {
        // Degenerate: no enabled features — current stays SPOTIFY (safe default).
        val state = FeatureNavState(persisted = null, enabled = emptyList())
        assertEquals(FeatureId.SPOTIFY, state.current)
    }

    // ---- onEnabledChanged: disabling current falls back --------------------

    @Test
    fun `disabling current feature falls back to next enabled`() {
        val state = FeatureNavState(
            persisted = null,
            enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT),
        )
        state.current = FeatureId.SPOTIFY
        // Disable SPOTIFY — should fall back to HOME_ASSISTANT (next in ring).
        state.onEnabledChanged(listOf(FeatureId.HOME_ASSISTANT))
        assertEquals(FeatureId.HOME_ASSISTANT, state.current)
    }

    @Test
    fun `disabling current feature when only one remains falls back to that one`() {
        val state = FeatureNavState(
            persisted = FeatureId.HOME_ASSISTANT,
            enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT),
        )
        // Disable HOME_ASSISTANT while it is current → only SPOTIFY remains.
        state.onEnabledChanged(listOf(FeatureId.SPOTIFY))
        assertEquals(FeatureId.SPOTIFY, state.current)
    }

    @Test
    fun `onEnabledChanged preserves current when still enabled`() {
        val state = FeatureNavState(
            persisted = FeatureId.HOME_ASSISTANT,
            enabled = listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT),
        )
        state.onEnabledChanged(listOf(FeatureId.SPOTIFY, FeatureId.HOME_ASSISTANT))
        assertEquals(FeatureId.HOME_ASSISTANT, state.current)
    }

    @Test
    fun `onEnabledChanged with empty list leaves current unchanged`() {
        val state = FeatureNavState(persisted = null, enabled = listOf(FeatureId.SPOTIFY))
        // Degenerate: no enabled features after the change — current must not crash.
        state.onEnabledChanged(emptyList())
        assertEquals(FeatureId.SPOTIFY, state.current)
    }
}
