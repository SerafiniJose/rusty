package dev.rusty.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * The Spotify PCM output: one `AudioTrack` fed by the Rust core over JNI (rust/src/audio_sink.rs).
 *
 * Every method is called from librespot's player thread only, so there is no locking here.
 * The Rust side treats a negative [write] result or a thrown exception as "output gone".
 *
 * Two AudioTrack quirks are hidden from the Rust caller:
 * - **Deferred start.** `AudioTrack.play()` on an empty stream counts underruns until the first
 *   write lands, so [play] only records the intent; the track really starts on the next [write].
 * - **Lossless pause.** librespot's resume position is the *decoder* position, which runs up to a
 *   whole buffer (~1 s) ahead of what has been heard. A bare `flush()` on pause would therefore
 *   skip that second on resume, while draining would delay the pause by it. [pause] does
 *   `pause()+flush()` for an instant silence and keeps the unplayed tail (out of [ReplayTail]) to
 *   re-queue it on the next [play], so playback resumes exactly where the listener left it.
 */
class PcmOutput {

    companion object {
        /** librespot decodes to 44.1 kHz stereo; Android resamples to the device rate itself. */
        const val SAMPLE_RATE = 44_100
        const val CHANNELS = 2
        /**
         * Audio queued ahead of the DAC. This — not the sink swap itself — is what protects the
         * Echo Show from micro-cuts when Canvas video or the network steals the CPU for a moment.
         * The same second is the seek/track-change latency, so it is not larger.
         */
        const val TARGET_BUFFER_MS = 1_000
        private const val BYTES_PER_SAMPLE = 4 // ENCODING_PCM_FLOAT
        private const val TAG = "PcmOutput"
    }

    private val track: AudioTrack

    /** Capacity of the track buffer in frames — what the HAL granted, which can exceed the request. */
    val bufferFrames: Int

    private val tail: ReplayTail

    /** Frames handed to the track since the last flush. */
    private var framesWritten = 0L

    /** Playback head as read right after the last flush (normally 0 — see [PcmBuffers.unplayedFrames]). */
    private var headBase = 0

    private var playRequested = false
    private var playing = false

    /** Tail retained by [pause], written back by the next [play]. */
    private var pendingReplay: FloatArray? = null

    init {
        val minBytes = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        check(minBytes > 0) { "AudioTrack.getMinBufferSize failed: $minBytes" }
        val bytes = PcmBuffers.bufferBytes(minBytes, SAMPLE_RATE, CHANNELS, BYTES_PER_SAMPLE, TARGET_BUFFER_MS)
        val built = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build(),
            )
            .setBufferSizeInBytes(bytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            // A normal shared (non-MMAP, non-low-latency) track: AudioPolicy re-routes it on
            // Bluetooth connect/disconnect transparently, which is what makes route following free.
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
        if (built.state != AudioTrack.STATE_INITIALIZED) {
            val state = built.state
            built.release()
            throw IllegalStateException("AudioTrack not initialised (state $state, requested $bytes B)")
        }
        track = built
        bufferFrames = built.bufferSizeInFrames
        tail = ReplayTail(bufferFrames * CHANNELS)
        Log.i(
            TAG,
            "opened: min=$minBytes B, buffer=$bufferFrames frames (~${bufferFrames * 1_000L / SAMPLE_RATE} ms), " +
                "session=${built.audioSessionId}",
        )
    }

    /**
     * Blocking write of the first [count] floats of [samples] (interleaved stereo). Returns the
     * number written (== [count]) or a negative `AudioTrack` error code; never throws on a live
     * track. Starts the track on the first data after [play].
     */
    fun write(samples: FloatArray, count: Int): Int {
        val n = track.write(samples, 0, count, AudioTrack.WRITE_BLOCKING)
        if (n > 0) {
            framesWritten += n / CHANNELS
            tail.push(samples, n)
            if (playRequested && !playing) startTrack()
        }
        return n
    }

    fun play() {
        playRequested = true
        pendingReplay?.let { replay ->
            pendingReplay = null
            if (replay.isNotEmpty()) {
                // The buffer is empty after the flush and the tail is at most one buffer, so
                // this cannot block for long.
                val n = track.write(replay, 0, replay.size, AudioTrack.WRITE_BLOCKING)
                if (n > 0) {
                    framesWritten += n / CHANNELS
                    tail.push(replay, n)
                }
            }
        }
        if (framesWritten > 0) startTrack()
    }

    fun pause() {
        playRequested = false
        if (!playing) return
        try {
            track.pause()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "pause on a dead track: $e")
            return
        }
        playing = false
        val unplayed = PcmBuffers.unplayedFrames(framesWritten, track.playbackHeadPosition, headBase, bufferFrames)
        pendingReplay = tail.last(unplayed * CHANNELS)
        track.flush()
        framesWritten = 0L
        tail.clear()
        headBase = track.playbackHeadPosition
        Log.d(TAG, "paused: retained $unplayed unplayed frames")
    }

    /** `AudioTrack.getUnderrunCount()`, or -1 once the track is released. */
    fun underrunCount(): Int = try {
        track.underrunCount
    } catch (e: IllegalStateException) {
        -1
    }

    fun release() {
        playRequested = false
        pendingReplay = null
        try {
            track.pause()
            track.flush()
        } catch (e: IllegalStateException) {
            // already dead; release() below is still the right call
        }
        track.release()
        playing = false
        Log.i(TAG, "released")
    }

    private fun startTrack() {
        try {
            track.play()
            playing = true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "play on a dead track: $e")
        }
    }
}
