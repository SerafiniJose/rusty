package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTakeoverTest {

    private val bothOn = TakeoverToggles(switchPage = true, showOnPlayback = true)
    private val allOff = TakeoverToggles(switchPage = false, showOnPlayback = false)

    private fun edge(
        toggles: TakeoverToggles,
        prev: VisualState = VisualState.IDLE,
        next: VisualState = VisualState.ACTIVE,
        canDrawOverlays: Boolean = true,
        msSinceLastActive: Long? = null,
        msSinceProcessStart: Long = 60_000L,
    ) = PlaybackTakeover.onVisualEdge(
        prev, next, toggles, canDrawOverlays, msSinceLastActive, msSinceProcessStart,
    )

    @Test fun allTogglesOffDoesNothing() {
        assertTrue(edge(allOff).isEmpty())
    }

    @Test fun idleToActiveWithBothTogglesFiresAllActions() {
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE, TakeoverAction.BRING_TO_FRONT, TakeoverAction.WAKE_SCREEN),
            edge(bothOn),
        )
    }

    @Test fun nonEdgeTransitionsDoNothing() {
        assertTrue(edge(bothOn, prev = VisualState.ACTIVE, next = VisualState.ACTIVE).isEmpty())
        assertTrue(edge(bothOn, prev = VisualState.ACTIVE, next = VisualState.IDLE).isEmpty())
        assertTrue(edge(bothOn, prev = VisualState.IDLE, next = VisualState.IDLE).isEmpty())
    }

    @Test fun switchPageAloneOnlySwitches() {
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE),
            edge(TakeoverToggles(switchPage = true, showOnPlayback = false)),
        )
    }

    @Test fun showOnPlaybackWakesFrontsAndImpliesSwitchPage() {
        // The merged toggle is the whole "show me the music" gesture: the screen lights, the app
        // comes forward, and coming forward always lands on the Spotify page.
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE, TakeoverAction.BRING_TO_FRONT, TakeoverAction.WAKE_SCREEN),
            edge(TakeoverToggles(switchPage = false, showOnPlayback = true)),
        )
    }

    @Test fun showOnPlaybackWithoutOverlayPermissionDoesNothingAtAll() {
        // Not even the wake: without the grant the toggle cannot do what it promises, and the
        // settings row says so in amber rather than half-performing. Guarded here because the
        // permission can be revoked while the app runs, long after the row was rendered.
        assertTrue(
            edge(
                TakeoverToggles(switchPage = false, showOnPlayback = true),
                canDrawOverlays = false,
            ).isEmpty(),
        )
    }

    @Test fun switchPageSurvivesMissingOverlayPermission() {
        // The page toggle needs no permission, so a revoked overlay grant must not silence it.
        assertEquals(
            setOf(TakeoverAction.SWITCH_PAGE),
            edge(bothOn, canDrawOverlays = false),
        )
    }

    @Test fun reentryWithinDebounceIsSuppressed() {
        assertTrue(edge(bothOn, msSinceLastActive = PlaybackTakeover.REENTRY_DEBOUNCE_MS - 1).isEmpty())
        assertEquals(3, edge(bothOn, msSinceLastActive = PlaybackTakeover.REENTRY_DEBOUNCE_MS).size)
    }

    @Test fun edgeWithinStartupGraceIsSuppressed() {
        assertTrue(edge(bothOn, msSinceProcessStart = PlaybackTakeover.STARTUP_GRACE_MS - 1).isEmpty())
        assertEquals(3, edge(bothOn, msSinceProcessStart = PlaybackTakeover.STARTUP_GRACE_MS).size)
    }
}
