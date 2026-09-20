package dev.rusty.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * Loopback RTSP proxy for ONE camera stream: media3 connects here, every byte goes to the camera
 * unchanged, and every byte comes back unchanged — except a DESCRIBE response whose SDP lacks the
 * H.264 fmtp line, which gets [SdpRepair.inject]ed with the parameter sets sniffed earlier.
 *
 * Why no URI rewriting: the camera matches the PATH of the request line, and Digest `uri=` must
 * equal what the camera sees, so passing the request line through verbatim is exactly right.
 * `Content-Base` comes back naming the camera's own address; media3 sends its SETUP/PLAY for that
 * URL over this same socket, so that is fine too. `Authorization` is never touched either.
 *
 * Pure JVM; owned by the live-stream repairer. One instance per live session. `open` (and `start`
 * / `close` open) so a repairer test can substitute a fake that never binds.
 */
open class SdpRepairProxy(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val sps: ByteArray,
    private val pps: ByteArray,
    private val connectTimeoutMs: Int = 3_000,
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private var listener: ServerSocket? = null
    private val sockets: MutableSet<Socket> = Collections.synchronizedSet(HashSet())
    @Volatile private var closed = false

    /** The bound port, or null before [start] and once [close] has run (a closed proxy has none). */
    val port: Int? get() = if (closed) null else listener?.localPort

    /**
     * Binds 127.0.0.1:0 and starts accepting; returns the port, or null when the bind failed or the
     * proxy is already closed. Idempotent: a second call returns the port already bound rather than
     * a second [ServerSocket] and a second accept thread that [close] would not know about.
     *
     * The whole body runs under the monitor [track]/[close] share, so a [close] racing a [start]
     * either loses (and then closes this listener) or wins (and this returns null having bound
     * nothing) — never lands in between and leaks the socket.
     */
    open fun start(): Int? {
        synchronized(sockets) {
            if (closed) return null
            listener?.let { return it.localPort }
            val ss = try {
                ServerSocket().apply {
                    reuseAddress = true
                    // The IPv4 loopback literally, NOT InetAddress.getLoopbackAddress(): on
                    // Android that returns ::1, so the listener came up on [::1]:port while the
                    // proxy URL LiveStreamRepairer hands media3 is hard-coded
                    // "rtsp://127.0.0.1:<port>/…" — media3 dialled the IPv4 loopback and got
                    // ECONNREFUSED forever. The literal byte form cannot reach a resolver at all,
                    // so the 127.0.0.1 in that URL is true by construction.
                    bind(InetSocketAddress(IPV4_LOOPBACK, 0), MAX_RELAYS)
                }
            } catch (e: IOException) {
                log("proxy bind failed: ${e.javaClass.simpleName}"); return null
            }
            listener = ss
            Thread({ acceptLoop(ss) }, "sdp-proxy-accept").apply { isDaemon = true; start() }
            return ss.localPort
        }
    }

    /**
     * Accept, pair with a fresh upstream socket, hand both to a relay thread. Both sockets are
     * registered HERE, on this one thread, so the cap counts whole connections rather than whatever
     * a relay thread has got round to creating, and so a [close] racing an accept can never leave a
     * socket or a thread behind: [track] either hands them to [close] or refuses them outright.
     */
    private fun acceptLoop(ss: ServerSocket) {
        while (!closed) {
            val client = try {
                ss.accept()
            } catch (e: IOException) {
                if (closed || ss.isClosed) break else continue
            }
            if (sockets.size >= MAX_SOCKETS) {
                log("proxy refused a connection: already at capacity ($MAX_RELAYS)")
                runCatching { client.close() }
                continue
            }
            val upstream = Socket()
            if (!track(client, upstream)) { closeBoth(client, upstream); break }
            val started = runCatching {
                Thread({ relay(client, upstream) }, "sdp-proxy-up").apply { isDaemon = true; start() }
            }
            if (started.isFailure) closeBoth(client, upstream)
        }
    }

    /** Registers a connection's two sockets unless [close] already ran; false = caller must close them. */
    private fun track(client: Socket, upstream: Socket): Boolean = synchronized(sockets) {
        if (closed) return false
        sockets.add(client); sockets.add(upstream)
        return true
    }

    private fun relay(client: Socket, upstream: Socket) {
        var down: Thread? = null
        try {
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            upstream.connect(InetSocketAddress(upstreamHost, upstreamPort), connectTimeoutMs)
            down = Thread({
                try { relayDown(upstream.getInputStream(), client.getOutputStream()) } catch (e: IOException) { /* peer went away */ }
                finally { closeBoth(client, upstream) }
            }, "sdp-proxy-down").apply { isDaemon = true; start() }
            pump(client.getInputStream(), upstream.getOutputStream())
        } catch (e: IOException) {
            log("proxy relay ended: ${e.javaClass.simpleName}")
        } finally {
            // Close FIRST, join second: the slot must be free the moment either side goes away, or
            // media3's reconnect-on-error storm would find the proxy at capacity. Closing the
            // upstream socket is also what unparks the down thread's blocking read.
            closeBoth(client, upstream)
            try { down?.join(1_000) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
        }
    }

    /** Upstream → client: RTSP messages one at a time (so a DESCRIBE body can be rewritten) until
     *  the first interleaved frame, then raw bytes. */
    private fun relayDown(rawIn: InputStream, out: OutputStream) {
        val eof = EofSensingStream(rawIn)
        val input = DataInputStream(BufferedInputStream(eof))
        while (true) {
            input.mark(1)
            val first = input.read()
            if (first < 0) return
            input.reset()
            // A '$' at a message boundary is an interleaved RTP/RTCP frame: nothing after it on this
            // connection is ours to parse, so copy the rest byte for byte.
            if (first == '$'.code) { pump(input, out); return }
            val head = RtspClientMessages.readHead(input) ?: return
            // readHead hands back what it got when EOF lands mid-head; forwarding that would append
            // a blank line and hand media3 a complete-looking message the camera never finished.
            if (eof.sawEof) return
            val headers = head.drop(1)
                .filter { it.contains(':') }
                .associate { it.substringBefore(':').trim().uppercase() to it.substringAfter(':').trim() }
            val len = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
            if (len < 0 || len > MAX_BODY) throw IOException("bad content length")
            var body = ByteArray(len).also { if (len > 0) input.readFully(it) }
            var lines = head
            if (headers["CONTENT-TYPE"]?.startsWith("application/sdp", ignoreCase = true) == true) {
                val sdp = String(body, Charsets.UTF_8)
                SdpRepair.missingH264Fmtp(sdp)?.let { pt ->
                    body = SdpRepair.inject(sdp, pt, sps, pps).toByteArray(Charsets.UTF_8)
                    lines = head.map { if (it.startsWith("Content-Length", ignoreCase = true)) "Content-Length: ${body.size}" else it }
                    log("proxy repaired SDP (pt $pt)")
                }
            }
            // ISO-8859-1, not ASCII: RTSP header text is latin-1 (RFC 2326 §15.1) and readHead
            // decoded it byte-for-char, so this re-encoding is exactly what the camera sent — an
            // accented Digest realm reaches media3 intact and its hash still matches.
            out.write((lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1))
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    /** Remembers whether the stream underneath ever answered "end of stream". */
    private class EofSensingStream(private val delegate: InputStream) : InputStream() {
        @Volatile var sawEof = false
            private set
        override fun read(): Int = delegate.read().also { if (it < 0) sawEof = true }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it < 0) sawEof = true }
        override fun available(): Int = delegate.available()
        override fun close() = delegate.close()
    }

    private fun pump(input: InputStream, out: OutputStream) {
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) return
            out.write(buf, 0, n); out.flush()
        }
    }

    private fun closeBoth(a: Socket, b: Socket) {
        runCatching { a.close() }; runCatching { b.close() }
        sockets.remove(a); sockets.remove(b)
    }

    open override fun close() {
        // The listener is read under the monitor too, so a start() that is still binding cannot
        // hand us a socket we have already walked past.
        val (ss, live) = synchronized(sockets) {
            if (closed) return
            closed = true
            listener to sockets.toList().also { sockets.clear() }
        }
        runCatching { ss?.close() }
        live.forEach { runCatching { it.close() } }
    }

    private companion object {
        /** media3 opens exactly one; retries reconnect sequentially, so four is already generous. */
        const val MAX_RELAYS = 4
        const val MAX_SOCKETS = MAX_RELAYS * 2   // client + upstream per relay
        /** A response body big enough to be a bug or an attack, not an SDP: refuse rather than allocate. */
        const val MAX_BODY = 4 * 1024 * 1024

        /** 127.0.0.1 from its four bytes — no name lookup, no IPv6, no platform opinion. */
        val IPV4_LOOPBACK: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    }
}
