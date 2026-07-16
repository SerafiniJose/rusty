package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RendererStatusPublisherTest {

    @Before
    fun reset() {
        RendererStatusPublisher.setDispatcher { it.run() }   // inline, no Android main looper
        RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.STOPPED, null))
    }

    private val running = RendererStatusSnapshot(RendererStatus.RUNNING, "http://1.2.3.4:49152/upnp/device.xml")

    @Test
    fun `starts out stopped with no url`() {
        assertEquals(RendererStatus.STOPPED, RendererStatusPublisher.current().status)
        assertNull(RendererStatusPublisher.current().descriptionUrl)
    }

    @Test
    fun `adding a listener replays the current value immediately`() {
        RendererStatusPublisher.publish(running)
        val seen = mutableListOf<RendererStatusSnapshot>()
        RendererStatusPublisher.addListener { seen += it }
        assertEquals("the service usually runs before the panel opens", listOf(running), seen)
    }

    @Test
    fun `listeners get subsequent updates`() {
        val seen = mutableListOf<RendererStatusSnapshot>()
        RendererStatusPublisher.addListener { seen += it }
        RendererStatusPublisher.publish(running)
        assertEquals(RendererStatus.RUNNING, seen.last().status)
    }

    @Test
    fun `a removed listener gets nothing further`() {
        val seen = mutableListOf<RendererStatusSnapshot>()
        val l: (RendererStatusSnapshot) -> Unit = { seen += it }
        RendererStatusPublisher.addListener(l)
        RendererStatusPublisher.removeListener(l)
        RendererStatusPublisher.publish(running)
        assertEquals("only the replayed initial value", 1, seen.size)
    }

    @Test
    fun `a callback queued for a listener removed before it runs is suppressed`() {
        val queue = mutableListOf<Runnable>()
        RendererStatusPublisher.setDispatcher { queue += it }

        val seen = mutableListOf<RendererStatusSnapshot>()
        val l: (RendererStatusSnapshot) -> Unit = { seen += it }
        RendererStatusPublisher.addListener(l)
        queue.forEach { it.run() }; queue.clear()      // drain the replay
        seen.clear()

        RendererStatusPublisher.publish(running)       // queues a callback...
        RendererStatusPublisher.removeListener(l)      // ...removed before it runs
        queue.forEach { it.run() }

        assertTrue("callback for a removed listener must not fire", seen.isEmpty())
    }

    @Test
    fun `syncFromPrefs against an already-RUNNING service must not regress to STARTING`() {
        RendererStatusPublisher.publish(running)
        RendererStatusPublisher.publishStartingUnlessRunning()
        assertEquals(running, RendererStatusPublisher.current())
    }

    @Test
    fun `publishStartingUnlessRunning moves a stopped renderer to STARTING`() {
        RendererStatusPublisher.publishStartingUnlessRunning()
        assertEquals(RendererStatus.STARTING, RendererStatusPublisher.current().status)
    }

    @Test
    fun `stop clears a sticky FAILED — no live service exists to do it`() {
        RendererStatusPublisher.publishFailed()
        assertEquals(RendererStatus.FAILED, RendererStatusPublisher.current().status)
        RendererStatusPublisher.publishStoppedIfInactive()
        assertEquals(RendererStatus.STOPPED, RendererStatusPublisher.current().status)
    }

    @Test
    fun `stop clears a pending STARTING — rapid start-stop may cancel creation before onCreate`() {
        RendererStatusPublisher.publishStartingUnlessRunning()
        RendererStatusPublisher.publishStoppedIfInactive()
        assertEquals(RendererStatus.STOPPED, RendererStatusPublisher.current().status)
    }

    @Test
    fun `a stop request against a RUNNING service publishes nothing — onDestroy does`() {
        RendererStatusPublisher.publish(running)
        RendererStatusPublisher.publishStoppedIfInactive()
        assertEquals(
            "stopService is a request, not a completed teardown",
            RendererStatus.RUNNING, RendererStatusPublisher.current().status
        )
    }

    @Test
    fun `RUNNING may carry a null url — running but no routable network address`() {
        RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.RUNNING, null))
        assertEquals(RendererStatus.RUNNING, RendererStatusPublisher.current().status)
        assertNull(RendererStatusPublisher.current().descriptionUrl)
    }
}
