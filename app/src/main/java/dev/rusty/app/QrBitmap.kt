package dev.rusty.app

import android.graphics.Bitmap
import android.graphics.Color

/** Draws a [QrCode] as a square bitmap: dark modules on white with the standard 4-module quiet
 *  zone. Always black-on-white regardless of theme — that is what phone cameras decode best. */
object QrBitmap {
    private const val QUIET = 4

    fun render(qr: QrCode, sizePx: Int): Bitmap {
        val modules = qr.size + QUIET * 2
        val scale = maxOf(1, sizePx / modules)
        val px = modules * scale
        val pixels = IntArray(px * px) { Color.WHITE }
        for (y in 0 until qr.size) for (x in 0 until qr.size) {
            if (!qr[x, y]) continue
            val left = (x + QUIET) * scale
            val top = (y + QUIET) * scale
            for (dy in 0 until scale) {
                val row = (top + dy) * px
                for (dx in 0 until scale) pixels[row + left + dx] = Color.BLACK
            }
        }
        return Bitmap.createBitmap(pixels, px, px, Bitmap.Config.ARGB_8888)
    }
}
