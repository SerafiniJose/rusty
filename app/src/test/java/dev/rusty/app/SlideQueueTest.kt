package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImmichSlideQueueTest {

    private fun asset(id: String, portrait: Boolean = false) =
        ImmichAsset(id, portrait, null, null, emptyList())

    @Test fun offerDedupesAgainstQueueAndHistory() {
        val q = SlideQueue()
        q.offer(listOf(asset("a"), asset("b")))
        q.offer(listOf(asset("a"), asset("c")))         // "a" already queued
        assertEquals(3, q.depth)
        val shown = q.nextSlide(splitView = false)!!
        q.noteShown(shown)                               // "a" moves to history
        q.offer(listOf(asset("a"), asset("d")))          // "a" in history -> dropped
        assertEquals(3, q.depth)                         // b, c, d
    }

    @Test fun landscapeShowsSoloEvenWithSplitView() {
        val q = SlideQueue()
        q.offer(listOf(asset("l1"), asset("p1", portrait = true)))
        val slide = q.nextSlide(splitView = true)!!
        assertEquals("l1", slide.primary.id)
        assertNull(slide.secondary)
    }

    @Test fun portraitPairsWithNextQueuedPortrait() {
        val q = SlideQueue()
        q.offer(listOf(asset("p1", true), asset("l1"), asset("p2", true)))
        val slide = q.nextSlide(splitView = true)!!
        assertEquals("p1", slide.primary.id)
        assertEquals("p2", slide.secondary!!.id)         // skips landscape l1 to find the pair
        assertEquals(1, q.depth)                         // l1 remains
    }

    @Test fun portraitShowsSoloWhenNoPartnerOrSplitViewOff() {
        val q = SlideQueue()
        q.offer(listOf(asset("p1", true), asset("l1")))
        assertNull(q.nextSlide(splitView = true)!!.secondary)   // no second portrait available
        val q2 = SlideQueue()
        q2.offer(listOf(asset("p1", true), asset("p2", true)))
        assertNull(q2.nextSlide(splitView = false)!!.secondary) // split view toggled off
    }

    @Test fun noteShownRecordsBothPairMembers() {
        val q = SlideQueue()
        q.offer(listOf(asset("p1", true), asset("p2", true)))
        val slide = q.nextSlide(splitView = true)!!
        q.noteShown(slide)
        q.offer(listOf(asset("p1", true), asset("p2", true), asset("x")))
        assertEquals(1, q.depth)                         // only "x" survives history
    }

    @Test fun starvationClearsHistorySoSmallLibrariesRepeat() {
        val q = SlideQueue()
        q.offer(listOf(asset("a"), asset("b")))
        q.noteShown(q.nextSlide(false)!!)
        q.noteShown(q.nextSlide(false)!!)
        assertEquals(0, q.depth)
        // Whole batch is history + queue empty -> history cleared, repeats allowed.
        q.offer(listOf(asset("a"), asset("b")))
        assertEquals(2, q.depth)
    }

    @Test fun historyRingEvictsOldestPastCapacity() {
        val q = SlideQueue(historyCapacity = 2)
        q.offer(listOf(asset("a"), asset("b"), asset("c")))
        repeat(3) { q.noteShown(q.nextSlide(false)!!) }  // history holds b, c ("a" evicted)
        q.offer(listOf(asset("a"), asset("b"), asset("c")))
        assertEquals(1, q.depth)                          // only "a" re-admitted
        assertEquals("a", q.nextSlide(false)!!.primary.id)
    }

    @Test fun emptyQueueReturnsNull() {
        assertNull(SlideQueue().nextSlide(splitView = true))
    }

    @Test fun offerDedupesRepeatedIdWithinSameBatch() {
        val q = SlideQueue()
        q.offer(listOf(asset("a"), asset("a")))
        assertEquals(1, q.depth)
        val first = q.nextSlide(splitView = false)!!
        assertEquals("a", first.primary.id)
        assertNull(q.nextSlide(splitView = false))          // "a" was only enqueued once
    }
}
