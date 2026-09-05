package dev.rusty.app

import android.media.MediaCodecList

/** Can this device's hardware/software decoders handle a video track? One Android line, kept
 *  out of the pure helpers so CodecHint and OnvifSoap stay JVM-testable. */
object DeviceDecoders {
    fun canDecode(mime: String, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        return try {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { info ->
                !info.isEncoder &&
                    info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    (info.getCapabilitiesForType(mime).videoCapabilities?.isSizeSupported(width, height) ?: false)
            }
        } catch (_: Exception) {
            true
        }
    }
}
