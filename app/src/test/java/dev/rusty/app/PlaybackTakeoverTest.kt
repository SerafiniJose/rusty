package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTakeoverTest {

    private val allOn = TakeoverToggles(switchPage = true, bringToFront = true, wakeScreen = true)
    private val allOff = TakeoverToggles(switchPage = false, bringToFront = false, wakeScreen = false)

    private fun edge(
        toggles: TakeoverToggles,
        prev: VisualState = VisualState.IDLE,
        next: VisualState = VisualState.ACTIVE,
        canDrawOverlays: Boolean = true,
        screenDesiredOn: Boolean = true,
        msSinceLastActive: Long? = null,
        msSinceProcessStart: Long = 60_000L,
    ) = PlaybackTakeover.onVisualEdge(
        prev, next, toggles, canDrawOverlays, screenDesiredOn, msSinceLastActive, msSinceProcessStart,
    )

    @Test fun allTogglesOffDoesNothing() {
        assertTrue(edge(allOff).isEmpty())
    }

    @Test fun idleToActiveWithAllTogglesFiresAllActions() {
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE, TakeoverAction.BRING_TO_FRONT, TakeoverAction.WAKE_SCREEN),
            edge(allOn),
        )
    }

    @Test fun nonEdgeTransitionsDoNothing() {
        assertTrue(edge(allOn, prev = VisualState.ACTIVE, next = VisualState.ACTIVE).isEmpty())
        assertTrue(edge(allOn, prev = VisualState.ACTIVE, next = VisualState.IDLE).isEmpty())
        assertTrue(edge(allOn, prev = VisualState.IDLE, next = VisualState.IDLE).isEmpty())
    }

    @Test fun switchPageAloneOnlySwitches() {
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE),
            edge(TakeoverToggles(switchPage = true, bringToFront = false, wakeScreen = false)),
        )
    }

    @Test fun bringToFrontImpliesSwitchPage() {
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE, TakeoverAction.BRING_TO_FRONT),
            edge(TakeoverToggles(switchPage = false, bringToFront = true, wakeScreen = false)),
        )
    }

    @Test fun bringToFrontWithoutOverlayPermissionDoesNothing() {
        assertTrue(
            edge(
                TakeoverToggles(switchPage = false, bringToFront = true, wakeScreen = false),
                canDrawOverlays = false,
            ).isEmpty(),
        )
    }

    @Test fun switchPageSurvivesMissingOverlayPermission() {
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE),
            edge(
                TakeoverToggles(switchPage = true, bringToFront = true, wakeScreen = false),
                canDrawOverlays = false,
            ),
        )
    }

    @Test fun fakeOffSuppressesLaunchUnlessWakeAlsoOn() {
        // Fake-off active (screenDesiredOn = false), wake toggle off → no launch.
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE),
            edge(
                TakeoverToggles(switchPage = true, bringToFront = true, wakeScreen = false),
                screenDesiredOn = false,
            ),
        )
        // Wake toggle on → launch allowed again.
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE, TakeoverAction.BRING_TO_FRONT, TakeoverAction.WAKE_SCREEN),
            edge(allOn, screenDesiredOn = false),
        )
    }

    @Test fun wakeAloneOnlyWakes() {
        assertEquals(
            setOf(TakeoverAction.WAKE_SCREEN),
            edge(TakeoverToggles(switchPage = false, bringToFront = false, wakeScreen = true)),
        )
    }

    @Test fun reentryWithinDebounceIsSuppressed() {
        assertTrue(edge(allOn, msSinceLastActive = PlaybackTakeover.REENTRY_DEBOUNCE_MS - 1).isEmpty())
        assertEquals(3, edge(allOn, msSinceLastActive = PlaybackTakeover.REENTRY_DEBOUNCE_MS).size)
    }

    @Test fun edgeWithinStartupGraceIsSuppressed() {
        assertTrue(edge(allOn, msSinceProcessStart = PlaybackTakeover.STARTUP_GRACE_MS - 1).isEmpty())
        assertEquals(3, edge(allOn, msSinceProcessStart = PlaybackTakeover.STARTUP_GRACE_MS).size)
    }
}
