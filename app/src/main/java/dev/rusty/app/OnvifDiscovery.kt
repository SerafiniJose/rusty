package dev.rusty.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private const val ONVIF_MULTICAST_ADDRESS = "239.255.255.250"
private const val ONVIF_MULTICAST_PORT = 3702
private const val SOCKET_TIMEOUT_MS = 250

/**
 * The socket/lock transport WS-Discovery runs over. [OnvifDiscoveryProtocol] owns the message
 * format; this interface owns getting bytes onto and off the network, so [OnvifDiscovery] can be
 * exercised with a scripted fake in pure-JVM tests instead of a real [MulticastSocket].
 */
interface DiscoveryIo {
    fun acquireMulticastLock()
    fun releaseMulticastLock()

    /** Sends [payload] to the ONVIF multicast group, bound to the Wi-Fi interface. */
    fun send(payload: ByteArray)

    /** Waits up to [remainingMs] for one datagram, returned as UTF-8 text; null on timeout. */
    fun receive(remainingMs: Long): String?

    /** The device's own LAN address and its subnet prefix length, e.g. `"192.168.2.222" to 24`. */
    fun localAddress(): Pair<String, Int>
    fun close()
}

/**
 * Runs one ONVIF WS-Discovery scan: acquire the Wi-Fi multicast lock, broadcast a Probe, collect
 * ProbeMatch replies for [windowMs], then always release the lock and close the socket — whether
 * the scan finished normally, [io] threw, or the calling coroutine was cancelled.
 */
class OnvifDiscovery(
    private val io: DiscoveryIo,
    private val windowMs: Long = 3000L,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun scan(): List<DiscoveredCamera> = withContext(dispatcher) {
        val replies = mutableListOf<DiscoveredCamera>()
        try {
            io.acquireMulticastLock()
            io.send(OnvifDiscoveryProtocol.buildProbe(UUID.randomUUID().toString()))

            val start = clock()
            while (true) {
                ensureActive()
                val remaining = windowMs - (clock() - start)
                if (remaining <= 0) break
                val datagram = io.receive(remaining) ?: continue
                OnvifDiscoveryProtocol.parseProbeMatch(datagram)?.let { replies.add(it) }
            }
        } finally {
            io.releaseMulticastLock()
            io.close()
        }
        OnvifDiscoveryProtocol.dedupe(replies)
    }
}

/**
 * Thin Android I/O: a [MulticastSocket] on the ONVIF discovery group plus a Wi-Fi
 * [WifiManager.MulticastLock]. Not unit-tested (no Robolectric in this project) — [OnvifDiscovery]
 * carries all the logic worth testing and is exercised against a fake [DiscoveryIo] instead.
 */
class AndroidDiscoveryIo(private val context: Context) : DiscoveryIo {

    private val groupAddress = InetAddress.getByName(ONVIF_MULTICAST_ADDRESS)
    private var multicastLock: WifiManager.MulticastLock? = null

    // Lazy + guarded: a construction/bind failure (permission denied, no network, ...) must
    // surface when the socket is actually used (send() throws, receive() reports no reply), not
    // crash AndroidDiscoveryIo(context) itself or the eager field-initializer that used to run
    // here.
    // A plain Lazy (not `by lazy`) so close() can check isInitialized() and skip creating a
    // socket — solely to close it — for a scan that never got as far as sending.
    private val socketLazy: Lazy<MulticastSocket?> = lazy { createSocket() }
    private val socket: MulticastSocket? get() = socketLazy.value

    private fun createSocket(): MulticastSocket? = runCatching {
        MulticastSocket().apply {
            soTimeout = SOCKET_TIMEOUT_MS
            bindToWifi(this)
        }
    }.onFailure { Log.w(TAG, "Failed to create discovery socket", it) }.getOrNull()

    /**
     * Binds [socket] to the Wi-Fi network so the probe leaves (and replies are read from) the
     * Wi-Fi interface even on a multi-homed device (VPN/Ethernet/tethering active) — otherwise
     * the OS is free to route the multicast send out whichever network it currently prefers,
     * which can silently make discovery find nothing. Degrades gracefully through two fallbacks
     * and finally an unbound socket; never throws.
     */
    private fun bindToWifi(socket: MulticastSocket) {
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            val wifiNetwork = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return // no Wi-Fi network up — leave the socket unbound rather than guess

            val boundViaNetwork = runCatching { wifiNetwork.bindSocket(socket) }.isSuccess
            if (boundViaNetwork) return

            // Fallback: bind by the Wi-Fi network's own local interface/address.
            runCatching {
                val linkProperties = cm.getLinkProperties(wifiNetwork) ?: return
                val ipv4 = linkProperties.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return
                val iface = NetworkInterface.getByInetAddress(ipv4.address) ?: return
                socket.networkInterface = iface
            }
        }.onFailure { Log.w(TAG, "Failed to bind discovery socket to Wi-Fi", it) }
    }

    override fun acquireMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
            multicastLock = wifi.createMulticastLock("rusty-onvif").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.onFailure { Log.w(TAG, "Failed to acquire multicast lock", it) }
    }

    override fun releaseMulticastLock() {
        runCatching { multicastLock?.release() }
            .onFailure { Log.w(TAG, "Failed to release multicast lock", it) }
        multicastLock = null
    }

    override fun send(payload: ByteArray) {
        val s = socket ?: throw IOException("Discovery socket unavailable")
        val packet = DatagramPacket(payload, payload.size, groupAddress, ONVIF_MULTICAST_PORT)
        s.send(packet)
    }

    override fun receive(remainingMs: Long): String? {
        val s = socket ?: return null
        val buffer = ByteArray(65_507) // max UDP payload
        val packet = DatagramPacket(buffer, buffer.size)
        s.soTimeout = remainingMs.coerceIn(1, SOCKET_TIMEOUT_MS.toLong()).toInt()
        return try {
            s.receive(packet)
            String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun localAddress(): Pair<String, Int> {
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "0.0.0.0" to 0
            val network = cm.activeNetwork ?: return "0.0.0.0" to 0
            val linkProperties = cm.getLinkProperties(network) ?: return "0.0.0.0" to 0
            val ipv4 = linkProperties.linkAddresses.firstOrNull { it.address is java.net.Inet4Address }
            if (ipv4 != null) {
                val host = ipv4.address.hostAddress
                if (host != null) return host to ipv4.prefixLength
            }
        }.onFailure { Log.w(TAG, "Failed to read local address", it) }
        return "0.0.0.0" to 0
    }

    override fun close() {
        if (socketLazy.isInitialized()) {
            runCatching { socketLazy.value?.close() }
        }
    }

    private companion object {
        const val TAG = "AndroidDiscoveryIo"
    }
}
