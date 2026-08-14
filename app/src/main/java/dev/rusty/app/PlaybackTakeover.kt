package dev.rusty.app

/** What a playback-start edge should do. */
enum class TakeoverAction { SWITCH_PAGE, BRING_TO_FRONT, WAKE_SCREEN }

/** The three Spotify-tab takeover toggles, read from prefs at edge time. */
data class TakeoverToggles(
    val switchPage: Boolean,
    val bringToFront: Boolean,
    val wakeScreen: Boolean,
)

/**
 * Pure decision for what a Spotify playback start takes over: the visible page, the
 * foreground app, the screen. Mirrors [ScreensaverTransitions] — all policy lives here,
 * the coordinator stays free of branching so JVM tests cover every combination.
 */
object PlaybackTakeover {
    /**
     * Re-entry into ACTIVE this soon after leaving it is not a user action: an
     * unavailable/erroring track mid-queue or a rename/bitrate session restart bounces
     * the status through IDLE and back within seconds.
     */
    const val REENTRY_DEBOUNCE_MS = 10_000L

    /**
     * An edge this soon after process creation is replay, not a cast: a START_STICKY
     * service restart mid-playback rebuilds the store at IDLE and immediately publishes
     * Playing again.
     */
    const val STARTUP_GRACE_MS = 15_000L

    fun onVisualEdge(
        prev: VisualState,
        next: VisualState,
        toggles: TakeoverToggles,
        canDrawOverlays: Boolean,
        screenDesiredOn: Boolean,
        msSinceLastActive: Long?,
        msSinceProcessStart: Long,
    ): Set<TakeoverAction> {
        if (prev != VisualState.IDLE || next != VisualState.ACTIVE) return emptySet()
        if (msSinceProcessStart < STARTUP_GRACE_MS) return emptySet()
        if (msSinceLastActive != null && msSinceLastActive < REENTRY_DEBOUNCE_MS) return emptySet()

        val actions = mutableSetOf<TakeoverAction>()
        if (toggles.wakeScreen) actions += TakeoverAction.WAKE_SCREEN
        // Launching under an active remote-control fake-off would land the activity beneath
        // a deliberately black layer — only the wake toggle may override that command.
        val launch = toggles.bringToFront && canDrawOverlays &&
            (screenDesiredOn || toggles.wakeScreen)
        if (launch) actions += TakeoverAction.BRING_TO_FRONT
        // Coming forward always lands on the Spotify page (locked decision), so a launch
        // implies the page switch even when the page toggle is off.
        if (toggles.switchPage || launch) actions += TakeoverAction.SWITCH_PAGE
        return actions
    }
}
