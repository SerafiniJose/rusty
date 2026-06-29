package dev.rusty.app

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable

/**
 * A [Drawable] that renders a single Material Design Icons glyph using the MDI [Typeface]. Used as a
 * Material `Chip`'s `chipIcon` so any HA dashboard icon can be drawn from the bundled font.
 *
 * It honors the chip's `chipIconTint` [ColorStateList]: Material applies the tint via
 * `DrawableCompat.setTintList` and forwards the chip's drawable state (incl. `state_checked`) to this
 * drawable, so the active-chip green tint ([R.color.chip_icon_tint]) works exactly like it does for the
 * vector icons.
 *
 * @param glyph the single-codepoint String to draw (from [MdiFont.glyphFor]).
 * @param sizePx intrinsic icon size in pixels (matches the chip's `chipIconSize`).
 */
class MdiGlyphDrawable(
    typeface: Typeface,
    private val glyph: String,
    private val sizePx: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.typeface = typeface
        textAlign = Paint.Align.CENTER
        color = Color.WHITE
    }
    private var tintList: ColorStateList? = null

    override fun getIntrinsicWidth(): Int = sizePx
    override fun getIntrinsicHeight(): Int = sizePx

    override fun isStateful(): Boolean = tintList?.isStateful == true

    override fun setTintList(tint: ColorStateList?) {
        tintList = tint
        updateColor(state)
        invalidateSelf()
    }

    override fun onStateChange(state: IntArray): Boolean = updateColor(state)

    private fun updateColor(state: IntArray): Boolean {
        val list = tintList ?: return false
        val next = list.getColorForState(state, list.defaultColor)
        if (next != paint.color) {
            paint.color = next
            invalidateSelf()
            return true
        }
        return false
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Size the glyph to the bounds and center it on the text baseline.
        paint.textSize = b.height().toFloat()
        val fm = paint.fontMetrics
        val baseline = b.exactCenterY() - (fm.ascent + fm.descent) / 2f
        canvas.drawText(glyph, b.exactCenterX(), baseline, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter; invalidateSelf() }
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
