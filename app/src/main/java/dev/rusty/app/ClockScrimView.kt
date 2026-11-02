package dev.rusty.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * A soft radial scrim used behind the screensaver clock. The clock is deliberately huge and thin,
 * so it needs contrast in the middle of the panel where the glyphs are — not a flat dim over the
 * whole backdrop. This draws a transparent-edged ellipse centred on the clock, letting the mesh or
 * album art stay visible at the corners while the numerals sit on a stable field.
 *
 * [configure] is cheap and idempotent: [ClockTheme] calls it from its 1 Hz tick, but it only
 * invalidates when something actually changed.
 */
class ClockScrimView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var scrimColor = Color.BLACK
    private var strength = 0.4f
    private var centerYFraction = 0.42f
    private var radiusFraction = 0.62f

    /**
     * @param color the scrim colour (black on dark faces, white on [ClockBackdrop.PAPER])
     * @param strength 0..1 opacity at the centre
     * @param centerYFraction where the clock sits vertically, 0..1
     * @param radiusFraction radius as a fraction of the panel's longest side
     */
    fun configure(
        color: Int,
        strength: Float,
        centerYFraction: Float = 0.42f,
        radiusFraction: Float = 0.62f,
    ) {
        val nextStrength = strength.coerceIn(0f, 1f)
        if (color == scrimColor &&
            nextStrength == this.strength &&
            centerYFraction == this.centerYFraction &&
            radiusFraction == this.radiusFraction
        ) {
            return
        }
        scrimColor = color
        this.strength = nextStrength
        this.centerYFraction = centerYFraction
        this.radiusFraction = radiusFraction
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (strength <= 0f || width == 0 || height == 0) return
        val centreX = width / 2f
        val centreY = height * centerYFraction
        val radius = max(width, height) * radiusFraction
        val alpha = (strength * 255f).toInt().coerceIn(1, 255)
        val centre = withAlpha(scrimColor, alpha)
        val middle = withAlpha(scrimColor, (alpha * 0.55f).toInt())
        paint.shader = RadialGradient(
            centreX,
            centreY,
            radius,
            intArrayOf(centre, middle, Color.TRANSPARENT),
            floatArrayOf(0f, 0.58f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
}
