package dev.rusty.app

/**
 * Counts frames and bytes over fixed windows and reports each closed window as a per-second rate.
 *
 * Used on both ends of a camera stream: the share hub meters what leaves the encoder, the live
 * view meters what the player rendered. Neither side trusts a nominal rate — the Echo Show's HAL
 * ignores the frame rate it is asked for, and most RTSP cameras leave the SDP frame rate empty.
 *
 * Not thread-safe: each owner calls it from one thread (the codec callback thread, or the
 * player's rendering thread).
 */
class StreamMeter(private val windowMs: Long = 1_000) {
    init { require(windowMs > 0) { "windowMs must be positive" } }

    data class Sample(val fps: Int, val bps: Int)

    private var windowStartMs = UNSTARTED
    private var frames = 0
    private var bytes = 0L

    /**
     * Accounts one frame of [bytes] bytes seen at [nowMs]. Returns the closed window's rates when
     * this frame is the first one at or past the window's end — that frame is counted in the NEXT
     * window — and null otherwise.
     */
    fun onFrame(bytes: Int, nowMs: Long): Sample? {
        if (windowStartMs == UNSTARTED || nowMs < windowStartMs) {
            open(nowMs)
            count(bytes)
            return null
        }
        val elapsed = nowMs - windowStartMs
        if (elapsed < windowMs) {
            count(bytes)
            return null
        }
        val sample = Sample(
            fps = Math.round(frames * 1_000.0 / elapsed).toInt(),
            bps = Math.round(this.bytes * 8 * 1_000.0 / elapsed).toInt(),
        )
        open(nowMs)
        count(bytes)
        return sample
    }

    fun reset() {
        windowStartMs = UNSTARTED
        frames = 0
        bytes = 0
    }

    private fun open(nowMs: Long) { windowStartMs = nowMs; frames = 0; bytes = 0 }
    private fun count(b: Int) { frames++; if (b > 0) bytes += b }

    private companion object { const val UNSTARTED = Long.MIN_VALUE }
}
