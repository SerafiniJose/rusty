package dev.rusty.app

import java.security.MessageDigest

/**
 * RFC 2617 Digest (MD5, no qop — what IP-camera clients expect) and Basic, for the RTSP server.
 * User is fixed to [BasicAuth.USER]; the password is the device's Remote Control password. Pure
 * (`java.security` only — no `android.*`), like [ControlAuth].
 *
 * Digest is offered first so a client never has to put the password on the wire; Basic stays as a
 * fallback for players that don't implement Digest. Neither hides the stream itself — interleaved
 * RTP over plain TCP is in the clear — so this is a LAN gate, not transport security.
 *
 * Nothing here logs: the password, the digest response and the Authorization header must never
 * reach logcat.
 */
object RtspAuth {
    const val REALM = "rusty"

    /** The two `WWW-Authenticate` headers a 401 carries: Digest first, then Basic. */
    fun challengeHeaders(nonce: String): List<Pair<String, String>> = listOf(
        "WWW-Authenticate" to "Digest realm=\"$REALM\", nonce=\"$nonce\"",
        "WWW-Authenticate" to "Basic realm=\"$REALM\"",
    )

    fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun digestResponse(user: String, realm: String, password: String, method: String, uri: String, nonce: String): String {
        val ha1 = md5Hex("$user:$realm:$password")
        val ha2 = md5Hex("$method:$uri")
        return md5Hex("$ha1:$nonce:$ha2")
    }

    /**
     * Whether [header] (a request's `Authorization`) proves knowledge of [password] for this
     * [method] on this [requestUri], against the connection's [nonce].
     *
     * The header's own `uri` parameter must equal [requestUri]: it feeds HA2, so without the check
     * a digest captured for one URI could be replayed against another. Secret comparisons go
     * through [MessageDigest.isEqual] so a caller can't binary-search the password by timing.
     */
    fun authorized(header: String?, method: String, requestUri: String, nonce: String, password: String): Boolean {
        val h = header?.trim() ?: return false
        val space = h.indexOf(' ')
        if (space < 0) return false
        val scheme = h.substring(0, space).lowercase()
        val rest = h.substring(space + 1).trim()
        return when (scheme) {
            "basic" -> {
                val presented = BasicAuth.password(rest) ?: return false
                eq(presented, password)
            }
            "digest" -> {
                val params = parseParams(rest)
                if (params["username"] != BasicAuth.USER) return false
                if (params["realm"] != REALM) return false
                if (params["nonce"] != nonce) return false
                val uri = params["uri"] ?: return false
                if (uri != requestUri) return false
                val response = params["response"] ?: return false
                eq(response.lowercase(), digestResponse(BasicAuth.USER, REALM, password, method, uri, nonce))
            }
            else -> false
        }
    }

    private fun eq(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))

    /** `k="v", k2=v2` → map; tolerant of unquoted values and stray whitespace. */
    private fun parseParams(s: String): Map<String, String> {
        val out = HashMap<String, String>()
        var i = 0
        while (i < s.length) {
            val eqIdx = s.indexOf('=', i)
            if (eqIdx < 0) break
            val key = s.substring(i, eqIdx).trim().trimStart(',').trim().lowercase()
            var j = eqIdx + 1
            while (j < s.length && s[j] == ' ') j++
            val value: String
            if (j < s.length && s[j] == '"') {
                val close = s.indexOf('"', j + 1)
                if (close < 0) break
                value = s.substring(j + 1, close)
                i = close + 1
            } else {
                val comma = s.indexOf(',', j).let { if (it < 0) s.length else it }
                value = s.substring(j, comma).trim()
                i = comma
            }
            out[key] = value
            while (i < s.length && (s[i] == ',' || s[i] == ' ')) i++
        }
        return out
    }
}
