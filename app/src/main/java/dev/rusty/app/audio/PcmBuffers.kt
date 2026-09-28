package dev.rusty.app.audio

/**
 * The arithmetic behind [PcmOutput], kept free of `android.*` so it runs in JVM unit tests.
 */
object PcmBuffers {

    /**
     * Size in bytes of an AudioTrack buffer holding at least [targetMs] of audio, never below
     * the HAL minimum [minBytes], rounded UP to whole frames (AudioTrack rejects partial frames).
     */
    fun bufferBytes(minBytes: Int, sampleRate: Int, channels: Int, bytesPerSample: Int, targetMs: Int): Int {
        val frameBytes = (channels * bytesPerSample).toLong()
        val targetBytes = sampleRate.toLong() * targetMs / 1_000L * frameBytes
        val chosen = maxOf(minBytes.toLong(), targetBytes)
        val frames = (chosen + frameBytes - 1) / frameBytes
        return (frames * frameBytes).toInt()
    }

    /**
     * Frames written since the last flush that the playback head has not reached yet.
     *
     * [head] is `AudioTrack.getPlaybackHeadPosition()`: an unsigned 32-bit counter returned as a
     * signed int, documented to reset on `flush()`. [headBase] is the head read right after the
     * last flush, so the maths is right whether or not that reset actually happened, and the
     * unsigned subtraction survives the 32-bit wrap. Clamped to `0..bufferFrames`: the head can
     * report a few frames past what was written (latency jitter), and nothing beyond the buffer
     * capacity can be queued.
     */
    fun unplayedFrames(framesWritten: Long, head: Int, headBase: Int, bufferFrames: Int): Int {
        val played = (head.toLong() - headBase.toLong()) and 0xFFFF_FFFFL
        return (framesWritten - played).coerceIn(0L, bufferFrames.toLong()).toInt()
    }
}
