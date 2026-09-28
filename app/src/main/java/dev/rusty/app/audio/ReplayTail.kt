package dev.rusty.app.audio

/**
 * Remembers the newest [capacity] floats handed to the AudioTrack, so a `pause()+flush()` can hand
 * the not-yet-heard tail back to the track on the next `play()` (see [PcmOutput.pause]).
 * Single-threaded by contract (the librespot player thread is the only caller).
 */
class ReplayTail(private val capacity: Int) {
    private val ring = FloatArray(capacity)
    /** Total floats ever pushed; only the newest [capacity] of them are still in [ring]. */
    private var written = 0L

    /** Appends the first [count] floats of [src]; a push larger than the ring keeps its newest part. */
    fun push(src: FloatArray, count: Int) {
        val kept = minOf(count, capacity)
        val from = count - kept
        val pos = ((written + from) % capacity).toInt()
        val first = minOf(kept, capacity - pos)
        System.arraycopy(src, from, ring, pos, first)
        if (kept > first) System.arraycopy(src, from + first, ring, 0, kept - first)
        written += count
    }

    /** The newest [count] floats (clamped to what the ring holds), oldest first. */
    fun last(count: Int): FloatArray {
        val n = minOf(count.toLong(), written, capacity.toLong()).coerceAtLeast(0L).toInt()
        val out = FloatArray(n)
        if (n == 0) return out
        val start = ((written - n) % capacity).toInt()
        val first = minOf(n, capacity - start)
        System.arraycopy(ring, start, out, 0, first)
        if (n > first) System.arraycopy(ring, 0, out, first, n - first)
        return out
    }

    fun clear() {
        written = 0L
    }
}
