package dev.rusty.app

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Chooses a UI accent from artwork swatches (in priority order) and guarantees it reads
 * against the near-black ambient base by lightening too-dark choices toward white.
 * Pure ARGB-int math — no android.graphics — so it is unit tested on the JVM.
 */
object AccentPicker {
    /** Relative luminance below which an accent is too dark for the dark base. */
    const val MIN_LUMINANCE = 0.30

    /** @param candidates packed ARGB ints in priority order; nulls are skipped. */
    fun pick(candidates: List<Int?>, fallback: Int): Int {
        val chosen = candidates.firstOrNull { it != null } ?: fallback
        return ensureReadable(chosen)
    }

    private fun ensureReadable(color: Int): Int {
        var c = color
        var guard = 0
        while (luminance(c) < MIN_LUMINANCE && guard < 12) {
            c = blendTowardWhite(c, 0.18f)
            guard++
        }
        return c
    }

    fun luminance(color: Int): Double {
        val r = lin(((color shr 16) and 0xFF) / 255.0)
        val g = lin(((color shr 8) and 0xFF) / 255.0)
        val b = lin((color and 0xFF) / 255.0)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun lin(channel: Double): Double =
        if (channel <= 0.03928) channel / 12.92 else ((channel + 0.055) / 1.055).pow(2.4)

    private fun blendTowardWhite(color: Int, ratio: Float): Int {
        val a = (color shr 24) and 0xFF
        val r = mix((color shr 16) and 0xFF, 255, ratio)
        val g = mix((color shr 8) and 0xFF, 255, ratio)
        val b = mix(color and 0xFF, 255, ratio)
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mix(from: Int, to: Int, ratio: Float): Int =
        (from + (to - from) * ratio).roundToInt().coerceIn(0, 255)
}
