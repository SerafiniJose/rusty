package dev.rusty.app

import android.graphics.Bitmap
import androidx.palette.graphics.Palette

/** Result of processing a cover bitmap. */
data class Artwork(val accent: Int, val wash: Bitmap)

/**
 * Turns a decoded cover into (accent color, blurred wash). The wash is a heavily
 * downscaled copy shown center-cropped and scaled up under a scrim, giving a smooth
 * blur on every API level without RenderEffect/RenderScript.
 *
 * Takes an **already-generated** [Palette] (produced by Palette's async API on a worker
 * thread) rather than generating one here. This is called from Coil's `onSuccess`, which
 * runs on the main thread — generating the palette synchronously would push the pixel
 * histogram onto the UI thread and risk a hitch exactly as the bloom animates.
 */
object ArtworkProcessor {
    private const val WASH_SIZE = 48

    fun fromPalette(palette: Palette?, cover: Bitmap, fallbackAccent: Int): Artwork {
        val candidates = listOf(
            palette?.vibrantSwatch?.rgb,
            palette?.lightVibrantSwatch?.rgb,
            palette?.lightMutedSwatch?.rgb,
            palette?.darkVibrantSwatch?.rgb,
            palette?.dominantSwatch?.rgb
        )
        val accent = AccentPicker.pick(candidates, fallbackAccent)
        val wash = Bitmap.createScaledBitmap(cover, WASH_SIZE, WASH_SIZE, true)
        return Artwork(accent, wash)
    }
}
