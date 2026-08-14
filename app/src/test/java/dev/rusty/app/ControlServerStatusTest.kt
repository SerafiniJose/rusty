package dev.rusty.app

import dev.rusty.app.ControlServerStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The status model is the ONE part of the remote-control service that is pure enough to test on
 * the JVM: the settings row reads it, the service writes it, and the two never meet in a test.
 * Mirrors [dev.rusty.app.renderer.RendererStatusPublisherTest] — including the inline dispatcher,
 * since the production default posts to the Android main looper, which does not exist here.
 */
class ControlServerStatusTest {

    private val runningUrl = "http://192.168.1.9:8765"

    @Before
    fun reset() {
        ControlServerStatus.setDispatcher { it.run() }   // inline, no Android main looper
        ControlServerStatus.resetForTest()
    }

    @Test
    fun `starts out stopped`() {
        assertEquals(State.Stopped, ControlServerStatus.current())
    }

    @Test
    fun `a start-to-running lifecycle reaches listeners in order`() {
        val seen = mutableListOf<State>()
        ControlServerStatus.addListener { seen += it }

        ControlServerStatus.publish(State.Starting)
        ControlServerStatus.publish(State.Running(runningUrl))

        assertEquals(
            "the replayed Stopped, then every transition in commit order",
            listOf(State.Stopped, State.Starting, State.Running(runningUrl)),
            seen,
        )
    }

    @Test
    fun `failed retains its message`() {
        ControlServerStatus.publish(State.Failed("bind failed: EADDRINUSE"))

        assertEquals(State.Failed("bind failed: EADDRINUSE"), ControlServerStatus.current())

        val seen = mutableListOf<State>()
        ControlServerStatus.addListener { seen += it }
        assertEquals(
            "the settings row shows the reason, so it must survive publication",
            "bind failed: EADDRINUSE",
            (seen.single() as State.Failed).message,
        )
    }

    @Test
    fun `a listener added late immediately receives the current state`() {
        ControlServerStatus.publish(State.Running(runningUrl))

        val seen = mutableListOf<State>()
        ControlServerStatus.addListener { seen += it }

        assertEquals(
            "the service is normally running long before the settings panel is opened",
            listOf(State.Running(runningUrl)),
            seen,
        )
    }

    @Test
    fun `a removed listener gets nothing further`() {
        val seen = mutableListOf<State>()
        val l: (State) -> Unit = { seen += it }
        ControlServerStatus.addListener(l)
        ControlServerStatus.removeListener(l)

        ControlServerStatus.publish(State.Running(runningUrl))

        assertEquals("only the replayed initial value", listOf(State.Stopped), seen)
    }

    @Test
    fun `a callback queued for a listener removed before it runs is suppressed`() {
        val queue = mutableListOf<Runnable>()
        ControlServerStatus.setDispatcher { queue += it }

        val seen = mutableListOf<State>()
        val l: (State) -> Unit = { seen += it }
        ControlServerStatus.addListener(l)
        queue.forEach { it.run() }; queue.clear()          // drain the replay
        seen.clear()

        ControlServerStatus.publish(State.Running(runningUrl))   // queues a callback...
        ControlServerStatus.removeListener(l)                    // ...removed before it runs
        queue.forEach { it.run() }

        assertTrue("callback for a removed listener must not fire", seen.isEmpty())
    }

    @Test
    fun `disabling clears a sticky failure — no live service exists to do it`() {
        ControlServerStatus.publish(State.Failed("bind failed"))

        ControlServerStatus.publishStoppedIfInactive()

        assertEquals(State.Stopped, ControlServerStatus.current())
    }

    @Test
    fun `disabling clears a pending Starting — a rapid on-off may cancel creation`() {
        ControlServerStatus.publish(State.Starting)

        ControlServerStatus.publishStoppedIfInactive()

        assertEquals(State.Stopped, ControlServerStatus.current())
    }

    @Test
    fun `a stop request against a Running server publishes nothing — onDestroy does`() {
        ControlServerStatus.publish(State.Running(runningUrl))

        ControlServerStatus.publishStoppedIfInactive()

        assertEquals(
            "stopService is a request, not a completed teardown",
            State.Running(runningUrl),
            ControlServerStatus.current(),
        )
    }

    @Test
    fun `resetForTest drops listeners so tests cannot leak into each other`() {
        val seen = mutableListOf<State>()
        ControlServerStatus.addListener { seen += it }
        seen.clear()

        ControlServerStatus.resetForTest()
        ControlServerStatus.publish(State.Running(runningUrl))

        assertTrue(seen.isEmpty())
        assertEquals(State.Running(runningUrl), ControlServerStatus.current())
    }
}
