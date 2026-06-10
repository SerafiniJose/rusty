package dev.rusty.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.Random

/** Static, tiled monochrome noise at very low alpha to prevent banding on gradients. */
class GrainView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val paint = Paint().apply {
        shader = BitmapShader(noise(96), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        alpha = 13 // ~5%
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    private fun noise(size: Int): Bitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val rnd = Random(7L) // deterministic
        for (y in 0 until size) {
            for (x in 0 until size) {
                val v = rnd.nextInt(256)
                bmp.setPixel(x, y, (0xFF shl 24) or (v shl 16) or (v shl 8) or v)
            }
        }
        return bmp
    }
}
