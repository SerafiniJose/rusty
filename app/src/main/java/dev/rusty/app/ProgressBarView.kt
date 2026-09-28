package dev.rusty.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat

/**
 * The now-playing progress bar: a 6 dp rounded track with an accent fill, DRAWN in [onDraw]
 * rather than laid out. Setting [progress] only invalidates — the old fill was a child `View`
 * whose `layoutParams.width` was rewritten every second, which re-measured the whole
 * now-playing tree per tick and left the bar empty until the second render.
 *
 * The view is [HEIGHT_DP] tall with the bar centred, so a finger has something to hit on a
 * touch-only screen (the Echo Show). Gestures are reported as track ratios through
 * [scrubListener]; the meaning (preview, seek, debounce) lives in [ProgressScrubModel].
 *
 * D-pad: the view is focusable and draws its own thumb + ring while focused. LEFT/RIGHT while
 * focused go to [stepListener] (the fragment turns them into ±10 s steps) and are consumed;
 * UP/DOWN are left to the framework / the fragment's focus routing so the bar is never a trap.
 */
class ProgressBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    /** Touch gestures along the track; every ratio is 0..1 of the width. */
    interface ScrubListener {
        fun onScrubStart(ratio: Float)
        fun onScrubMove(ratio: Float)
        fun onScrubEnd(ratio: Float)
        fun onScrubCancel()
    }

    /** One DPAD_LEFT (direction -1) or DPAD_RIGHT (+1) press while focused, with its repeat count. */
    fun interface StepListener {
        fun onStep(direction: Int, repeatCount: Int)
    }

    var scrubListener: ScrubListener? = null
    var stepListener: StepListener? = null

    private val density = resources.displayMetrics.density
    private val barHeight = BAR_DP * density
    private val thumbRadius = THUMB_DP * density
    private val ringWidth = RING_DP * density

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = TRACK_COLOR }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent_fallback)
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringWidth
        color = ContextCompat.getColor(context, R.color.ink)
    }
    private val rect = RectF()

    /** Filled fraction of the track, 0..1. Only repaints; never requests a layout. */
    var progress: Float = 0f
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (clamped == field) return
            field = clamped
            invalidate()
        }

    /** The fill and thumb colour — the fragment feeds it the album accent. */
    var fillColor: Int
        get() = fillPaint.color
        set(value) {
            fillPaint.color = value
            invalidate()
        }

    /** Draws the thumb even without focus (a finger is on the bar). */
    var thumbVisible: Boolean = false
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    init {
        isFocusable = true
    }

    /** Ratio of the track under [x], clamped to 0..1. */
    fun ratioAt(x: Float): Float = if (width <= 0) 0f else (x / width).coerceIn(0f, 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = resolveSize((HEIGHT_DP * density).toInt(), heightMeasureSpec)
        setMeasuredDimension(getDefaultSize(suggestedMinimumWidth, widthMeasureSpec), height)
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        if (w <= 0f) return
        val cy = height / 2f
        val top = cy - barHeight / 2f
        val bottom = cy + barHeight / 2f
        val radius = barHeight / 2f
        rect.set(0f, top, w, bottom)
        canvas.drawRoundRect(rect, radius, radius, trackPaint)
        val fillEnd = w * progress
        if (fillEnd > 0f) {
            rect.set(0f, top, fillEnd, bottom)
            canvas.drawRoundRect(rect, radius, radius, fillPaint)
        }
        if (isFocused || thumbVisible) {
            // coerceIn(thumbRadius, w - thumbRadius) would throw if w < 2 * thumbRadius (a
            // narrower-than-thumb layout, where the "low" bound would exceed the "high" one).
            // This clamps from both ends without ever throwing, and for the normal case
            // (w >= 2 * thumbRadius) reduces to exactly the original coerceIn(thumbRadius, w - thumbRadius).
            val cx = fillEnd.coerceAtLeast(minOf(thumbRadius, w - thumbRadius)).coerceAtMost(maxOf(thumbRadius, w - thumbRadius))
            canvas.drawCircle(cx, cy, thumbRadius, fillPaint)
            if (isFocused) canvas.drawCircle(cx, cy, thumbRadius + ringWidth, ringPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val listener = scrubListener ?: return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // Nothing above us scrolls, but be explicit: the gesture is ours end to end.
                parent?.requestDisallowInterceptTouchEvent(true)
                thumbVisible = true
                listener.onScrubStart(ratioAt(event.x))
            }
            MotionEvent.ACTION_MOVE -> listener.onScrubMove(ratioAt(event.x))
            MotionEvent.ACTION_UP -> {
                thumbVisible = false
                listener.onScrubEnd(ratioAt(event.x))
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                thumbVisible = false
                listener.onScrubCancel()
            }
            else -> return super.onTouchEvent(event)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val listener = stepListener ?: return super.onKeyDown(keyCode, event)
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                listener.onStep(-1, event.repeatCount)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                listener.onStep(1, event.repeatCount)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        // Consume the matching UP so the key never falls through to a system default.
        if (stepListener != null &&
            (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
        ) return true
        return super.onKeyUp(keyCode, event)
    }

    private companion object {
        const val HEIGHT_DP = 32f
        const val BAR_DP = 6f
        const val THUMB_DP = 7f
        const val RING_DP = 2f

        /** Same translucent white as the old `bg_progress_track_ambient` drawable (#33FFFFFF). */
        const val TRACK_COLOR = 0x33FFFFFF
    }
}
