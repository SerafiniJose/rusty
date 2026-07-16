package dev.rusty.app.renderer

import java.net.URI

/**
 * The renderer's playback policy: accept any http-get audio and let ExoPlayer (media3) decode it,
 * rejecting only what media3 core provably cannot play from a plain progressive source — adaptive
 * manifests (HLS/DASH), playlists (.m3u/.pls/.xspf), and non-audio (video/image).
 *
 * NB the original design used a strict 7-type allowlist; that faulted common internet-radio codecs
 * ExoPlayer actually decodes (audio/aacp HE-AAC, application/ogg, aliases) with 714 "Unsupported
 * MIME type" → "no sound" for real casters (e.g. HA Radio Browser). Validation is now permissive;
 * [SINK_MIME_TYPES] remains the set we advertise on ConnectionManager AND the only types we pass to
 * ExoPlayer as a format hint — every other accepted type is handed over with no hint so ExoPlayer
 * sniffs the real container.
 */
object UpnpFormats {
    val SINK_MIME_TYPES: List<String> = listOf(
        "audio/mpeg", "audio/mp4", "audio/aac", "audio/ogg",
        "audio/flac", "audio/wav", "audio/x-wav",
    )

    private val EXTENSION_MIME = mapOf(
        "mp3" to "audio/mpeg", "m4a" to "audio/mp4", "mp4" to "audio/mp4",
        "aac" to "audio/aac", "ogg" to "audio/ogg", "oga" to "audio/ogg",
        "flac" to "audio/flac", "wav" to "audio/wav",
    )

    /** Content types media3 core cannot play from a progressive http-get source (adaptive manifests
     *  + playlists). Combined with a video/ or image/ prefix check to reject non-audio. */
    private val UNSUPPORTED_MIME = setOf(
        "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl", "audio/x-mpegurl",
        "application/dash+xml", "audio/x-scpls", "application/pls+xml", "application/xspf+xml",
    )

    /** Extensions to reject when no MIME hint is given (manifests/playlists + obvious video/image). */
    private val UNSUPPORTED_EXT = setOf(
        "m3u", "m3u8", "pls", "mpd", "xspf", "avi", "mkv", "webm", "mov", "jpg", "jpeg", "png", "gif",
    )

    sealed class Validation {
        data class Ok(val uri: String, val mime: String?) : Validation()
        object BadUri : Validation()
        object UnsupportedMime : Validation()
    }

    fun sinkProtocolInfo(): String =
        SINK_MIME_TYPES.joinToString(",") { "http-get:*:$it:*" }

    fun validateUri(uri: String?, mimeHint: String?): Validation {
        if (uri.isNullOrBlank()) return Validation.BadUri
        val parsed = runCatching { URI(uri) }.getOrNull() ?: return Validation.BadUri
        if (parsed.scheme != "http" && parsed.scheme != "https") return Validation.BadUri
        if (parsed.host.isNullOrBlank()) return Validation.BadUri
        val hinted = mimeHint?.substringBefore(';')?.trim()?.lowercase()
        if (!hinted.isNullOrEmpty()) {
            if (hinted in UNSUPPORTED_MIME || hinted.startsWith("video/") || hinted.startsWith("image/"))
                return Validation.UnsupportedMime
            // Accept anything else (audio/*, application/ogg, application/octet-stream, unknown).
            // Pass a format hint ONLY for the types ExoPlayer maps reliably; otherwise let it sniff
            // (an alias like audio/aacp fed to setMimeType can defeat ExoPlayer's own detection).
            return Validation.Ok(uri, hinted.takeIf { it in SINK_MIME_TYPES })
        }
        val ext = parsed.path?.substringAfterLast('.', "")?.lowercase().orEmpty()
        if (ext.isNotEmpty() && ext.length <= 4 && ext in UNSUPPORTED_EXT) return Validation.UnsupportedMime
        // Known audio extension → hint; unknown/extensionless → null so ExoPlayer sniffs.
        return Validation.Ok(uri, EXTENSION_MIME[ext])
    }

    /** UPnP duration/position format H:MM:SS; null = unknown → "NOT_IMPLEMENTED". */
    fun formatTime(ms: Long?): String {
        if (ms == null || ms < 0) return "NOT_IMPLEMENTED"
        val totalSec = ms / 1000
        return "%d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
    }

    fun parseTime(s: String?): Long? {
        if (s.isNullOrBlank()) return null
        val parts = s.substringBefore('.').split(':')
        if (parts.size != 3) return null
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val sec = parts[2].toLongOrNull() ?: return null
        if (h < 0 || m !in 0..59 || sec !in 0..59) return null
        return ((h * 3600 + m * 60 + sec) * 1000)
    }
}
