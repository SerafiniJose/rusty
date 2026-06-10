package dev.rusty.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Slow-drifting color mesh for the idle ambient face. "Medium" energy (~24s loop).
 * Call [start]/[stop] from the host's visibility lifecycle so it never animates off-screen.
 */
class AmbientMeshView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private data class Blob(
        val color: Int,
        val baseX: Float, val baseY: Float, val baseR: Float,
        val travelX: Float, val travelY: Float, val travelR: Float,
        val phase: Float
    )

    private val base = ContextCompat.getColor(context, R.color.bg_base)
    private val blobs = listOf(
        Blob(ContextCompat.getColor(context, R.color.mesh_teal),   0.18f, 0.22f, 0.55f,  0.14f,  0.10f,  0.16f, 0.00f),
        Blob(ContextCompat.getColor(context, R.color.mesh_blue),   0.82f, 0.82f, 0.52f, -0.12f, -0.09f, -0.10f, 0.55f),
        Blob(ContextCompat.getColor(context, R.color.mesh_violet), 0.55f, 0.50f, 0.45f, -0.10f,  0.10f,  0.20f, 0.30f)
    )
    // One unit-radius gradient per blob, built once. Per-frame we only move/scale it via a
    // reused Matrix — no allocation in onDraw, which matters for an always-on idle screen.
    private val shaders = blobs.map { b ->
        RadialGradient(0f, 0f, 1f, withAlpha(b.color, 150), Color.TRANSPARENT, Shader.TileMode.CLAMP)
    }
    private val shaderMatrix = Matrix()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 24_000L
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            t = it.animatedValue as Float
            invalidate()
        }
    }

    fun start() { if (!animator.isStarted) animator.start() }
    fun stop() { animator.cancel() }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(base)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w == 0f || h == 0f) return
        val diag = hypot(w, h)
        for (i in blobs.indices) {
            val b = blobs[i]
            val a = sin((t + b.phase) * 2.0 * Math.PI).toFloat() // -1..1
            val cx = (b.baseX + b.travelX * a) * w
            val cy = (b.baseY + b.travelY * a) * h
            val r = ((b.baseR + b.travelR * a) * diag * 0.5f).coerceAtLeast(1f)
            shaderMatrix.setScale(r, r)
            shaderMatrix.postTranslate(cx, cy)
            shaders[i].setLocalMatrix(shaderMatrix)
            paint.shader = shaders[i]
            canvas.drawCircle(cx, cy, r, paint)
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (alpha shl 24) or (color and 0x00FFFFFF)
}
