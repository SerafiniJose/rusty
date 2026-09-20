package dev.rusty.app

/** A video track as far as the codec hint cares. Built from a media3 Format by the Android side. */
data class VideoFormatInfo(val mime: String, val width: Int, val height: Int, val frameRate: Float)

object CodecNames {
    fun mimeForOnvif(codec: String): String? {
        val n = codec.uppercase().replace(".", "").replace("-", "").replace(" ", "")
        return when (n) {
            "H264", "AVC" -> "video/avc"
            "H265", "HEVC" -> "video/hevc"
            "MPEG4", "MP4V", "MP4VES" -> "video/mp4v-es"
            "JPEG", "MJPEG" -> "video/mjpeg"
            else -> null
        }
    }

    fun label(mime: String): String = when (mime.lowercase()) {
        "video/avc" -> "H.264"
        "video/hevc" -> "H.265"
        "video/mp4v-es" -> "MPEG-4"
        "video/mjpeg" -> "MJPEG"
        else -> mime.substringAfter('/').uppercase()
    }
}

/**
 * What the live view's info chip shows: the format the player reported, plus the frame rate
 * MEASURED from rendered frames ([StreamMeter]); [fps] is null until the first second closes.
 * The SDP frame rate in [VideoFormatInfo.frameRate] is ignored — most cameras leave it at -1.
 */
data class VideoStats(val format: VideoFormatInfo?, val fps: Int?) {
    fun chipText(): String? {
        val f = format ?: return null
        if (f.width <= 0 || f.height <= 0) return null
        val rate = fps?.toString() ?: "…"
        return "${f.width}×${f.height} · $rate fps · ${CodecNames.label(f.mime)}"
    }
}

/** The one sentence the app says about a stream this device cannot decode, with the fix in it. */
object CodecHint {
    fun describe(f: VideoFormatInfo): String {
        val base = "${CodecNames.label(f.mime)} · ${f.width}×${f.height}"
        return if (f.frameRate > 0f) "$base · ${f.frameRate.toInt()} fps" else base
    }

    fun build(f: VideoFormatInfo, decoderFound: Boolean, suffix: String): String? {
        if (decoderFound) return null
        return "This device can't decode ${CodecNames.label(f.mime)} at ${f.width}×${f.height} — $suffix"
    }
}

/** One result line of the edit dialog's Test: the mark plus, when the probe learned it, the
 *  stream's codec, resolution and frame rate. Pure string work, so it stays JVM-testable. */
object TestReport {
    fun line(label: String, ok: Boolean, format: VideoFormatInfo?): String {
        val mark = if (ok) "✓" else "✗"
        return if (format == null) "$label $mark" else "$label $mark · ${CodecHint.describe(format)}"
    }
}
