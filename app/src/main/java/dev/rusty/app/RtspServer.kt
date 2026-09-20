package dev.rusty.app

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.Collections

/**
 * Accept loop + one thread per viewer, wrapping the pure [RtspProtocol]/[H264RtpPacketizer] pieces.
 * Pure JVM (`java.net`/`java.io`/`java.util.concurrent` only) so RtspServerTest can drive a whole
 * session over loopback.
 *
 * ## Threads and locks
 *
 * Per connection there are TWO threads: a reader (parses requests, writes responses, attaches and
 * detaches from the hub) and a writer (drains that client's [ViewerFrameQueue] onto the socket).
 * The hub's codec-callback thread only ever ENQUEUES — see [Client.onAccessUnit] — and the queue's
 * flush-and-resync overflow policy lives in [ViewerFrameQueue], with the reasoning for it.
 *
 * Lock order, never the other way round:
 *
 *     Client.stateLock  ->  hub's lock (attach/detach)
 *
 * [Client.writeLock] is a leaf and is taken alone. Nothing reaches back into the hub while holding
 * either of them, and the blocking calls — `hub.describe()` and `hub.attach()`, both of which can
 * take SECONDS while the camera opens — run on the reader thread with no client lock held, so
 * [stop] never queues behind a camera open.
 */
class RtspServer(
    private val port: Int,
    private val hub: CameraShareHub,
    private val password: () -> String?,
    private val log: (String) -> Unit = {},
    private val sessionTimeoutS: Int = 60,
) {
    private val random = SecureRandom()
    private var listener: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients: MutableSet<Client> = Collections.synchronizedSet(HashSet())
    @Volatile private var stopped = false

    /** The stream position a PLAY advertises in `RTP-Info`, published as one immutable pair so a
     *  sequence number from one frame can never be paired with a timestamp from another. */
    private data class RtpPosition(val seq: Int, val rtpTimestamp: Long)

    fun start(): Boolean {
        stopped = false
        val ss = try {
            // Bound explicitly with SO_REUSEADDR so toggling the share off and on again does not
            // hit "Address already in use" while a torn-down viewer socket sits in TIME_WAIT.
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port), BACKLOG) }
        } catch (e: IOException) {
            log("rtsp bind failed: ${e.message}")
            return false
        }
        listener = ss
        hub.onError = { closeAllClients() }
        acceptThread = Thread({
            while (!stopped) {
                val socket = try {
                    ss.accept()
                } catch (e: IOException) {
                    // accept() throws IOException for RECOVERABLE reasons too — ECONNABORTED when
                    // a peer resets between SYN and accept, EMFILE/ENFILE at the fd limit — so
                    // ending the loop on one would leave the port bound with nothing accepting:
                    // new viewers would sit in the kernel backlog while the UI still reported the
                    // share as up. Same shape as ControlHttpServer/RendererHttpServer: only a real
                    // stop ends it, and the isClosed check is what keeps the stop path (where
                    // accept() fails instantly, forever) from spinning hot.
                    if (stopped || ss.isClosed) break
                    log("rtsp accept failed: ${e.message}")
                    continue
                }
                // Refused BEFORE this connection's buffers and threads exist. Deliberately no 503
                // first: that is a blocking write on the accept thread, which a peer with a full
                // receive window could stall — the very denial of service the cap exists to stop.
                if (clients.size >= MAX_CLIENTS) {
                    log("rtsp connection refused: $MAX_CLIENTS viewers already connected")
                    runCatching { socket.close() }
                    continue
                }
                val c = try {
                    Client(socket)
                } catch (t: Throwable) {
                    // A peer that vanished between accept() and getOutputStream(), or an
                    // OutOfMemoryError allocating the 64 KB output buffer, must not take the accept
                    // loop — and with it the whole server — down with it.
                    log("rtsp client setup failed: $t")
                    runCatching { socket.close() }
                    continue
                }
                clients.add(c)
                // Belt and braces against the stop() race: stop() joins this thread before it
                // closes clients, but if that join ever timed out, a client added here afterwards
                // would be in no server's set and would stream on until its read timeout.
                if (stopped) { c.close(); break }
                c.startReader()
            }
        }, "rtsp-accept").apply { isDaemon = true; start() }
        return true
    }

    fun stop() {
        stopped = true
        runCatching { listener?.close() }
        listener = null
        // Join the accept thread BEFORE closing clients, never after. accept() can already have
        // returned a socket when stop() begins; that client joins `clients` after any snapshot
        // taken here, so closing first would leave it in no set that a later stop() can reach —
        // a viewer still receiving video, camera indicator lit, until its 120 s read timeout, with
        // the share switched off. The join cannot itself block on a client: closing the listener
        // above breaks accept(), and the rest of that loop is a bounded set of field assignments.
        acceptThread?.join(1_000)
        acceptThread = null
        closeAllClients()
        hub.onError = {}
    }

    fun clientCount(): Int = clients.size

    private fun closeAllClients() { synchronized(clients) { clients.toList() }.forEach { it.close() } }

    /** 16 hex chars from a CSPRNG. Session ids must match `[\w$\-_.+]+` (media3 validates the
     *  Session header with `matches()`), and the Digest nonce must be unpredictable and free of
     *  `"`, CR and LF — hex satisfies both. */
    private fun hex(): String = ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }

    private inner class Client(private val socket: Socket) : FrameSink {
        /** One protocol instance PER CONNECTION: the Digest nonce is per-instance and lazily
         *  minted, so sharing one would share the nonce and make a captured Authorization header
         *  replayable across connections. */
        private val protocol = RtspProtocol(sessionTimeoutS = sessionTimeoutS, sessionIdSource = ::hex, nonceSource = ::hex)
        private val packetizer = H264RtpPacketizer(ssrc = random.nextInt())
        /** Serializes the two producers on this socket: RTSP responses (reader thread) and
         *  interleaved RTP frames (writer thread). Each writes one complete unit under it. */
        private val writeLock = Any()
        /** Guards [attached]/[closed] so PLAY cannot attach a client that close() is tearing down. */
        private val stateLock = Any()
        private val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
        /** Bounded hand-off to the writer thread. The hub fans frames out on the single MediaCodec
         *  callback thread and a Java socket has NO write timeout, so writing inline would let one
         *  viewer on a stalled link freeze the encoder callback and every other viewer with it. */
        private val queue = ViewerFrameQueue(QUEUE_LIMIT)
        private var attached = false
        @Volatile private var closed = false
        private var writerRunning = false
        /** PTS of the first access unit sent to this client; RTP timestamps are relative to it so
         *  the first PLAY response's `rtptime=0` is literally true. Touched only by the writer
         *  thread. */
        private var basePtsUs = Long.MIN_VALUE
        /** Where the stream actually is, for a replayed PLAY's `RTP-Info`. Published under
         *  [writeLock] once a frame's bytes are out, so a response written between two frames
         *  reports what the client has really seen. The reader thread samples it a hair before it
         *  takes [writeLock], so a burst in that window can leave the advertised value one frame
         *  behind — the harmless direction (the client treats the next packet as marginally future
         *  rather than as a duplicate), and bounded by a frame against the minutes a hardcoded
         *  `seq=0;rtptime=0` would be out by on a live connection. */
        @Volatile private var position = RtpPosition(0, 0L)
        private val reader = Thread(::run, "rtsp-client-${socket.inetAddress.hostAddress}").apply { isDaemon = true }
        private val writer = Thread(::pump, "rtsp-rtp-${socket.inetAddress.hostAddress}").apply { isDaemon = true }

        fun startReader() { reader.start() }

        // -- reader thread ---------------------------------------------------------------------

        private fun run() {
            try {
                // TWICE the advertised session timeout, deliberately. We advertise
                // `Session: <id>;timeout=60` and media3 turns that value into its keepalive
                // INTERVAL — it sends OPTIONS every 60 s. A 60 s read timeout against a 60 s ping
                // period is a coin flip on every keepalive and shows up as intermittent mid-stream
                // disconnects, so the read timeout has to leave room for one late ping.
                socket.soTimeout = sessionTimeoutS * 2 * 1000
                socket.tcpNoDelay = true
                val input = BufferedInputStream(socket.getInputStream(), 8 * 1024)
                while (!closed) {
                    val first = input.read()
                    if (first < 0) break
                    // A client's own interleaved data (RTCP receiver reports on the odd channel):
                    // 4-byte header, then that many bytes to discard. A keepalive arriving right
                    // behind one must still be answered, so this only skips and loops.
                    if (first == '$'.code) { if (!skipInterleaved(input)) break; continue }
                    // Tolerate a stray CRLF between requests rather than answering it with a 400.
                    if (first == '\r'.code || first == '\n'.code) continue
                    val head = readHead(input, first.toChar()) ?: break
                    val req = protocol.parse(head)
                    if (req == null) {
                        write("RTSP/1.0 400 Bad Request\r\nCSeq: 0\r\n\r\n".toByteArray(Charsets.US_ASCII))
                        break
                    }
                    val bodyLength = req.headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
                    if (bodyLength > 0 && !skipFully(input, bodyLength)) break
                    // Both describe() (camera open, up to ~4 s plus a 3 s config wait) and attach()
                    // below can block for seconds. That is fine HERE, on a per-connection socket
                    // thread, and only here — never on the main thread, and never under a lock.
                    val outcome = protocol.handle(req, hub, password()) { position.let { p -> p.seq to p.rtpTimestamp } }
                    write(outcome.response.encode())
                    when (outcome.effect) {
                        SessionEffect.ATTACH -> attachToHub()
                        SessionEffect.DETACH -> detachFromHub()
                        SessionEffect.NONE -> Unit
                    }
                    if (outcome.closeAfter) break
                }
            } catch (_: SocketTimeoutException) {
                log("rtsp client timed out")
            } catch (_: IOException) {
                // peer went away
            } catch (t: Throwable) {
                log("rtsp client failed: $t")
            } finally {
                close()
            }
        }

        /** Starts the writer, then joins the fan-out. [hub] can block for seconds opening the
         *  camera, so it is called with NO client lock held; a close() that lands in that window
         *  is undone by the re-check afterwards. */
        private fun attachToHub() {
            val go = synchronized(stateLock) {
                if (closed || attached) false
                else {
                    attached = true
                    if (!writerRunning) { writerRunning = true; writer.start() }
                    true
                }
            }
            if (!go) return
            hub.attach(this)
            // close() may have run while we were inside attach(), detaching a sink the hub had not
            // yet been given. Detach is idempotent, so undoing it here is always safe.
            if (closed) hub.detach(this)
        }

        private fun detachFromHub() {
            val go = synchronized(stateLock) { if (attached) { attached = false; true } else false }
            if (go) hub.detach(this)
        }

        /** Consumes the 3 bytes after `'$'` and discards the framed payload. False on EOF. */
        private fun skipInterleaved(input: InputStream): Boolean {
            if (input.read() < 0) return false                       // channel
            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) return false
            return skipFully(input, (hi shl 8) or lo)
        }

        private fun skipFully(input: InputStream, count: Int): Boolean {
            var left = count
            while (left > 0) {
                val n = input.skip(left.toLong()).toInt()
                if (n > 0) { left -= n; continue }
                if (input.read() < 0) return false
                left--
            }
            return true
        }

        /** Reads up to and including the blank line, [first] being the byte already consumed;
         *  null on EOF or on a head too large to be a real request. */
        private fun readHead(input: InputStream, first: Char): String? {
            val sb = StringBuilder().append(first)
            while (true) {
                if (sb.length > MAX_HEAD) return null
                val c = input.read()
                if (c < 0) return null
                sb.append(c.toChar())
                if (sb.length >= 4 && sb.endsWith("\r\n\r\n")) return sb.toString()
            }
        }

        private fun write(bytes: ByteArray) { synchronized(writeLock) { out.write(bytes); out.flush() } }

        // -- fan-out (codec callback thread) ----------------------------------------------------

        /**
         * MUST NOT BLOCK: this runs on the single MediaCodec callback thread shared by every
         * viewer. It only enqueues; [pump] does the writing, and [ViewerFrameQueue] decides what an
         * overflow costs. In particular it must NOT ask the pipeline for a keyframe — that is a
         * `MediaCodec.setParameters` call under the hub's camera mutex, which can be held for
         * seconds.
         */
        override fun onAccessUnit(au: AccessUnit) {
            if (closed) return
            try {
                val wasAwaiting = queue.awaitingKeyframe
                if (!queue.offer(au) && !wasAwaiting && queue.awaitingKeyframe) {
                    // Once per stall, not once per dropped frame.
                    log("rtsp viewer behind: queue flushed, resuming at the next keyframe")
                }
            } catch (t: Throwable) {
                // Anything escaping here lands uncaught on the MediaCodec callback thread and
                // takes the process with it.
                log("rtsp fan-out failed: $t")
                close()
            }
        }

        // -- writer thread ----------------------------------------------------------------------

        private fun pump() {
            try {
                while (!closed) {
                    val au = queue.poll(POLL_MS) ?: continue
                    writeAccessUnit(au)
                }
            } catch (_: InterruptedException) {
                // close() woke us
            } catch (_: IOException) {
                // the viewer went away mid-frame
            } catch (t: Throwable) {
                log("rtsp writer failed: $t")
            } finally {
                close()
            }
        }

        private fun writeAccessUnit(au: AccessUnit) {
            // A PTS that goes BACKWARDS (the pipeline restarting its clock under us) would make
            // au.ptsUs - basePtsUs hugely negative and wrap the 32-bit RTP timestamp; rebase on it
            // instead, which costs the client one timestamp discontinuity rather than a garbage one.
            if (basePtsUs == Long.MIN_VALUE || au.ptsUs < basePtsUs) basePtsUs = au.ptsUs
            // Rebased against this client's own first access unit, which is what makes the first
            // PLAY response's `rtptime=0` (and the packetizer's seq starting at 0) literally true.
            val ts = H264RtpPacketizer.rtpTimestamp(au.ptsUs - basePtsUs)
            val packets = packetizer.packetize(au.nals, ts)
            // The channel the client asked for in SETUP, NOT a hardcoded 0: media3 never reads our
            // response's Transport header, it demuxes on the pair it requested, so a fixed 0 is a
            // silent black screen for any client that asks for anything else.
            val channel = protocol.rtpChannel
            synchronized(writeLock) {
                for (p in packets) {
                    out.write('$'.code); out.write(channel); out.write(p.size shr 8); out.write(p.size and 0xFF); out.write(p)
                }
                out.flush()
                position = RtpPosition(packetizer.nextSeq, ts)
            }
        }

        // -- teardown -----------------------------------------------------------------------------

        /** Idempotent, callable from any thread, and takes no lock the hub holds — so [stop] can
         *  never deadlock against a client mid-write or blocked inside `hub.attach`. */
        fun close() {
            val wasAttached: Boolean
            synchronized(stateLock) {
                if (closed) return
                closed = true
                wasAttached = attached
                attached = false
            }
            // Leave the client set BEFORE the hub, so a viewerCount that has reached 0 is never
            // observed alongside a clientCount that has not.
            clients.remove(this)
            runCatching { socket.close() }
            queue.clear()
            if (Thread.currentThread() !== writer) writer.interrupt()
            if (wasAttached) hub.detach(this)
        }
    }

    private companion object {
        const val BACKLOG = 8

        /**
         * Hard cap on concurrent connections. Every accepted socket costs a 64 KB output buffer,
         * an 8 KB input buffer and two threads, held for up to the 120 s read timeout and
         * allocated BEFORE the password is checked — so uncapped, any device on the LAN can walk
         * this process into `pthread_create failed` and take music, the slideshow and Home
         * Assistant down along with the share. [BACKLOG] bounds only the kernel's accept queue.
         *
         * 8 is generous for the job: at 1.5 Mbps per viewer the uplink gives out long before the
         * slots do, and a viewer that vanishes without a FIN only holds one until the read timeout.
         */
        const val MAX_CLIENTS = 8
        const val MAX_HEAD = 16 * 1024
        /** ~4 s of 15 fps video: enough to ride out a Wi-Fi hiccup, small enough to bound memory. */
        const val QUEUE_LIMIT = 60
        const val POLL_MS = 200L
    }
}
