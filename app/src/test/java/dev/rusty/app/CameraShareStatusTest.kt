package dev.rusty.app

import dev.rusty.app.CameraShareStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CameraShareStatusTest {

    @Before
    fun reset() {
        CameraShareStatus.setDispatcher { it.run() }   // inline, no Android main looper
        CameraShareStatus.resetForTest()
    }

    @Test fun `listener replays current state and sees publishes`() {
        CameraShareStatus.publish(State.Off)
        val seen = mutableListOf<State>()
        val l: (State) -> Unit = { seen.add(it) }
        CameraShareStatus.addListener(l)
        CameraShareStatus.publish(State.Streaming(2))
        CameraShareStatus.removeListener(l)
        CameraShareStatus.publish(State.Ready)
        assertEquals(listOf(State.Off, State.Streaming(2)), seen)
        assertEquals(State.Ready, CameraShareStatus.current())
    }

    @Test
    fun `a callback queued for a listener removed before it runs is suppressed`() {
        val queue = mutableListOf<Runnable>()
        CameraShareStatus.setDispatcher { queue += it }

        val seen = mutableListOf<State>()
        val l: (State) -> Unit = { seen += it }
        CameraShareStatus.addListener(l)
        queue.forEach { it.run() }; queue.clear()          // drain the replay
        seen.clear()

        CameraShareStatus.publish(State.Streaming(2))   // queues a callback...
        CameraShareStatus.removeListener(l)                    // ...removed before it runs
        queue.forEach { it.run() }

        assertTrue("callback for a removed listener must not fire", seen.isEmpty())
    }

    @Test fun `streaming defaults its measurements to zero so a bare viewer count still compares equal`() {
        assertEquals(State.Streaming(2), State.Streaming(2, 0, 0, 0, 0))
    }
}
