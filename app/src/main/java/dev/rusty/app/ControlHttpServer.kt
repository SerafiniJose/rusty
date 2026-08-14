package dev.rusty.app

import dev.rusty.app.renderer.RendererHttpProtocol
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thin Android glue around [ControlProtocol]: an accept loop binding the remote-control HTTP
 * port. All parsing/routing logic lives in the pure, unit-tested protocol object and its shared
 * parser [RendererHttpProtocol.parseRequest] (see the DLNA renderer's proven twin,
 * [dev.rusty.app.renderer.RendererHttpServer], which this transcribes: same accept loop, 4-thread
 * pool, 10 s socket timeout, daemon accept thread) — this class differs from that twin only in
 * that it binds exactly the port it's given (no GENA eventing, no port-picker ladder): on
 * [IOException] the bind failure propagates to the caller, which is how the owning service (Task
 * 9) turns it into a `FAILED` status shown in settings.
 *
 * [localHostsProvider] is invoked once PER REQUEST, not captured at [start] time, so the Host
 * guard in [ControlProtocol.route] always sees the device's current addresses even if they
 * change (e.g. DHCP) while the server is running.
 */
class ControlHttpServer(
    private val runtime: ControlRuntime,
    private val localHostsProvider: () -> Set<String>,
) {

    companion object {
        private const val CONNECTION_SO_TIMEOUT_MS = 10_000

        /** See [connectionPool]. Headroom for the three Immich proxy routes plus the control
         *  page's own polling, while always leaving workers free for `/api/state`. */
        private const val POOL_SIZE = 8
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    /**
     * Bounded so a burst of connections (or a stuck peer under the soTimeout) can't spin up an
     * unbounded number of handler threads; owned by this server, torn down in [stop].
     *
     * Deliberately [POOL_SIZE] rather than the twin's 4. `RendererHttpServer`'s handlers are all
     * fast in-memory SOAP replies, so 4 is plenty there. Here, three of the routes
     * (`/api/immich/{albums,people,tags}`) proxy an upstream server, and ONE endpoint —
     * `/api/state` — is what Home Assistant polls to decide whether this device is available at
     * all. A single control-page load fires all three list fetches in parallel, so with a pool of
     * 4 a second open tab could leave `/api/state` with no worker.
     * [ControlImmichProxy] caps how long any one of those can hold a worker (see its class doc);
     * this caps how many they can hold at once. Both halves are needed: the budget alone would
     * still let three simultaneous crawls own 3/4 of a 4-thread pool for the whole budget.
     */
    private val connectionPool: ExecutorService = Executors.newFixedThreadPool(POOL_SIZE)

    /**
     * Binds [port] and starts the accept loop, returning the port actually bound (so `start(0)`
     * — an OS-assigned ephemeral port — is usable by tests). Throws [IOException] on bind
     * failure rather than swallowing it; the caller decides what a failed bind means.
     */
    fun start(port: Int): Int {
        val socket = ServerSocket(port)
        serverSocket = socket

        running = true
        val thread = Thread({ acceptLoop(socket) }, "control-http")
        thread.isDaemon = true
        acceptThread = thread
        thread.start()
        return socket.localPort
    }

    /** Idempotent and never throws: closing an already-closed/null socket is a no-op. */
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
                val req = RendererHttpProtocol.parseRequest(s.getInputStream())
                val output = s.getOutputStream()
                if (req == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".toByteArray(Charsets.UTF_8))
                    output.flush()
                    return
                }
                val response = ControlProtocol.route(req, runtime, localHostsProvider())
                output.write(response.render().toByteArray(Charsets.UTF_8))
                output.flush()
            }
        } catch (_: IOException) {
            // one bad client (or a soTimeout) must not kill the accept loop
        } catch (_: Exception) {
            // defensive: never let a per-connection failure propagate (ControlProtocol.route
            // already turns runtime throwables into a 500; this is a last-resort backstop)
        }
    }
}
