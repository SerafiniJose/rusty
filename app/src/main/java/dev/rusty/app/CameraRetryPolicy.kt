package dev.rusty.app

/** How a stream failure should be handled: retry it, or give up because it will never recover.
 *  [FATAL_NO_CODEC_PARAMS]: the SDP's H.264 track has no `a=fmtp` / `sprop-parameter-sets`, which
 *  media3 refuses outright — repairable by the SDP proxy, never by retrying. */
enum class StreamErrorKind { TRANSIENT, FATAL_AUTH, FATAL_NOT_FOUND, FATAL_UNSUPPORTED, FATAL_NO_CODEC_PARAMS }

/**
 * Classifies media3 `PlaybackException` failures for RTSP camera streams, and derives the
 * reconnect backoff.
 *
 * Device evidence (Task 1 de-risk run) showed that on real hardware every RTSP failure surfaces
 * as `errorCode == 2000` (`ERROR_CODE_IO_UNSPECIFIED`) with the RTSP status text folded into the
 * exception's flattened message — the more specific 2001..2008 codes essentially never appear.
 * [classify] therefore reads the RTSP status out of the message for the whole 2000..2008 io
 * range, not just 2004.
 */
object CameraRetryPolicy {

    /**
     * Classifies a media3 `PlaybackException` (by [errorCode] and its flattened [message]) into a
     * [StreamErrorKind].
     *
     * - regardless of [errorCode]: a message containing media3's "missing attribute fmtp" or
     *   "missing sprop parameter" phrase -> [StreamErrorKind.FATAL_NO_CODEC_PARAMS], checked before
     *   any code range below.
     * - `2000..2008` (io, including the unspecified 2000 code seen on-device): the message is
     *   matched case-insensitively for RTSP status markers — "401"/"unauthorized" or "403" ->
     *   [StreamErrorKind.FATAL_AUTH]; "404"/"454"/"not found" -> [StreamErrorKind.FATAL_NOT_FOUND];
     *   "461"/"unsupported transport" -> [StreamErrorKind.FATAL_UNSUPPORTED]; no marker (including
     *   a null message) -> [StreamErrorKind.TRANSIENT].
     * - `3001..3004` (parsing, e.g. malformed SDP) -> [StreamErrorKind.FATAL_UNSUPPORTED],
     *   regardless of message.
     * - `4001..4005` (decoder init/decoding) -> [StreamErrorKind.FATAL_UNSUPPORTED].
     * - anything else (unknown codes) -> [StreamErrorKind.TRANSIENT].
     */
    fun classify(errorCode: Int, message: String?): StreamErrorKind {
        // Checked before the code ranges: on-device this arrives as code 2000 (io) but it is a
        // parse failure in disguise, and no code range tells the two apart.
        if (message != null && isMissingCodecParams(message.lowercase())) return StreamErrorKind.FATAL_NO_CODEC_PARAMS
        return when (errorCode) {
            in 2000..2008 -> classifyIoMessage(message)
            in 3001..3004 -> StreamErrorKind.FATAL_UNSUPPORTED
            in 4001..4005 -> StreamErrorKind.FATAL_UNSUPPORTED
            else -> StreamErrorKind.TRANSIENT
        }
    }

    /** media3's exact phrases from `RtspMediaTrack.generatePayloadFormat` / `processH264FmtpAttribute`. */
    private fun isMissingCodecParams(lower: String): Boolean =
        lower.contains("missing attribute fmtp") || lower.contains("missing sprop parameter")

    private fun classifyIoMessage(message: String?): StreamErrorKind {
        val text = message?.lowercase() ?: return StreamErrorKind.TRANSIENT
        return when {
            hasStatusCode(text, "401") || text.contains("unauthorized") || hasStatusCode(text, "403") ->
                StreamErrorKind.FATAL_AUTH
            hasStatusCode(text, "404") || hasStatusCode(text, "454") || text.contains("not found") ->
                StreamErrorKind.FATAL_NOT_FOUND
            hasStatusCode(text, "461") || text.contains("unsupported transport") ->
                StreamErrorKind.FATAL_UNSUPPORTED
            else -> StreamErrorKind.TRANSIENT
        }
    }

    /**
     * Whether [text] contains [code] as a standalone number — not adjacent to another digit — so
     * an RTSP status like "401" matches while a port, timestamp or millisecond count that merely
     * embeds those digits (":8401/", "1401ms", "port 4540") does not.
     */
    private fun hasStatusCode(text: String, code: String): Boolean {
        var from = 0
        while (true) {
            val idx = text.indexOf(code, from)
            if (idx < 0) return false
            val before = idx - 1
            val after = idx + code.length
            val boundedBefore = before < 0 || !text[before].isDigit()
            val boundedAfter = after >= text.length || !text[after].isDigit()
            if (boundedBefore && boundedAfter) return true
            from = idx + 1
        }
    }

    /** Reconnect backoff for a [TRANSIENT][StreamErrorKind.TRANSIENT] failure: 2s, 4s, 8s, then 15s. */
    fun nextDelayMs(attempt: Int): Long = when {
        attempt <= 0 -> 2_000L
        attempt == 1 -> 4_000L
        attempt == 2 -> 8_000L
        else -> 15_000L
    }

    /** Whether a stream that stayed healthy for [healthyPlaybackMs] should reset its retry-attempt count. */
    fun shouldResetAttempts(healthyPlaybackMs: Long): Boolean = healthyPlaybackMs >= 30_000L
}

/** Builds and redacts RTSP playback URIs, JVM-pure (no `android.net.Uri` / `java.net.URL`). */
object CameraUri {

    /** RFC 3986 unreserved characters: never percent-encoded. */
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

    /**
     * Returns [rtspUrl] with its userinfo (`user:pass@`) set to the percent-encoded [username]
     * and [password], replacing any userinfo already present.
     *
     * A null or blank [username] leaves [rtspUrl] unchanged (a password without a username is
     * ignored, since RTSP userinfo requires a username).
     */
    fun withCredentials(rtspUrl: String, username: String?, password: String?): String {
        if (username.isNullOrBlank()) return rtspUrl

        val schemeSep = rtspUrl.indexOf("://")
        if (schemeSep < 0) return rtspUrl
        val scheme = rtspUrl.substring(0, schemeSep + 3)
        val rest = rtspUrl.substring(schemeSep + 3)

        val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val remainder = rest.substring(authorityEnd)

        val hostPart = authority.substringAfterLast('@')

        val userinfo = if (password.isNullOrEmpty()) {
            percentEncode(username)
        } else {
            "${percentEncode(username)}:${percentEncode(password)}"
        }

        return "$scheme$userinfo@$hostPart$remainder"
    }

    /**
     * Hides the userinfo in [url] behind a bullet marker: `scheme://user:pass@host/...` becomes
     * `scheme://•••@host/...`. A [url] without userinfo is returned unchanged.
     */
    fun redact(url: String): String {
        val schemeSep = url.indexOf("://")
        if (schemeSep < 0) return url
        val scheme = url.substring(0, schemeSep + 3)
        val rest = url.substring(schemeSep + 3)

        val authorityEnd = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val remainder = rest.substring(authorityEnd)

        val atIndex = authority.lastIndexOf('@')
        if (atIndex < 0) return url
        val hostPart = authority.substring(atIndex + 1)
        return "$scheme•••@$hostPart$remainder"
    }

    /** Percent-encodes every byte outside [UNRESERVED], UTF-8. */
    private fun percentEncode(value: String): String {
        val sb = StringBuilder()
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val unsigned = byte.toInt() and 0xFF
            if (unsigned < 128 && UNRESERVED.indexOf(unsigned.toChar()) >= 0) {
                sb.append(unsigned.toChar())
            } else {
                sb.append('%')
                sb.append(String.format("%02X", unsigned))
            }
        }
        return sb.toString()
    }
}
