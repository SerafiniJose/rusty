package dev.rusty.app

/**
 * Tracks *why* the now-playing screen is showing the ambient clock instead of the track face.
 * Two independent reasons can raise the clock, and a tap must dismiss whichever is active:
 *  - a manual peek (tap the clock during playback to glance at the time), and
 *  - the automatic drift after playback has sat paused for a while.
 *
 * Pure state (no Android dependencies) so the tap/timeout transitions are unit-testable. The
 * Android handler that schedules the pause drift lives in the activity; this only owns the
 * boolean reasons and the tap rule that combines them.
 */
class ClockFaceState {
    /** Tap-to-peek the clock during active playback. */
    private var manualPeek = false

    /** Drifted to the clock after a sustained pause. */
    private var pausedDrift = false

    /** True when either reason is holding the clock face up. */
    val showingClock: Boolean get() = manualPeek || pausedDrift

    /** The pause-drift timer fired. */
    fun onPauseTimeout() { pausedDrift = true }

    /** Playback left the paused state: drop the drift but keep any manual peek. */
    fun clearPausedDrift() { pausedDrift = false }

    /** Playback went idle (stopped/error/etc): drop every reason. */
    fun reset() {
        manualPeek = false
        pausedDrift = false
    }

    /**
     * The user tapped the clock (or now-playing area).
     *
     * When the clock was raised automatically by the pause drift, a tap wakes the now-playing
     * face and clears *both* reasons — otherwise the drift flag would hold the clock up forever
     * while the tap merely toggled the peek underneath it.
     *
     * @return true if this tap woke the screen from the automatic pause drift, signalling the
     *   caller to restart the drift countdown.
     */
    fun onTap(): Boolean {
        if (pausedDrift) {
            pausedDrift = false
            manualPeek = false
            return true
        }
        manualPeek = !manualPeek
        return false
    }
}
