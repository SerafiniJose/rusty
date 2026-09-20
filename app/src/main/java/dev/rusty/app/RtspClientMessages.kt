package dev.rusty.app

import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.Base64

/**
 * A socket stream that re-arms the read deadline on EVERY read: [remainingMs] is asked for what is
 * left of the caller's budget just before each `recv`, so a peer that dribbles one byte per timeout
 * window — restarting a per-message timeout forever — still runs out of total time.
 *
 * [readHead] and [readResponse] see only a stream, so this is where the whole-operation deadline
 * lives; the RTP frame reads go through the same wrapper.
 */
class DeadlineInputStream(private val socket: Socket, private val remainingMs: () -> Int) : InputStream() {
    private val delegate: InputStream = socket.getInputStream()

    private fun arm() { socket.soTimeout = remainingMs().coerceAtLeast(1) }

    override fun read(): Int { arm(); return delegate.read() }
    override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return delegate.read(b, off, len) }
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

/** One RTSP response as a client sees it. [headers] keys are UPPER-CASED. */
data class RtspClientResponse(val status: Int, val reason: String, val headers: Map<String, String>, val body: ByteArray)

/** Client-side RTSP framing (RFC 2326 subset) for [RtspParamSetFetcher] and the SDP-repair proxy. Pure. */
object RtspClientMessages {
    /** A response body big enough to be a bug or an attack, not an SDP: refuse rather than allocate. */
    private const val MAX_BODY = 4 * 1024 * 1024

    /** Serialises `METHOD uri RTSP/1.0` + headers (CSeq added by the caller) + blank line. */
    fun request(method: String, uri: String, headers: List<Pair<String, String>>): ByteArray {
        val sb = StringBuilder("$method $uri RTSP/1.0\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** Reads CRLF- or LF-terminated header lines up to the blank line. Null on EOF at the start. */
    fun readHead(input: DataInputStream): List<String>? {
        val lines = mutableListOf<String>()
        while (true) {
            val sb = StringBuilder()
            var c = input.read()
            if (c < 0) return if (lines.isEmpty()) null else lines
            while (c >= 0 && c != '\n'.code) { if (c != '\r'.code) sb.append(c.toChar()); c = input.read() }
            if (sb.isEmpty()) break
            lines.add(sb.toString())
        }
        return lines
    }

    /**
     * Reads one response (status line, headers, Content-Length body). Returns null on EOF before a
     * complete status line; throws [IOException] on a malformed one (or an absurd Content-Length,
     * which must not become an OutOfMemoryError the caller cannot catch).
     *
     * A header repeated on several lines keeps its FIRST value: a 401 lists its `WWW-Authenticate`
     * schemes strongest-first, and dropping Digest for a later Basic would put the password on the
     * wire for nothing.
     */
    fun readResponse(input: DataInputStream): RtspClientResponse? {
        val lines = readHead(input) ?: return null
        val statusLine = lines.firstOrNull() ?: throw IOException("empty response")
        val parts = statusLine.split(' ', limit = 3)
        if (parts.size < 2 || !parts[0].startsWith("RTSP/")) throw IOException("bad status line")
        val status = parts[1].toIntOrNull() ?: throw IOException("bad status line")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            headers.putIfAbsent(line.substring(0, colon).trim().uppercase(), line.substring(colon + 1).trim())
        }
        val len = headers["CONTENT-LENGTH"]?.toIntOrNull() ?: 0
        if (len < 0 || len > MAX_BODY) throw IOException("bad content length")
        val body = ByteArray(len).also { if (len > 0) input.readFully(it) }
        return RtspClientResponse(status, parts.getOrElse(2) { "" }, headers, body)
    }

    /**
     * `WWW-Authenticate` → an `Authorization` value for [method]/[uri], Digest preferred, Basic
     * otherwise; null when the challenge is neither or credentials are missing.
     */
    fun authorization(challenge: String?, method: String, uri: String, user: String?, pass: String?): String? {
        if (challenge == null || user == null) return null
        val password = pass ?: ""
        if (challenge.startsWith("Digest", ignoreCase = true)) {
            val params = Regex("(\\w+)=\"([^\"]*)\"").findAll(challenge).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
            val realm = params["realm"] ?: return null
            val nonce = params["nonce"] ?: return null
            val response = RtspAuth.digestResponse(user, realm, password, method, uri, nonce)
            return "Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$uri\", response=\"$response\""
        }
        if (challenge.startsWith("Basic", ignoreCase = true)) {
            return "Basic " + Base64.getEncoder().encodeToString("$user:$password".toByteArray(Charsets.UTF_8))
        }
        return null
    }

    private val H264_RTPMAP = Regex("^a=rtpmap:\\d+\\s+H264/", RegexOption.IGNORE_CASE)

    /**
     * Control URL of the first `m=video` section whose rtpmap is H264 (an H.265 section that
     * happens to come first is skipped), resolved per RFC 2326 §C.1.1 against Content-Base, else
     * Content-Location, else the request URL — with real URI-reference resolution, so `/track1` is
     * host-absolute and `track1` is relative to the base. Null when the SDP has no H.264 video
     * section at all: nothing here can repair that stream.
     */
    fun videoControlUrl(sdp: String, requestUrl: String, contentBase: String?): String? {
        var inVideo = false
        var isH264 = false
        var control: String? = null
        var found = false
        for (line in sdp.split("\r\n", "\n")) {
            if (line.startsWith("m=")) {
                if (inVideo && isH264) { found = true; break }
                inVideo = line.startsWith("m=video"); isH264 = false; control = null
                continue
            }
            if (!inVideo) continue
            if (H264_RTPMAP.containsMatchIn(line)) isH264 = true
            if (control == null && line.startsWith("a=control:")) control = line.removePrefix("a=control:").trim()
        }
        if (!found && !(inVideo && isH264)) return null
        val c = control
        if (c == null || c.isEmpty() || c == "*") return requestUrl
        val base = (contentBase?.takeIf { it.isNotBlank() } ?: requestUrl).let { if (it.endsWith("/")) it else "$it/" }
        return runCatching { java.net.URI(base).resolve(c).toString() }.getOrElse { base + c.trimStart('/') }
    }
}
