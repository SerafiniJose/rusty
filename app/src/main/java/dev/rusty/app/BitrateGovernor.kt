package dev.rusty.app

import kotlin.math.abs

/**
 * Holds what actually leaves the socket at the bitrate the share was configured for, whatever
 * frame rate the camera turns out to deliver.
 *
 * ## Why this is needed at all
 *
 * `MediaCodec` sizes each frame from the frame rate it was CONFIGURED with, not from the rate it
 * is actually fed. [CameraCapturePipeline] configures `KEY_FRAME_RATE` and the camera's
 * `CONTROL_AE_TARGET_FPS_RANGE` from the frame rate the share was configured for (15 by default),
 * but that request is a hint: on the 15 fps default the Echo Show's MediaTek HAL lists that range
 * among its supported ones, confirms it in the capture request (`dumpsys media.camera` shows
 * `aeTargetFpsRange [15 15]`), and then delivers 30 fps anyway. The encoder keeps spending
 * ~12.5 KB on every one of those frames — exactly 1.5 Mbps / 15 / 8 — so the stream leaves at
 * 2.97 Mbps against a 1.5 Mbps budget. Measured, not inferred: 889 frames in 30.0 s, all 888
 * inter-frame gaps a uniform 34 ms.
 *
 * Double the bitrate is double the rate at which a viewer's [ViewerFrameQueue] fills during a
 * Wi-Fi stall, so a roam that a stream inside its budget rides through instead overflows the
 * queue, and the viewer's picture freezes until the next IDR.
 *
 * ## The correction
 *
 * Watching bytes leave is HAL-independent: it does not care why the frame rate is wrong, only
 * that the bitrate is. Once a window's worth of frames has been seen, the encoder's bitrate
 * parameter is scaled by `target / observed`, which lands in a single step — the relationship is
 * linear, because the encoder's per-frame budget and the frame rate are both constant.
 *
 * ## Why it can never make things worse
 *
 * [configuredBps] is a hard CEILING, so on a camera that already delivers at or under budget the
 * governor asks for exactly what the pipeline asks for today and changes nothing. Only overshoot
 * is trimmed. The floor stops a misread — a burst of huge keyframes, a window that caught a
 * stall — from collapsing the picture; recovery is symmetric, so a camera that later slows down
 * (a dim room drops one of the test devices to 9 fps) climbs back to the ceiling rather than
 * staying needlessly soft.
 */
internal class BitrateGovernor(
    private val configuredBps: Int,
    private val targetBps: Int = configuredBps,
    private val windowMs: Long = WINDOW_MS,
) {
    init {
        require(configuredBps > 0) { "configuredBps must be positive" }
        require(targetBps > 0) { "targetBps must be positive" }
        require(windowMs > 0) { "windowMs must be positive" }
    }

    private val floorBps: Int = (configuredBps * FLOOR_FRACTION).toInt().coerceAtLeast(1)

    private var windowStartMs: Long = UNSTARTED
    private var bytesInWindow: Long = 0

    /** The encoder bitrate currently asked for. Starts at what the pipeline configured. */
    var current: Int = configuredBps
        private set

    /**
     * Accounts one encoded access unit of [bytes] bytes seen at [nowMs].
     *
     * Returns the new encoder bitrate to apply, or null when nothing should change — which is the
     * answer for all but at most one call per [windowMs], so the caller pays a comparison per
     * frame and a `setParameters` only when the wire rate has actually drifted.
     */
    fun onAccessUnit(bytes: Int, nowMs: Long): Int? {
        if (windowStartMs == UNSTARTED) {
            windowStartMs = nowMs
            bytesInWindow = 0
        }
        if (bytes > 0) bytesInWindow += bytes.toLong()

        val elapsed = nowMs - windowStartMs
        // A clock that went backwards (or stood still) makes the division meaningless: restart the
        // window rather than act on it.
        if (elapsed <= 0) {
            windowStartMs = nowMs
            bytesInWindow = 0
            return null
        }
        if (elapsed < windowMs) return null

        val observedBps = bytesInWindow * 8_000.0 / elapsed
        windowStartMs = nowMs
        bytesInWindow = 0
        // No frames at all in a full window: the camera is stalled, not overshooting. Nothing to
        // regulate, and dividing by it would be a division by zero.
        if (observedBps <= 0.0) return null
        if (abs(observedBps - targetBps) <= targetBps * TOLERANCE) return null

        val next = (current * (targetBps / observedBps)).toInt().coerceIn(floorBps, configuredBps)
        if (next == current) return null
        // Ignore a correction too small to be worth a codec round trip; without this a stream
        // hovering at the tolerance edge would re-set the bitrate every window forever.
        if (abs(next - current) < current * MIN_STEP) return null
        current = next
        return next
    }

    /** Forgets the window and returns to the configured bitrate, for a restarted encoder. */
    fun reset() {
        windowStartMs = UNSTARTED
        bytesInWindow = 0
        current = configuredBps
    }

    companion object {
        private const val UNSTARTED = Long.MIN_VALUE

        /** Long enough to average over a keyframe (the I-frame interval is 2 s) plus its P-frames. */
        const val WINDOW_MS = 3_000L

        /** How far off target the wire rate may sit before it is worth correcting. */
        const val TOLERANCE = 0.12

        /** Corrections smaller than this fraction of the current setting are not worth applying. */
        const val MIN_STEP = 0.05

        /** The lowest fraction of the configured bitrate the governor will ask for. */
        const val FLOOR_FRACTION = 0.25
    }
}
