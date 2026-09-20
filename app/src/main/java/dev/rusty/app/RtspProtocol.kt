package dev.rusty.app

data class RtspRequest(val method: String, val uri: String, val cseq: Int, val headers: Map<String, String>)

data class RtspResponse(val status: Int, val reason: String, val headers: List<Pair<String, String>>, val body: String = "") {
    fun encode(): ByteArray {
        val sb = StringBuilder("RTSP/1.0 $status $reason\r\n")
        for ((k, v) in headers) sb.append(k).append(": ").append(v).append("\r\n")
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        if (bodyBytes.isNotEmpty()) sb.append("Content-Length: ").append(bodyBytes.size).append("\r\n")
        sb.append("\r\n")
        return sb.toString().toByteArray(Charsets.US_ASCII) + bodyBytes
    }
}

sealed class DescribeResult {
    /**
     * Parameter sets for the SDP. [sps] and [pps] MUST be bare NAL units with NO Annex-B start
     * code: MediaCodec's `csd-0`/`csd-1` buffers do carry one, so split them with
     * [H264Nal.splitAnnexB] and pass the resulting NAL rather than the raw buffer.
     */
    data class Ready(val sps: ByteArray, val pps: ByteArray) : DescribeResult()
    data class Unavailable(val reason: String) : DescribeResult()
}

interface RtspStreamSource { fun describe(): DescribeResult }

enum class SessionEffect { NONE, ATTACH, DETACH }

data class RtspOutcome(val response: RtspResponse, val effect: SessionEffect, val closeAfter: Boolean = false)

/**
 * One viewer connection's RTSP state (RFC 2326 subset). Pure: the socket layer (RtspServer) feeds
 * it parsed requests and writes back the encoded response, then applies [RtspOutcome.effect].
 * Only interleaved TCP transport is supported — a UDP SETUP gets 461 and media3 retries over TCP.
 *
 * @param sessionIdSource must generate an id matching `[\w$\-_.+]+`: media3 validates the Session
 *   header against exactly that regex with `matches()` and throws a ParserException otherwise, so a
 *   base64 id containing `/` or `=` would kill playback.
 * @param nonceSource must be unpredictable (drawn from a CSPRNG) and must not contain a double
 *   quote, CR or LF. It is the auth gate's entire cross-connection replay defence, and it is
 *   interpolated unescaped into a quoted `WWW-Authenticate` header value: a constant or empty
 *   nonce makes a captured Authorization header replayable on every future connection, and a
 *   double quote in it both corrupts the challenge header and makes the equality check
 *   unsatisfiable, i.e. permanent 401s.
 */
class RtspProtocol(
    private val streamPath: String = CameraShareSettings.STREAM_PATH,
    private val sessionTimeoutS: Int = 60,
    private val sessionIdSource: () -> String,
    private val nonceSource: () -> String,
) {
    @Volatile var sessionId: String? = null; private set
    @Volatile var playing: Boolean = false; private set
    /** RTP channel the client asked for in SETUP's `interleaved=<lo>-<hi>`; 0 until then. */
    @Volatile var rtpChannel: Int = 0; private set
    /** One Digest nonce per connection, minted on the first challenge and stable for its life. */
    private val nonce: String by lazy { nonceSource() }

    companion object {
        const val METHODS = "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN, GET_PARAMETER"
        const val TRACK = "track0"

        /** The payload type this server always offers. */
        const val PAYLOAD_TYPE = 96

        /** The stream's own path is deliberately absent: the session control is `*` and the track
         *  control is relative, so both resolve against the `Content-Base` of the DESCRIBE that
         *  carries this body. A path baked in here could only contradict it. */
        fun sdp(sps: ByteArray, pps: ByteArray): String = buildString {
            append("v=0\r\n")
            append("o=- 0 0 IN IP4 0.0.0.0\r\n")
            append("s=Rusty camera\r\n")
            append("t=0 0\r\n")
            append("a=control:*\r\n")
            append("m=video 0 RTP/AVP $PAYLOAD_TYPE\r\n")
            append("c=IN IP4 0.0.0.0\r\n")
            append("a=rtpmap:$PAYLOAD_TYPE H264/90000\r\n")
            // The same line SdpRepair writes into a camera's SDP that left it out, from the same
            // builder: the stream this server offers and a stream it repairs must describe their
            // parameter sets identically, and one builder is the only way to keep that true. It
            // also carries the Annex-B guard — a caller handing over MediaCodec's csd buffers
            // would otherwise make profile-level-id read 000001 and corrupt sprop-parameter-sets.
            append(SdpRepair.fmtpLine(PAYLOAD_TYPE, sps, pps)).append("\r\n")
            append("a=control:").append(TRACK).append("\r\n")
        }
    }

    fun parse(head: String): RtspRequest? {
        val lines = head.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(' ')
        if (parts.size != 3 || !parts[2].startsWith("RTSP/")) return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':'); if (colon <= 0) continue
            headers[line.substring(0, colon).trim().uppercase()] = line.substring(colon + 1).trim()
        }
        val cseq = headers["CSEQ"]?.toIntOrNull() ?: return null
        return RtspRequest(parts[0], parts[1], cseq, headers)
    }

    /**
     * @param rtpPosition where the stream already is — the sequence number the next RTP packet
     *   will carry and its RTP timestamp — for the `RTP-Info` of a PLAY. It is `0 to 0` for the
     *   first PLAY (nothing has been sent, and RtspServer rebases timestamps to this client's first
     *   access unit), which is why the default is truthful for a fresh connection. A REPLAYED PLAY
     *   is a live path — VLC does PAUSE→405→PLAY, a media3 seek does PAUSE+PLAY — and there the
     *   stream carries on from where it was, so advertising a hardcoded zero would tell a client
     *   that resyncs on `RTP-Info` to treat every packet it then receives as far-future.
     */
    fun handle(
        req: RtspRequest,
        source: RtspStreamSource,
        password: String?,
        rtpPosition: () -> Pair<Int, Long> = { 0 to 0L },
    ): RtspOutcome {
        val base = listOf("CSeq" to req.cseq.toString(), "Server" to "Rusty")
        fun resp(status: Int, reason: String, extra: List<Pair<String, String>> = emptyList(), body: String = "") =
            RtspResponse(status, reason, base + extra, body)
        fun err(status: Int, reason: String) = RtspOutcome(resp(status, reason), SessionEffect.NONE)

        // OPTIONS stays open: clients probe it before they hold credentials. Everything else needs
        // the password, and the 401 must carry the challenge or the client never learns how to ask.
        if (password != null && req.method != "OPTIONS" &&
            !RtspAuth.authorized(req.headers["AUTHORIZATION"], req.method, req.uri, nonce, password)
        ) {
            return RtspOutcome(resp(401, "Unauthorized", RtspAuth.challengeHeaders(nonce)), SessionEffect.NONE)
        }

        if (req.method != "OPTIONS" && !pathMatches(req.uri)) return err(404, "Not Found")

        return when (req.method) {
            "OPTIONS" -> RtspOutcome(resp(200, "OK", listOf("Public" to METHODS)), SessionEffect.NONE)
            "DESCRIBE" -> when (val d = source.describe()) {
                is DescribeResult.Unavailable -> err(503, "Service Unavailable")
                is DescribeResult.Ready -> RtspOutcome(
                    resp(200, "OK", listOf("Content-Type" to "application/sdp", "Content-Base" to contentBase(req.uri)), sdp(d.sps, d.pps)),
                    SessionEffect.NONE,
                )
            }
            "SETUP" -> {
                val transport = req.headers["TRANSPORT"] ?: return err(400, "Bad Request")
                val parts = transport.split(';').map { it.trim() }
                val interleaved = parts.firstOrNull { it.startsWith("interleaved=") }
                if (!parts.contains("RTP/AVP/TCP") || interleaved == null) {
                    return err(461, "Unsupported Transport")
                }
                // RFC 2326 §12.39 wants the transport actually chosen, and media3 never reads this
                // header — it demuxes on the channel it asked for — so echo the client's pair back.
                val (rtp, rtcp) = parseInterleaved(interleaved)
                // An interleaved channel is ONE byte on the wire (RFC 2326 §10.12). Honouring
                // `interleaved=999-1000` would put the frames on channel 231 (999 truncated) while
                // this header still said 999, so the client would demux nothing and show black.
                // Refusing is the only answer that cannot end in a silent black screen.
                if (rtp !in 0..255 || rtcp !in 0..255) return err(461, "Unsupported Transport")
                rtpChannel = rtp
                val id = sessionId ?: sessionIdSource().also { sessionId = it }
                RtspOutcome(resp(200, "OK", listOf("Session" to "$id;timeout=$sessionTimeoutS", "Transport" to "RTP/AVP/TCP;unicast;interleaved=$rtp-$rtcp")), SessionEffect.NONE)
            }
            "PLAY" -> {
                val check = checkSession(req) ?: return err(455, "Method Not Valid in This State")
                if (!check) return err(454, "Session Not Found")
                // VLC (PAUSE→405, then PLAY) and a media3 seek (PAUSE+PLAY) replay PLAY on a live
                // connection: attach once, or every frame would go out twice with duplicate seq.
                val effect = if (playing) SessionEffect.NONE else SessionEffect.ATTACH
                playing = true
                // The REAL position, not a placeholder. On the first PLAY it is 0;0 — the client's
                // packetizer starts at seq 0 and RtspServer rebases RTP timestamps to the first
                // access unit sent to that client — and on a replayed one it is wherever the stream
                // has got to, which is the only value a client resyncing on RTP-Info can use.
                val (seq, rtptime) = rtpPosition()
                RtspOutcome(resp(200, "OK", listOf("Session" to sessionId!!, "RTP-Info" to "url=${contentBase(req.uri)}$TRACK;seq=$seq;rtptime=$rtptime")), effect)
            }
            "GET_PARAMETER" -> {
                val check = checkSession(req)
                if (check == false) return err(454, "Session Not Found")
                RtspOutcome(resp(200, "OK", listOfNotNull(sessionId?.let { "Session" to it })), SessionEffect.NONE)
            }
            "TEARDOWN" -> {
                val wasPlaying = playing
                playing = false; sessionId = null
                RtspOutcome(resp(200, "OK"), if (wasPlaying) SessionEffect.DETACH else SessionEffect.NONE, closeAfter = true)
            }
            else -> err(405, "Method Not Allowed")
        }
    }

    /** `interleaved=<lo>-<hi>` → the channel pair; a malformed token falls back to 0-1. */
    private fun parseInterleaved(token: String): Pair<Int, Int> {
        val value = token.substringAfter('=').trim()
        val rtp = value.substringBefore('-', "").toIntOrNull() ?: return 0 to 1
        val rtcp = value.substringAfter('-', "").toIntOrNull() ?: return 0 to 1
        return rtp to rtcp
    }

    /** null = no session exists; false = header names a different session; true = matches. */
    private fun checkSession(req: RtspRequest): Boolean? {
        val id = sessionId ?: return null
        val presented = req.headers["SESSION"]?.substringBefore(';')?.trim() ?: return false
        return presented == id
    }

    private fun pathMatches(uri: String): Boolean {
        val path = uri.substringAfter("://", uri).let { rest -> rest.substring(rest.indexOf('/').coerceAtLeast(0)) }
            .substringBefore('?').trimEnd('/')
        return path == streamPath || path == "$streamPath/$TRACK"
    }

    private fun contentBase(uri: String): String {
        val noQuery = uri.substringBefore('?').trimEnd('/')
        val base = if (noQuery.endsWith("/$TRACK")) noQuery.removeSuffix("/$TRACK") else noQuery
        return "$base/"
    }
}
