package dev.rusty.app

/** What a playback-start edge should do. */
enum class TakeoverAction { SWITCH_PAGE, BRING_TO_FRONT, WAKE_SCREEN }

/**
 * The two Spotify-tab takeover toggles, read from prefs at edge time.
 *
 * [showOnPlayback] is one gesture — light the screen AND bring Rusty forward — because the halves
 * were never independently useful: a launch with the display off may never resume, so front without
 * wake is a no-op dressed as a feature, and the policy already refused to launch under a remote
 * fake-off unless wake was also on. One toggle removes the incoherent combinations rather than
 * asking the user to avoid them.
 */
data class TakeoverToggles(
    val switchPage: Boolean,
    val showOnPlayback: Boolean,
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
        msSinceLastActive: Long?,
        msSinceProcessStart: Long,
    ): Set<TakeoverAction> {
        if (prev != VisualState.IDLE || next != VisualState.ACTIVE) return emptySet()
        if (msSinceProcessStart < STARTUP_GRACE_MS) return emptySet()
        if (msSinceLastActive != null && msSinceLastActive < REENTRY_DEBOUNCE_MS) return emptySet()

        val actions = mutableSetOf<TakeoverAction>()
        // All or nothing, deliberately: without the overlay grant the toggle cannot deliver what
        // it promises, so it does none of it — not even the wake, which would need no permission.
        // The settings row carries that in amber instead of half-performing. Checked here and not
        // only in the UI because the grant can be revoked long after the row was drawn.
        val show = toggles.showOnPlayback && canDrawOverlays
        if (show) {
            // Wake is emitted with the launch, never apart from it: the coordinator runs the wake
            // first, which is also what lets the launch survive an active remote-control fake-off
            // (the wake clears it) — no separate opt-in for that case any more.
            actions += TakeoverAction.WAKE_SCREEN
            actions += TakeoverAction.BRING_TO_FRONT
        }
        // Coming forward always lands on the Spotify page (locked decision), so a launch
        // implies the page switch even when the page toggle is off.
        if (toggles.switchPage || show) actions += TakeoverAction.SWITCH_PAGE
        return actions
    }
}
