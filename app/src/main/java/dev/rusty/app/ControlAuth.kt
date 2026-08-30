package dev.rusty.app

import java.security.MessageDigest

/**
 * The control API's password gate, pure and off-device-testable like the rest of
 * [ControlProtocol]'s decisions (`java.security` only — no `android.*`).
 *
 * Scheme: `Authorization: Bearer <password>` on every `/api/...` request. Bearer rather than HTTP
 * Basic so browsers never pop their native credential dialog over the control page — the page owns
 * its login overlay — and so the header carries the password verbatim (no base64 pair encoding for
 * the Home Assistant integration to get subtly wrong).
 *
 * This protects against casual LAN access, not a hostile network: the transport is plain HTTP
 * (librespot-era stack, no TLS), so the password crosses the wire in the clear. The settings UI
 * says as much.
 */
object ControlAuth {

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
     * Whether a request carrying [authorizationHeader] may proceed when [requiredPassword] is in
     * force (null = the gate is off). Constant-time comparison ([MessageDigest.isEqual]) so a
     * remote caller can't binary-search the password by timing rejections.
     */
    fun authorized(requiredPassword: String?, authorizationHeader: String?): Boolean {
        if (requiredPassword == null) return true
        val presented = bearerToken(authorizationHeader) ?: return false
        return MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            requiredPassword.toByteArray(Charsets.UTF_8),
        )
    }
}
