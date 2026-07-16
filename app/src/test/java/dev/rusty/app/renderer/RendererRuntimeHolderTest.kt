package dev.rusty.app.renderer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RendererRuntimeHolderTest {

    private val inline: (Runnable) -> Unit = { it.run() }

    private class FakeBackend(
        var st: RendererState? = RendererState(),
        var pos: Long? = 0L,
        var name: String = "Rusty",
    ) : RendererRuntimeHolder.Backend {
        val commands = mutableListOf<RendererCommand>()
        override fun state() = st
        override fun positionMs() = pos
        override fun deviceName() = name
        override fun dispatch(command: RendererCommand) { commands.add(command) }
    }

    @Before fun setUp() {
        RendererRuntimeHolder.setDispatcher(inline)
        RendererStatusPublisher.setDispatcher(inline)
        RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.STOPPED, null))
    }

    @After fun tearDown() = RendererRuntimeHolder.resetForTest()

    @Test fun detachedReportsNoServiceState() {
        val snap = RendererRuntimeHolder.current()
        assertNull(snap.state)
        assertEquals(RendererStatus.STOPPED, snap.identity.status)
        assertNull(RendererRuntimeHolder.positionMs())
    }

    @Test fun addListenerReplaysCurrentSnapshot() {
        val backend = FakeBackend(name = "Kitchen")
        RendererRuntimeHolder.attach(backend)
        RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.RUNNING, "http://x/d.xml"))
        var seen: RendererUiSnapshot? = null
        RendererRuntimeHolder.addListener { seen = it }
        assertEquals("Kitchen", seen?.identity?.deviceName)
        assertEquals(RendererStatus.RUNNING, seen?.identity?.status)
        assertEquals("http://x/d.xml", seen?.identity?.lanAddress)
    }

    @Test fun publishChangedNotifiesListeners() {
        val backend = FakeBackend()
        RendererRuntimeHolder.attach(backend)
        var count = 0
        RendererRuntimeHolder.addListener { count++ }   // replay = 1
        backend.st = RendererState(transport = RendererTransport.PLAYING)
        RendererRuntimeHolder.publishChanged()          // -> 2
        assertEquals(2, count)
    }

    @Test fun statusChangeNotifiesListeners() {
        RendererRuntimeHolder.attach(FakeBackend())
        var last: RendererUiSnapshot? = null
        RendererRuntimeHolder.addListener { last = it }
        RendererStatusPublisher.publish(RendererStatusSnapshot(RendererStatus.FAILED, null))
        assertEquals(RendererStatus.FAILED, last?.identity?.status)
    }

    @Test fun commandsForwardToBackend() {
        val backend = FakeBackend()
        RendererRuntimeHolder.attach(backend)
        RendererRuntimeHolder.play()
        RendererRuntimeHolder.pause()
        RendererRuntimeHolder.stop()
        RendererRuntimeHolder.seek(1234L)
        assertEquals(
            listOf(RendererCommand.Play, RendererCommand.Pause, RendererCommand.Stop,
                RendererCommand.Seek(1234L)),
            backend.commands)
    }

    @Test fun commandsAreNoOpWhenDetached() {
        RendererRuntimeHolder.play()   // must not throw
        assertTrue(true)
    }

    @Test fun detachStopsUsingBackend() {
        val backend = FakeBackend(name = "Kitchen")
        RendererRuntimeHolder.attach(backend)
        RendererRuntimeHolder.detach(backend)
        assertEquals("", RendererRuntimeHolder.current().identity.deviceName)
        assertNull(RendererRuntimeHolder.current().state)
    }
}
