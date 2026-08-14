package dev.rusty.app

import java.net.URI

/**
 * Pure normalization + origin parsing for a user-entered Home Assistant address. Trims; blank → null;
 * prepends http:// when no scheme is present (LAN HA is usually plain http). Accepts only http/https
 * URLs that carry a host — anything else (javascript:, ftp:, file:, hostless) is rejected. Android-free
 * for unit testing.
 */
object HomeAssistantUrl {
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        val candidate = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return candidate
    }

    /** scheme://host[:port], lowercased, with default http/https ports omitted; null if invalid. */
    fun origin(url: String?): String? {
        val normalized = normalize(url) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val port = uri.port
        val isDefault = port == -1 ||
            (scheme == "http" && port == 80) ||
            (scheme == "https" && port == 443)
        return if (isDefault) "$scheme://$host" else "$scheme://$host:$port"
    }

    fun isSameOrigin(url: String?, origin: String?): Boolean =
        origin != null && origin(url) == origin

    /**
     * The path (+query+fragment) portion of [url], for client-side `history.pushState` navigation.
     * Root / empty path → "/". Returns null if [url] can't be normalized. Uses raw components so the
     * already-encoded URL is fed to pushState verbatim.
     */
    fun pathWithQuery(url: String?): String? {
        val normalized = normalize(url) ?: return null
        val uri = runCatching { URI(normalized) }.getOrNull() ?: return null
        val path = uri.rawPath.orEmpty().ifEmpty { "/" }
        val query = uri.rawQuery?.let { "?$it" } ?: ""
        val fragment = uri.rawFragment?.let { "#$it" } ?: ""
        return path + query + fragment
    }

    /** Assembles [base] + a page-reported [path] into an absolute URL, or null when [path] is not a
     *  safe same-origin path. Rejects anything not starting with a single '/', and re-checks the
     *  assembled URL's origin against [base] so a crafted path (e.g. "@evil.example/", which turns the
     *  host into userinfo) can never redirect the WebView off the trusted origin. Pure. */
    fun childUrlOrNull(base: String?, path: String?): String? {
        val root = normalize(base) ?: return null
        val rootOrigin = origin(root) ?: return null
        val p = path.orEmpty()
        // A single leading '/' only: no leading '/' at all lets a path like "@evil.example/" or
        // "https://evil.example" get glued straight onto the origin below; "//" is protocol-relative
        // (not actually exploitable once it's past a fixed authority, but never a legitimate
        // HA-reported path either, so reject it too).
        if (!p.startsWith("/") || p.startsWith("//")) return null
        val assembled = root.trimEnd('/') + p
        return if (isSameOrigin(assembled, rootOrigin)) assembled else null
    }
}
