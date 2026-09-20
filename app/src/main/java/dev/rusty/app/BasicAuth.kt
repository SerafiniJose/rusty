package dev.rusty.app

import java.util.Base64

/**
 * The one HTTP/RTSP Basic credential this device answers to, and the one decoder for it.
 *
 * Two gates accept `Basic base64("<USER>:<password>")` against the same Remote Control password —
 * [RtspAuth] for the camera-share RTSP stream, and [ControlAuth.authorizedAllowingBasic] for the
 * single local-snapshot route another Rusty device polls — so one password works both ways. They
 * used to carry a username constant and a decoder each; a change to either copy would have made
 * the two gates disagree silently, with no test able to see it.
 *
 * Pure (`java.util` only — no `android.*`), like both callers.
 */
object BasicAuth {

    /** The fixed username. There are no per-user accounts: the password IS the credential. */
    const val USER = "rusty"

    /**
     * The password out of a `Basic` credential's base64 payload (the part after the scheme), for
     * [USER] only — null when it does not decode, carries no colon, or names another user. The
     * password may be empty; callers decide whether that is acceptable.
     *
     * The MIME decoder, not the strict one: it tolerates the embedded line breaks Android's own
     * `android.util.Base64.DEFAULT` wraps a long credential with at 76 characters (media3 builds
     * its header that way), so a real client is not rejected on a technicality.
     */
    fun password(base64Payload: String): String? {
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(base64Payload.trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        if (decoded.substring(0, colon) != USER) return null
        return decoded.substring(colon + 1)
    }

    /** [password] from a whole `Authorization` header value; null when it is absent or not Basic. */
    fun passwordFromHeader(authorizationHeader: String?): String? {
        val header = authorizationHeader?.trim() ?: return null
        val space = header.indexOf(' ')
        if (space < 0 || !header.substring(0, space).equals("Basic", ignoreCase = true)) return null
        return password(header.substring(space + 1))
    }
}
