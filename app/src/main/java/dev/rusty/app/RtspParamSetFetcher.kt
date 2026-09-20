package dev.rusty.app

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI

/**
 * The smallest RTSP client that can learn a stream's SPS/PPS: DESCRIBE → SETUP (interleaved TCP)
 * → PLAY → read RTP until [H264ParamSniffer] is complete → TEARDOWN. Blocking; call off the main
 * thread. Pure JVM (no `android.*`).
 *
 * [fetch] never throws and never puts a credential, a URL or a raw exception message into a
 * failure reason: a reason is a short classification the caller may log verbatim.
 */
class RtspParamSetFetcher(
    private val deadlineMs: Long = 5_000L,
    private val connectTimeoutMs: Int = 3_000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed class Result {
        data class Found(val sps: ByteArray, val pps: ByteArray, val sdp: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    fun fetch(url: String, user: String?, pass: String?): Result {
        val started = clock()
        fun remaining(): Int = (deadlineMs - (clock() - started)).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()
        val uri = try { URI(url) } catch (e: Exception) { return Result.Failed("bad url") }
        val host = uri.host?.takeIf { it.isNotEmpty() } ?: return Result.Failed("bad url")
        val port = if (uri.port > 0) uri.port else 554
        var cseq = 0
        return try {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                // Every read through this stream re-arms soTimeout with what is left of the
                // deadline, so a trickling peer cannot restart the clock one byte at a time.
                val input = DataInputStream(BufferedInputStream(DeadlineInputStream(socket) { remaining() }))
                val out = socket.getOutputStream()

                /** The camera's WWW-Authenticate once seen; every later method is signed up front
                 *  (Digest depends on method+uri, so it is recomputed per call from this). */
                var challenge: String? = null

                /** Sends [method] (pre-signed when a challenge is known); on 401 retries ONCE with
                 *  the Authorization built from the response's challenge. */
                fun call(method: String, target: String, extra: List<Pair<String, String>>): RtspClientResponse {
                    var retried = false
                    while (true) {
                        cseq += 1
                        val auth = RtspClientMessages.authorization(challenge, method, target, user, pass)
                        val headers = listOf("CSeq" to cseq.toString(), "User-Agent" to "Rusty") + extra + listOfNotNull(auth?.let { "Authorization" to it })
                        out.write(RtspClientMessages.request(method, target, headers)); out.flush()
                        val resp = RtspClientMessages.readResponse(input) ?: throw IOException("connection closed")
                        if (resp.status != 401 || retried || user == null) return resp
                        challenge = resp.headers["WWW-AUTHENTICATE"] ?: return resp
                        retried = true
                    }
                }

                val describe = call("DESCRIBE", url, listOf("Accept" to "application/sdp"))
                if (describe.status != 200) return Result.Failed("DESCRIBE ${describe.status}")
                val sdp = String(describe.body, Charsets.UTF_8)
                // Fail fast, before any SETUP: an H.265-only (or video-less) description cannot be
                // repaired by an H.264 sniffer, and the owner's fallback applies at once instead of
                // burning the whole sniff deadline.
                val control = RtspClientMessages.videoControlUrl(sdp, url, describe.headers["CONTENT-BASE"] ?: describe.headers["CONTENT-LOCATION"])
                    ?: return Result.Failed("no H.264 video track")
                val setup = call("SETUP", control, listOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=0-1"))
                if (setup.status != 200) return Result.Failed("SETUP ${setup.status}")
                // Honour the channel pair the camera actually granted: an `interleaved=2-3` answer
                // would otherwise put the video on a channel we read and throw away.
                val videoChannel = Regex("interleaved=(\\d+)").find(setup.headers["TRANSPORT"] ?: "")
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val session = setup.headers["SESSION"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() }
                    ?: return Result.Failed("SETUP without Session")
                val play = call("PLAY", url, listOf("Session" to session, "Range" to "npt=now-"))
                if (play.status != 200) return Result.Failed("PLAY ${play.status}")

                val sniffer = H264ParamSniffer()
                val frame = ByteArray(65_535)
                try {
                    while (!sniffer.complete) {
                        if (clock() - started >= deadlineMs) break
                        val first = input.read()
                        if (first < 0) break
                        if (first == '$'.code) {
                            val channel = input.read()
                            if (channel < 0) break
                            val len = input.readUnsignedShort()
                            input.readFully(frame, 0, len)
                            if (channel == videoChannel) sniffer.onRtpPacket(frame, 0, len)
                        } else {
                            // A server-initiated RTSP message (GET_PARAMETER keep-alive, ANNOUNCE):
                            // swallow its head and body, then carry on with the frames. The first
                            // byte is already gone; only Content-Length matters for staying in sync.
                            val rest = RtspClientMessages.readHead(input) ?: break
                            val len = rest.firstOrNull { it.startsWith("Content-Length", true) }
                                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                            if (len > 0 && len <= 1 shl 20) input.readFully(ByteArray(len)) else if (len > 0) break
                        }
                    }
                } catch (e: IOException) {
                    // The deadline ran out mid-read, or the camera hung up: whatever the sniffer
                    // has is all there is, and the TEARDOWN below is still worth a try.
                }
                runCatching {
                    cseq += 1
                    val auth = RtspClientMessages.authorization(challenge, "TEARDOWN", url, user, pass)
                    out.write(RtspClientMessages.request("TEARDOWN", url, listOf("CSeq" to cseq.toString(), "Session" to session) + listOfNotNull(auth?.let { "Authorization" to it }))); out.flush()
                }
                val sps = sniffer.sps
                val pps = sniffer.pps
                if (sps != null && pps != null) Result.Found(sps, pps, sdp) else Result.Failed("no SPS/PPS within ${deadlineMs}ms")
            }
        } catch (e: Exception) {
            // Classification only: an exception message can carry the host, the URL or the
            // credentials media3-style callers fold into it.
            Result.Failed(e.javaClass.simpleName)
        }
    }
}
