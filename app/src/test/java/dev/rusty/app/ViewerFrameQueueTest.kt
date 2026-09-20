package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The overflow policy, driven single-threaded. A frame dropped here leaves NO gap in the RTP
 * sequence (the packetizer only numbers what leaves the queue), so a client cannot detect the loss
 * and will never ask for a refresh — which is why an overflow throws the backlog away and waits for
 * a keyframe instead of quietly shedding one frame.
 */
class ViewerFrameQueueTest {
    private var pts = 0L
    private fun au(keyframe: Boolean = false) =
        AccessUnit(listOf(byteArrayOf(if (keyframe) 0x65 else 0x41)), pts.also { pts += 66_000 }, keyframe)

    @Test fun `below the limit nothing is dropped and order is preserved`() {
        val q = ViewerFrameQueue(4)
        val sent = List(4) { au(keyframe = it == 0) }
        sent.forEach { assertTrue(q.offer(it)) }
        assertEquals(4, q.size)
        assertFalse(q.awaitingKeyframe)
        sent.forEach { assertSame(it, q.poll(0)) }
        assertNull(q.poll(0))
    }

    @Test fun `an overflow flushes the queue and waits for a keyframe instead of blocking`() {
        val q = ViewerFrameQueue(3)
        repeat(3) { assertTrue(q.offer(au(keyframe = it == 0))) }
        // Returns immediately: the codec callback thread is never allowed to block here.
        assertFalse(q.offer(au()))
        // The whole backlog goes, so the viewer resumes at live rather than staying a queue-depth
        // behind for the rest of the connection.
        assertEquals(0, q.size)
        assertTrue(q.awaitingKeyframe)
        assertNull(q.poll(0))
    }

    @Test fun `while awaiting a keyframe non-keyframes are dropped and the first keyframe is queued`() {
        val q = ViewerFrameQueue(2)
        assertTrue(q.offer(au(keyframe = true)))
        assertTrue(q.offer(au()))
        assertFalse(q.offer(au()))
        assertTrue(q.awaitingKeyframe)
        // Every P-frame in the gap references a picture the decoder no longer has.
        repeat(5) { assertFalse(q.offer(au())) }
        assertEquals(0, q.size)
        val idr = au(keyframe = true)
        assertTrue(q.offer(idr))
        assertFalse(q.awaitingKeyframe)
        assertSame(idr, q.poll(0))
        val p = au()
        assertTrue(q.offer(p))
        assertSame(p, q.poll(0))
    }

    @Test fun `a keyframe that overflows is queued straight away`() {
        val q = ViewerFrameQueue(2)
        repeat(2) { assertTrue(q.offer(au(keyframe = it == 0))) }
        val idr = au(keyframe = true)
        assertTrue(q.offer(idr))
        assertFalse(q.awaitingKeyframe)
        assertEquals(1, q.size)
        assertSame(idr, q.poll(0))
    }

    @Test fun `clear drops the backlog and arms the keyframe wait`() {
        val q = ViewerFrameQueue(4)
        assertTrue(q.offer(au(keyframe = true)))
        assertTrue(q.offer(au()))
        q.clear()
        assertEquals(0, q.size)
        assertTrue(q.awaitingKeyframe)
        assertFalse(q.offer(au()))
        assertTrue(q.offer(au(keyframe = true)))
    }
}
