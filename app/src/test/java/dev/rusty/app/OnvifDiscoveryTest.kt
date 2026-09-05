package dev.rusty.app

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hand-written fake [DiscoveryIo]. [receiveScript] is consumed in order by [receive]; each call
 * advances a virtual clock by [stepMs] and returns null once the caller's [remainingMs] budget
 * would be exhausted, mimicking a real socket timeout.
 */
private class FakeDiscoveryIo(
    private val receiveScript: MutableList<String?> = mutableListOf(),
    private val stepMs: Long = 100L,
    private val sendThrows: Boolean = false,
) : DiscoveryIo {
    var lockAcquired = false
    var lockReleased = false
    var closed = false
    val sentPayloads = mutableListOf<ByteArray>()
    var receiveCalls = 0

    override fun acquireMulticastLock() {
        lockAcquired = true
    }

    override fun releaseMulticastLock() {
        lockReleased = true
    }

    override fun send(payload: ByteArray) {
        if (sendThrows) throw IOException("send failed")
        sentPayloads.add(payload)
    }

    override fun receive(remainingMs: Long): String? {
        receiveCalls++
        if (remainingMs <= 0) return null
        return if (receiveScript.isNotEmpty()) receiveScript.removeAt(0) else null
    }

    override fun localAddress(): Pair<String, Int> = "192.168.1.50" to 24

    override fun close() {
        closed = true
    }
}

class OnvifDiscoveryTest {

    private fun probeMatch(uuid: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope" xmlns:wsa="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:wsd="http://schemas.xmlsoap.org/ws/2005/04/discovery">
          <soap:Body>
            <wsd:ProbeMatches>
              <wsd:ProbeMatch>
                <wsa:EndpointReference><wsa:Address>urn:uuid:$uuid</wsa:Address></wsa:EndpointReference>
                <wsd:Scopes>onvif://www.onvif.org/name/Cam1</wsd:Scopes>
                <wsd:XAddrs>http://192.168.1.10/onvif/device_service</wsd:XAddrs>
              </wsd:ProbeMatch>
            </wsd:ProbeMatches>
          </soap:Body>
        </soap:Envelope>
    """.trimIndent()

    @Test
    fun `scan returns parsed and deduped cameras from datagrams`() = runTest {
        val uuidA = "aaaaaaaa-0000-0000-0000-000000000001"
        val uuidB = "bbbbbbbb-0000-0000-0000-000000000002"
        val io = FakeDiscoveryIo(
            receiveScript = mutableListOf(
                probeMatch(uuidA),
                "not xml garbage <<<",
                probeMatch(uuidA), // duplicate of the first
                probeMatch(uuidB),
            ),
        )
        val discovery = OnvifDiscovery(io = io, windowMs = 1000L, dispatcher = StandardTestDispatcher(testScheduler))

        val cameras = discovery.scan()

        assertEquals(2, cameras.size)
        assertEquals(setOf("urn:uuid:$uuidA", "urn:uuid:$uuidB"), cameras.map { it.endpointUuid }.toSet())
        assertTrue(io.lockAcquired)
        assertTrue(io.lockReleased)
        assertTrue(io.closed)
    }

    @Test
    fun `lock is released even when send throws`() = runTest {
        val io = FakeDiscoveryIo(sendThrows = true)
        val discovery = OnvifDiscovery(io = io, windowMs = 1000L, dispatcher = StandardTestDispatcher(testScheduler))

        var thrown: Throwable? = null
        try {
            discovery.scan()
        } catch (e: IOException) {
            thrown = e
        }
        assertTrue(thrown is IOException)

        assertTrue(io.lockReleased)
        assertTrue(io.closed)
    }

    @Test
    fun `receive loop stops when remainingMs hits 0`() = runTest {
        // Every receive call reports null (a timeout) and advances a virtual clock by 400ms;
        // with a 1000ms window that allows at most 3 calls before remaining <= 0.
        var elapsed = 0L
        val io = object : DiscoveryIo {
            var lockReleased = false
            var closed = false
            var receiveCalls = 0
            override fun acquireMulticastLock() {}
            override fun releaseMulticastLock() { lockReleased = true }
            override fun send(payload: ByteArray) {}
            override fun receive(remainingMs: Long): String? {
                receiveCalls++
                elapsed += 400L
                return null
            }
            override fun localAddress(): Pair<String, Int> = "192.168.1.50" to 24
            override fun close() { closed = true }
        }
        val discovery = OnvifDiscovery(
            io = io,
            windowMs = 1000L,
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { elapsed },
        )

        val cameras = discovery.scan()

        assertEquals(0, cameras.size)
        assertTrue(io.receiveCalls in 2..4)
        assertTrue(io.lockReleased)
        assertTrue(io.closed)
    }

    @Test
    fun `cancellation closes the socket`() = runTest {
        // Runs the scan on a real dispatcher so the loop is genuinely mid-flight (blocked in
        // io.receive) when we cancel it — a virtual/single-threaded test dispatcher can't model
        // "cancel while another call is in progress" since nothing would be running concurrently
        // to cancel. [started] confirms the loop has entered before we cancel it.
        val started = CountDownLatch(1)
        val io = object : DiscoveryIo {
            @Volatile var closed = false
            override fun acquireMulticastLock() {}
            override fun releaseMulticastLock() {}
            override fun send(payload: ByteArray) {}
            override fun receive(remainingMs: Long): String? {
                started.countDown()
                Thread.sleep(20)
                return null
            }
            override fun localAddress(): Pair<String, Int> = "192.168.1.50" to 24
            override fun close() { closed = true }
        }
        val discovery = OnvifDiscovery(io = io, windowMs = 60_000L, dispatcher = Dispatchers.Default)

        val job = launch(Dispatchers.Default) { discovery.scan() }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue(io.closed)
    }
}
