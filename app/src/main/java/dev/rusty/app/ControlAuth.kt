package dev.rusty.app

import java.security.MessageDigest
import java.util.Base64

/**
 * The control API's password gate, pure and off-device-testable like the rest of
 * [ControlProtocol]'s decisions (`java.security`/`java.util` only — no `android.*`).
 *
 * Scheme: `Authorization: Bearer <password>` on every `/api/...` request — [authorized] is
 * that gate. Bearer rather than HTTP Basic so browsers never pop their native credential dialog
 * over the control page — the page owns its login overlay — and so the header carries the
 * password verbatim (no base64 pair encoding for the Home Assistant integration to get subtly
 * wrong).
 *
 * `Basic base64("rusty:<password>")` is ALSO accepted, but only by [authorizedAllowingBasic] — which
 * [ControlProtocol.route] must call for EXACTLY ONE route, `GET /api/camera/local/snapshot.jpg`,
 * and never for the general gate (that's [authorized]'s job). That fetcher is another Rusty
 * device polling for a grid thumbnail, not a browser, so the native-dialog concern does not apply
 * — and it is the same credential shape [RtspAuth] already accepts for the RTSP stream itself, so
 * one password works both ways. [basicPassword] is the decoder for it; the fixed username is
 * `rusty`, matching [RtspAuth.USER].
 *
 * Why the split matters: HTTP Basic credentials, once presented to an origin by a real browser (a
 * URL with embedded `rusty:pw@host`, a bookmark, an HA Lovelace picture-entity URL), are cached
 * per-origin and auto-attached to every later same-origin request from ANY tab — including one
 * opened by a hostile page targeting the device's LAN IP directly. Bearer has no such ambient
 * replay: a cross-site page can't forge a custom header without CORS approval this server never
 * grants. Letting [authorizedAllowingBasic] leak into routes beyond the one that needs it would hand that
 * ambient-replay risk to the rest of the API, writes included.
 *
 * This protects against casual LAN access, not a hostile network: the transport is plain HTTP
 * (librespot-era stack, no TLS), so the password crosses the wire in the clear. The settings UI
 * says as much.
 */
object ControlAuth {

    private const val BASIC_USER = "rusty"

    /**
     * The password presented by [authorizationHeader], or null when the header is absent, blank,
     * or not a Bearer credential. Outer whitespace is trimmed; inner spaces are kept — they are
     * part of the password.
     */
    fun bearerToken(authorizationHeader: String?): String? {
        val header = authorizationHeader?.trim() ?: return null
        val space = header.indexOf(' ')
        if (space < 0) return null
        if (!header.substring(0, space).equals("Bearer", ignoreCase = true)) return null
        return header.substring(space + 1).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * The password presented by a `Basic` [authorizationHeader] for the fixed user [BASIC_USER],
     * or null when the header is absent, malformed, not Basic, for any other user, or decodes to
     * an empty password. Never throws: a non-base64 payload is simply rejected.
     *
     * Uses the MIME decoder rather than the strict one — same reason [RtspAuth.authorized] does:
     * it tolerates the embedded line breaks Android's own `android.util.Base64.DEFAULT` wraps a
     * long credential with, so a real client that built its header that way is not rejected on a
     * technicality.
     */
    fun basicPassword(authorizationHeader: String?): String? {
        val header = authorizationHeader?.trim() ?: return null
        val space = header.indexOf(' ')
        if (space < 0 || !header.substring(0, space).equals("Basic", ignoreCase = true)) return null
        val decoded = runCatching {
            String(Base64.getMimeDecoder().decode(header.substring(space + 1).trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val colon = decoded.indexOf(':')
        if (colon < 0) return null
        if (decoded.substring(0, colon) != BASIC_USER) return null
        return decoded.substring(colon + 1).takeIf { it.isNotEmpty() }
    }

    /**
     * Whether a request carrying [authorizationHeader] may proceed when [requiredPassword] is in
     * force (null = the gate is off) — the GENERAL `/api/...` gate: Bearer only, never Basic.
     * [ControlProtocol.route] calls this for every route except the local-snapshot one; see
     * [authorizedAllowingBasic] for the widened check reserved for that single route, and the class doc for why
     * the two must never be swapped. Constant-time comparison ([passwordMatches]) so a remote
     * caller can't binary-search the password by timing rejections.
     */
    fun authorized(requiredPassword: String?, authorizationHeader: String?): Boolean {
        if (requiredPassword == null) return true
        val presented = bearerToken(authorizationHeader) ?: return false
        return passwordMatches(presented, requiredPassword)
    }

    /**
     * Like [authorized], but Basic (for [BASIC_USER]) is ALSO accepted — Bearer is tried
     * first, then Basic. Reserved for EXACTLY ONE route: [ControlProtocol.route] must only reach
     * for this when the path is `/api/camera/local/snapshot.jpg`, never for the general gate.
     * Constant-time comparison ([passwordMatches]) so a remote caller can't binary-search the
     * password by timing rejections, whichever scheme presented it.
     */
    fun authorizedAllowingBasic(requiredPassword: String?, authorizationHeader: String?): Boolean {
        if (requiredPassword == null) return true
        val presented = bearerToken(authorizationHeader) ?: basicPassword(authorizationHeader) ?: return false
        return passwordMatches(presented, requiredPassword)
    }

    /** Constant-time so a remote caller can't binary-search the password by timing rejections. */
    private fun passwordMatches(presented: String, required: String): Boolean = MessageDigest.isEqual(
        presented.toByteArray(Charsets.UTF_8),
        required.toByteArray(Charsets.UTF_8),
    )
}
