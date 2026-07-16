package dev.rusty.app.renderer

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thin Android glue around [RendererHttpProtocol]: an accept loop binding a sticky port
 * (see [RendererPortPicker]; advertised via SSDP/description separately) plus a raw-socket
 * GENA NOTIFY sender. All parsing/routing logic lives in the pure, unit-tested protocol
 * object; this class is exercised on-device only.
 */
class RendererHttpServer(private val runtime: RendererRuntime) {

    companion object {
        private const val CONNECTION_SO_TIMEOUT_MS = 10_000
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    /** Bounded so a burst of connections (or a stuck peer under the soTimeout) can't spin up
     *  an unbounded number of handler threads; owned by this server, torn down in [stop]. */
    private val connectionPool: ExecutorService = Executors.newFixedThreadPool(4)

    /**
     * Binds the HTTP port and starts the accept loop, returning the port actually bound.
     *
     * No TOCTOU: the bind attempt IS the final bind — the winning ServerSocket is kept open
     * and becomes the server socket; ports are never probed, closed and re-bound.
     *
     * @param preferredPort the port persisted from the previous run (see [RendererPortPicker]),
     *        or null on first start. The caller MUST persist the returned port so the
     *        LOCATION URL stays stable across restarts.
     */
    fun start(preferredPort: Int?): Int {
        var bound: ServerSocket? = null
        RendererPortPicker.choose(preferredPort) { port ->
            try {
                ServerSocket(port).let { bound = it; it.localPort }
            } catch (_: IOException) {
                null
            }
        }
        val socket = bound ?: throw IOException("could not bind any renderer HTTP port")
        serverSocket = socket

        running = true
        val thread = Thread({ acceptLoop(socket) }, "renderer-http")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
        return socket.localPort
    }

    fun stop() {
        running = false
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        serverSocket = null
        acceptThread = null
        connectionPool.shutdownNow()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val conn = try {
                socket.accept()
            } catch (_: IOException) {
                if (!running) return
                continue
            }
            try {
                conn.soTimeout = CONNECTION_SO_TIMEOUT_MS
            } catch (_: IOException) {
            }
            try {
                connectionPool.execute { handleConnection(conn) }
            } catch (_: Exception) {
                // pool already shut down (racing stop()); don't leak the socket
                try {
                    conn.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handleConnection(conn: Socket) {
        try {
            conn.use { s ->
                val subscriberIp = s.inetAddress?.hostAddress ?: ""
                val req = RendererHttpProtocol.parseRequest(s.getInputStream())
                val output = s.getOutputStream()
                if (req == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                    return
                }
                val response = RendererHttpProtocol.route(req, runtime, subscriberIp)
                output.write(response.render().toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: IOException) {
            // one bad client must not kill the accept loop
        } catch (_: Exception) {
            // defensive: never let a per-connection failure propagate
        }
    }

    /** Sends a GENA NOTIFY for [sub] over a fresh raw socket; returns true iff the peer replied 200. */
    fun sendNotify(sub: GenaSubscriptions.Sub, body: String, seq: Long): Boolean {
        return try {
            val uri = URI(sub.callbackUrl)
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else 80
            val path = uri.rawPath.ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
            val bodyBytes = body.toByteArray(Charsets.UTF_8)

            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 5_000)
                socket.soTimeout = 5_000

                val request = buildString {
                    append("NOTIFY ").append(path).append(" HTTP/1.1\r\n")
                    append("HOST: ").append(host).append(':').append(port).append("\r\n")
                    append("CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n")
                    append("NT: upnp:event\r\n")
                    append("NTS: upnp:propchange\r\n")
                    append("SID: ").append(sub.sid).append("\r\n")
                    append("SEQ: ").append(seq).append("\r\n")
                    append("CONTENT-LENGTH: ").append(bodyBytes.size).append("\r\n")
                    append("\r\n")
                }

                val output = socket.getOutputStream()
                output.write(request.toByteArray(Charsets.UTF_8))
                output.write(bodyBytes)
                output.flush()

                val statusLine = RendererHttpProtocol.readLine(socket.getInputStream())
                statusLine != null && statusLine.startsWith("HTTP/1.1 200")
            }
        } catch (_: Exception) {
            false
        }
    }
}
