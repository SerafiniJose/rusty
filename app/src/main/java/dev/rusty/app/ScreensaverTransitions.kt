package dev.rusty.app

/** Why the screensaver is currently up — governs whether a track change blooms us out. */
enum class ScreensaverShowReason {
    /** Shown because the dashboard is idle: the screensaver IS the idle face. */
    AUTO_IDLE,
    /** Shown over an active dashboard (idle-timeout peek or manual clock tap). */
    AMBIENT,
}

/** What the controller should do on a visual-state edge. */
enum class ScreensaverEdgeAction { NONE, SHOW_AUTO_IDLE, EXIT_TO_DASHBOARD }

/**
 * Pure decision for the screensaver's auto show/hide on a dashboard visual-state edge.
 * Keeps the controller's threading/View code free of branching logic.
 */
object ScreensaverTransitions {
    fun onVisualEdge(
        isShowing: Boolean,
        reason: ScreensaverShowReason?,
        prev: VisualState,
        next: VisualState,
    ): ScreensaverEdgeAction = when {
        prev == next -> ScreensaverEdgeAction.NONE
        // The dashboard went idle while nothing covered it → the screensaver becomes the idle face.
        !isShowing && next == VisualState.IDLE -> ScreensaverEdgeAction.SHOW_AUTO_IDLE
        // A track started under an auto-idle screensaver → play the bloom into now-playing.
        isShowing && reason == ScreensaverShowReason.AUTO_IDLE && next == VisualState.ACTIVE ->
            ScreensaverEdgeAction.EXIT_TO_DASHBOARD
        else -> ScreensaverEdgeAction.NONE
    }
}
