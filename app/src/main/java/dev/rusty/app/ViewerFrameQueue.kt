package dev.rusty.app

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * One viewer's bounded frame queue plus its overflow policy, kept out of the socket layer so the
 * policy can be exercised deterministically on a single thread (see ViewerFrameQueueTest).
 *
 * ## Why an overflow flushes instead of dropping a frame
 *
 * RTP sequence numbers are minted downstream — by `H264RtpPacketizer.packetize`, on the writer
 * thread, and only for the access units that actually LEAVE this queue. A frame dropped here
 * therefore leaves no gap in the transmitted sequence, and no client can detect the loss: media3's
 * reordering queue, VLC/live555 and go2rtc all key on a sequence discontinuity, so none of them
 * reports it and none asks for a refresh. The decoder simply receives a P-frame whose reference
 * picture never arrived and conceals it by repeating the last one — a smeared, blocky picture that
 * persists until the next IDR, which nothing has requested.
 *
 * So an overflow throws the whole queue away and resumes at the next keyframe:
 * - Latency snaps back to ~0. Evicting one frame per overflow instead would pin the viewer
 *   [limit] frames behind live (60 frames is ~4 s at 15 fps) for as long as the connection lasts,
 *   because every later frame evicts one more and never catches up, even after the link recovers.
 * - Every frame that IS sent has the reference picture it needs, because the stream restarts at a
 *   real IDR.
 *
 * The encoder's I-frame interval (2 s) bounds how long the gap lasts. Nothing here may ask the
 * pipeline for a keyframe: [offer] runs on the codec's single, shared callback thread and must
 * never block.
 *
 * ## Threading
 *
 * [offer] is called only by the producer (the codec callback thread, which fans one frame out to
 * every viewer in turn) and [poll] only by that viewer's writer thread, so [awaitingKeyframe] has
 * a single writer. [LinkedBlockingQueue] covers the hand-off itself, and [clear] — teardown, from
 * any thread — is safe against both.
 */
internal class ViewerFrameQueue(private val limit: Int) {
    init { require(limit > 0) { "limit must be positive" } }

    private val queue = LinkedBlockingQueue<AccessUnit>(limit)

    /** True while non-keyframes are being discarded after a flush, until the next keyframe. */
    @Volatile var awaitingKeyframe: Boolean = false
        private set

    val size: Int get() = queue.size

    /**
     * Hands [au] to the writer thread, or drops it. Returns whether it was queued. Never blocks
     * and never throws: this runs on the codec callback thread.
     */
    fun offer(au: AccessUnit): Boolean {
        if (awaitingKeyframe) {
            if (!au.keyframe) return false
            awaitingKeyframe = false
        }
        if (queue.offer(au)) return true
        // The viewer is not keeping up. Discard everything queued rather than block the codec
        // thread, and resume at a keyframe — this one if it happens to be one.
        queue.clear()
        if (au.keyframe && queue.offer(au)) return true
        awaitingKeyframe = true
        return false
    }

    /** Waits up to [timeoutMs] for the next access unit; null if none arrived. */
    fun poll(timeoutMs: Long): AccessUnit? = queue.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /**
     * Discards everything queued and arms the keyframe wait, so whatever is sent next starts at an
     * IDR rather than at a P-frame whose reference was just thrown away.
     */
    fun clear() {
        awaitingKeyframe = true
        queue.clear()
    }
}
