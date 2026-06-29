package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreensaverTransitionsTest {

    @Test fun noEdgeWhenStateUnchanged() {
        assertEquals(
            ScreensaverEdgeAction.NONE,
            ScreensaverTransitions.onVisualEdge(
                isShowing = false, reason = null,
                prev = VisualState.IDLE, next = VisualState.IDLE
            )
        )
    }

    @Test fun activeToIdleWhileHiddenShowsAutoIdle() {
        assertEquals(
            ScreensaverEdgeAction.SHOW_AUTO_IDLE,
            ScreensaverTransitions.onVisualEdge(
                isShowing = false, reason = null,
                prev = VisualState.ACTIVE, next = VisualState.IDLE
            )
        )
    }

    @Test fun idleToActiveWhileShowingAutoIdleExitsWithBloom() {
        assertEquals(
            ScreensaverEdgeAction.EXIT_TO_DASHBOARD,
            ScreensaverTransitions.onVisualEdge(
                isShowing = true, reason = ScreensaverShowReason.AUTO_IDLE,
                prev = VisualState.IDLE, next = VisualState.ACTIVE
            )
        )
    }

    @Test fun idleToActiveWhileShowingAmbientStays() {
        // An ambient peek during playback must not yank the user out on a track change.
        assertEquals(
            ScreensaverEdgeAction.NONE,
            ScreensaverTransitions.onVisualEdge(
                isShowing = true, reason = ScreensaverShowReason.AMBIENT,
                prev = VisualState.IDLE, next = VisualState.ACTIVE
            )
        )
    }

    @Test fun activeToIdleWhileAlreadyShowingDoesNothing() {
        assertEquals(
            ScreensaverEdgeAction.NONE,
            ScreensaverTransitions.onVisualEdge(
                isShowing = true, reason = ScreensaverShowReason.AMBIENT,
                prev = VisualState.ACTIVE, next = VisualState.IDLE
            )
        )
    }
}
