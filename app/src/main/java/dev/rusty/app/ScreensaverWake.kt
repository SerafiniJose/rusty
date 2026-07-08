package dev.rusty.app

/**
 * Pure decision for what a wake gesture (tap or remote key) does the instant it arrives, before
 * the per-theme handler ([ScreensaverTheme.onWakeGesture]) or the ACTIVE-face check run.
 *
 * The screensaver overlay is a full-screen view drawn on top of the shell chrome, so while it is
 * up the launcher / settings / info buttons are covered. A touch user can still tap "through" a
 * subsequent gesture, but a remote/D-pad user's keys are all consumed by the overlay
 * ([HomeActivity.dispatchKeyEvent]) and, at idle, [ScreensaverController] deliberately keeps the
 * ambient face up — leaving a touchless TV (e.g. NVIDIA Shield) with no way to reach any control.
 *
 * Kept Android-free so the rule is exercised by a plain JVM test.
 */
object ScreensaverWake {

    /**
     * Returns true when a wake gesture must exit the saver straight to the dashboard:
     * - the current feature is NOT the receiver (any input returns to that feature), OR
     * - the UI is in non-touch mode (a remote/D-pad user must always be able to escape the
     *   full-screen saver onto a focusable control).
     *
     * When false, the caller keeps the ambient idle face up and falls through to its per-theme /
     * ACTIVE-face handling — the unchanged touch behaviour.
     */
    fun exitsImmediately(receiverForeground: Boolean, isTouchMode: Boolean): Boolean =
        !receiverForeground || !isTouchMode
}
