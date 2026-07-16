package dev.rusty.app.renderer

import android.util.Log
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Thin Android glue around [SsdpMessages]: owns the multicast UDP socket, the Wi-Fi
 * network-interface selection and the timing (random M-SEARCH reply delay, periodic
 * alive re-announce). All wire-format/matching decisions live in [SsdpMessages]; this
 * class only opens sockets, schedules sends and feeds datagrams to the pure parser.
 *
 * The caller (the service) owns the WifiManager MulticastLock — this class does not
 * acquire one itself, matching the task-10 brief.
 */
class SsdpEndpoint(private val identityProvider: () -> SsdpIdentity) {

    companion object {
        private const val TAG = "SsdpEndpoint"
        private const val SSDP_ADDRESS = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val RECEIVE_BUFFER_BYTES = 4096
        private const val ALIVE_REPEAT_DELAY_MS = 500L
    }

    private var socket: MulticastSocket? = null
    private var boundInterface: NetworkInterface? = null
    private var receiveThread: Thread? = null
    private var executor: ScheduledThreadPoolExecutor? = null
    private var periodicAnnounce: ScheduledFuture<*>? = null
    @Volatile private var running = false

    /** [stop] is TERMINAL: an endpoint belongs to one service instance, and a stopped one must
     *  never come back. Without this, any late caller reaching [announceNow] after teardown (a
     *  rename queued on the service's event executor is the real case) would fall into the
     *  "not running ⇒ start()" branch and resurrect the socket, the receive thread and the periodic
     *  alive announcer for a renderer whose HTTP server is already gone. The retry path that branch
     *  exists for — [start] finding no Wi-Fi interface, a later network change re-announcing — is
     *  unaffected: it never calls [stop]. */
    @Volatile private var stopped = false

    /** For tests: whether the socket/loop are live. */
    internal val isRunning: Boolean get() = running

    /** Opens the multicast socket, starts the receive loop and announces `ssdp:alive`.
     *
     *  No-op while the identity is not advertisable (no routable LOCATION yet — see
     *  [SsdpMessages.isAdvertisable]): the renderer stays silent rather than promising the LAN a
     *  0.0.0.0 device description. The service calls [announceNow] the moment an address arrives,
     *  and that falls into the "not running ⇒ start()" branch, so the deferral heals itself. */
    fun start() {
        if (running || stopped) return
        // Silent by design: the deferral is a normal state, and the service logs it (with the
        // address it resolved) at every decision point. Keeping android.util.Log off this path is
        // also what lets the JVM test assert the guard without a Robolectric shadow.
        if (!advertisable()) return
        val sock = try {
            MulticastSocket(SSDP_PORT).apply { reuseAddress = true }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open SSDP socket on port $SSDP_PORT", e)
            return
        }
        socket = sock
        running = true

        val exec = ScheduledThreadPoolExecutor(1) { r ->
            Thread(r, "ssdp-sched").apply { isDaemon = true }
        }
        executor = exec

        joinCurrentInterface(sock)

        val thread = Thread({ receiveLoop(sock) }, "ssdp-recv")
        thread.isDaemon = true
        receiveThread = thread
        thread.start()

        announceAliveTwice(exec)
        schedulePeriodicAnnounce(exec)
    }

    /** Re-resolves the Wi-Fi interface (joining the group if it changed) and re-announces. Call
     *  after a network rebind — e.g. Wi-Fi coming up after [start] ran without one, or the local
     *  address changing. */
    fun announceNow() {
        if (!advertisable()) return   // no routable LOCATION yet — see start()
        if (!running) {
            start()
            return
        }
        val sock = socket ?: return
        joinCurrentInterface(sock)
        val exec = executor ?: return
        announceAliveTwice(exec)
    }

    /** Sends an `ssdp:byebye` burst for the CURRENT identity without tearing the endpoint down.
     *  Used by a rename: UPnP wants byebye(old) → description change → alive(new). MUST be called
     *  from a background thread (a datagram send on the main thread throws
     *  NetworkOnMainThreadException) — the rename path runs it on the service's event executor. */
    fun byebyeNow() {
        if (!running) return
        sendMulticast(SsdpMessages.byebyeNotifications(identityProvider()))
    }

    /** Stops the receive loop and closes the socket; optionally announces `ssdp:byebye` first.
     *  Terminal — see [stopped]. */
    fun stop(sendByebye: Boolean) {
        stopped = true
        if (!running) return
        running = false

        periodicAnnounce?.cancel(false)
        periodicAnnounce = null

        // The byebye burst must go through the executor — stop() is typically called from the
        // service's onDestroy on the MAIN thread, where a datagram send throws
        // NetworkOnMainThreadException. shutdown() + awaitTermination keeps the socket alive
        // until the byebye task has actually run (pending DELAYED alive tasks are dropped by
        // ScheduledThreadPoolExecutor's default after-shutdown policy, so this returns fast).
        val exec = executor
        executor = null
        if (exec != null) {
            if (sendByebye) {
                try {
                    exec.execute { sendMulticast(SsdpMessages.byebyeNotifications(identityProvider())) }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to submit ssdp:byebye", e)
                }
            }
            exec.shutdown()
            try {
                exec.awaitTermination(1, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val sock = socket
        socket = null
        val iface = boundInterface
        boundInterface = null
        if (sock != null) {
            try {
                if (iface != null) {
                    sock.leaveGroup(InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT), iface)
                }
            } catch (_: Exception) {
                // best-effort: the socket is about to be closed regardless
            }
            try {
                sock.close()
            } catch (_: Exception) {
            }
        }

        receiveThread = null
    }

    // -------------------------------------------------------------------
    // Interface selection
    // -------------------------------------------------------------------

    /** Whether the CURRENT identity may go on the wire. Read at every send: the identity is
     *  swapped (rename, network rebind) under our feet by design. */
    private fun advertisable(): Boolean = SsdpMessages.isAdvertisable(identityProvider())

    /**
     * The interface the advertisements leave on. It must be the one that OWNS the address in
     * LOCATION — otherwise the renderer multicasts "reach me at 192.168.1.50" out of an interface
     * that is not 192.168.1.50. Only when the advertised host belongs to no interface (a race with
     * a rebind) do we fall back to the old heuristic: the first up, non-loopback interface with a
     * site-local IPv4 (the Wi-Fi interface on these appliance devices).
     */
    private fun selectWifiInterface(): NetworkInterface? {
        val host = SsdpMessages.locationHost(identityProvider())
        val owner = host?.let { LanAddress.interfaceOwning(it) }
        if (owner != null) return owner
        return LanAddress.usableInterfaces()
            .firstOrNull { nif -> LanAddress.siteLocalIpv4(LanAddress.addressesOf(nif)) != null }
    }

    private fun joinCurrentInterface(sock: MulticastSocket) {
        val iface = selectWifiInterface()
        if (iface == null) {
            Log.w(TAG, "No usable Wi-Fi interface found; SSDP will retry on the next announceNow()")
            return
        }
        if (iface == boundInterface) return
        try {
            val group = InetSocketAddress(InetAddress.getByName(SSDP_ADDRESS), SSDP_PORT)
            sock.joinGroup(group, iface)
            boundInterface = iface
        } catch (e: Exception) {
            Log.w(TAG, "Failed to join SSDP multicast group on ${iface.name}", e)
        }
    }

    // -------------------------------------------------------------------
    // Receive loop (M-SEARCH -> scheduled unicast reply)
    // -------------------------------------------------------------------

    private fun receiveLoop(sock: MulticastSocket) {
        val buffer = ByteArray(RECEIVE_BUFFER_BYTES)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                sock.receive(packet)
            } catch (e: Exception) {
                if (!running) return
                Log.w(TAG, "SSDP receive failed", e)
                continue
            }
            try {
                handleDatagram(packet)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to handle SSDP datagram", e)
            }
        }
    }

    private fun handleDatagram(packet: DatagramPacket) {
        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        val search = SsdpMessages.parseMSearch(text) ?: return
        val exec = executor ?: return
        val senderAddress = packet.address
        val senderPort = packet.port
        val delayMs = Random.nextLong(0L, (search.mxSeconds * 1000L).coerceAtLeast(1L))
        try {
            exec.schedule(
                // Identity is read AT SEND TIME (inside buildSearchResponses): a reply scheduled
                // before a rename must carry the post-rename identity, not a stale CONFIGID.
                { buildSearchResponses(search).forEach { sendUnicast(it, senderAddress, senderPort) } },
                delayMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (_: Exception) {
            // executor rejected (shutting down) — drop the reply
        }
    }

    /** Regenerates the M-SEARCH responses from the CURRENT identity. Internal so the send-time
     *  contract is pinned by a JVM test. An identity with no routable LOCATION answers nothing —
     *  an M-SEARCH reply is an advertisement like any other. */
    internal fun buildSearchResponses(search: SsdpMSearch): List<String> {
        val id = identityProvider()
        if (!SsdpMessages.isAdvertisable(id)) return emptyList()
        return SsdpMessages.responsesFor(search, id)
    }

    // -------------------------------------------------------------------
    // Sending
    // -------------------------------------------------------------------

    private fun sendUnicast(message: String, address: InetAddress, port: Int) {
        val sock = socket ?: return
        try {
            val bytes = message.toByteArray(Charsets.UTF_8)
            sock.send(DatagramPacket(bytes, bytes.size, address, port))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send SSDP unicast reply to $address:$port", e)
        }
    }

    private fun sendMulticast(messages: List<String>) {
        val sock = socket ?: return
        val group = try {
            InetAddress.getByName(SSDP_ADDRESS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve SSDP multicast address", e)
            return
        }
        for (message in messages) {
            try {
                val bytes = message.toByteArray(Charsets.UTF_8)
                sock.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send SSDP multicast notification", e)
            }
        }
    }

    /** Both bursts go through the executor: callers run on arbitrary threads — including the
     *  MAIN thread (service onCreate) and ConnectivityManager callback threads — and a datagram
     *  send on the main thread throws NetworkOnMainThreadException. No send may ever run on the
     *  caller's thread. */
    private fun announceAliveTwice(exec: ScheduledThreadPoolExecutor) {
        try {
            exec.execute { sendAlive() }
            exec.schedule({ sendAlive() }, ALIVE_REPEAT_DELAY_MS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            // executor rejected (shutting down) — drop the announcements
        }
    }

    private fun schedulePeriodicAnnounce(exec: ScheduledThreadPoolExecutor) {
        val periodSeconds = (SsdpMessages.MAX_AGE_SECONDS / 2).toLong()
        periodicAnnounce = exec.scheduleAtFixedRate(
            { sendAlive() },
            periodSeconds,
            periodSeconds,
            TimeUnit.SECONDS,
        )
    }

    /** Identity is read AT SEND TIME, and re-checked: the periodic re-announce outlives any single
     *  identity, and it must never carry a LOCATION nobody can GET. */
    private fun sendAlive() {
        val id = identityProvider()
        if (!SsdpMessages.isAdvertisable(id)) return
        sendMulticast(SsdpMessages.aliveNotifications(id))
    }
}
