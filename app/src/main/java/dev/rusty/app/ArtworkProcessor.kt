package dev.rusty.app

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/** Result of processing a cover bitmap. */
data class Artwork(val accent: Int, val wash: Bitmap)

/**
 * Turns a decoded cover into (accent color, blurred wash). The wash is a blurred copy shown
 * center-cropped and scaled up under a scrim. It used to be 48×48, which read as a pixelated
 * mosaic on a TV-sized panel next to a sharp album cover; 640×640 keeps the background at the
 * same resolution Spotify serves the cover at, so the only softness left is the deliberate blur.
 * The blur is plain integer pixel work so it needs no RenderEffect/RenderScript and works on
 * every API level.
 *
 * Takes an **already-generated** [Palette] (produced by Palette's async API on a worker
 * thread) rather than generating one here. This is called from Coil's `onSuccess`, which
 * runs on the main thread — generating the palette synchronously would push the pixel
 * histogram onto the UI thread and risk a hitch exactly as the bloom animates. The blur is
 * bounded to 640² and two passes to keep that same main-thread cost small.
 */
object ArtworkProcessor {
    private const val WASH_SIZE = 640
    private const val WASH_BLUR_RADIUS = 8
    private const val WASH_BLUR_PASSES = 2

    fun fromPalette(palette: Palette?, cover: Bitmap, fallbackAccent: Int): Artwork {
        val candidates = listOf(
            palette?.vibrantSwatch?.rgb,
            palette?.lightVibrantSwatch?.rgb,
            palette?.lightMutedSwatch?.rgb,
            palette?.darkVibrantSwatch?.rgb,
            palette?.dominantSwatch?.rgb
        )
        val accent = AccentPicker.pick(candidates, fallbackAccent)
        val scaled = Bitmap.createScaledBitmap(cover, WASH_SIZE, WASH_SIZE, true)
        return Artwork(accent, blurred(scaled))
    }

    private fun blurred(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        if (width == 0 || height == 0) return source
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val scratch = IntArray(pixels.size)
        repeat(WASH_BLUR_PASSES) {
            boxBlurHorizontal(pixels, scratch, width, height, WASH_BLUR_RADIUS)
            boxBlurVertical(scratch, pixels, width, height, WASH_BLUR_RADIUS)
        }
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, width, 0, 0, width, height)
        return output
    }

    /** One-pass box blur along X, clamping at the edges. [src] and [dst] must not be the same array. */
    private fun boxBlurHorizontal(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val diameter = radius * 2 + 1
        for (y in 0 until height) {
            val row = y * width
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = src[row + i.coerceIn(0, width - 1)]
                a += pixel ushr 24
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
            }
            for (x in 0 until width) {
                dst[row + x] = ((a / diameter) shl 24) or
                    ((r / diameter) shl 16) or
                    ((g / diameter) shl 8) or
                    (b / diameter)
                val outgoing = src[row + (x - radius).coerceIn(0, width - 1)]
                val incoming = src[row + (x + radius + 1).coerceIn(0, width - 1)]
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
                g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }

    /** One-pass box blur along Y, clamping at the edges. [src] and [dst] must not be the same array. */
    private fun boxBlurVertical(src: IntArray, dst: IntArray, width: Int, height: Int, radius: Int) {
        val diameter = radius * 2 + 1
        for (x in 0 until width) {
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val pixel = src[i.coerceIn(0, height - 1) * width + x]
                a += pixel ushr 24
                r += (pixel shr 16) and 0xFF
                g += (pixel shr 8) and 0xFF
                b += pixel and 0xFF
            }
            for (y in 0 until height) {
                dst[y * width + x] = ((a / diameter) shl 24) or
                    ((r / diameter) shl 16) or
                    ((g / diameter) shl 8) or
                    (b / diameter)
                val outgoing = src[(y - radius).coerceIn(0, height - 1) * width + x]
                val incoming = src[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                a += (incoming ushr 24) - (outgoing ushr 24)
                r += ((incoming shr 16) and 0xFF) - ((outgoing shr 16) and 0xFF)
                g += ((incoming shr 8) and 0xFF) - ((outgoing shr 8) and 0xFF)
                b += (incoming and 0xFF) - (outgoing and 0xFF)
            }
        }
    }
}
