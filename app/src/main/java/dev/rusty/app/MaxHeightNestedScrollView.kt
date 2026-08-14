package dev.rusty.app

import android.content.Context
import android.util.AttributeSet
import androidx.core.widget.NestedScrollView

/**
 * A [NestedScrollView] that never measures taller than [maxHeightFraction] of the display.
 *
 * The Services & status card grows with the number of rows, and in portrait (or on a short TV panel)
 * it can outgrow the screen. Pinning the dialog window to a fixed fraction instead would leave a short
 * card sitting inside a tall transparent window — and because no card in this app sets
 * `setCanceledOnTouchOutside`, taps in that dead band land inside the window and do not dismiss it.
 * Capping the scroll container keeps the card wrap-content when it fits and scrolls it when it doesn't.
 *
 * The cap is set programmatically ([maxHeightFraction]) rather than via a custom XML attribute, so this
 * needs no `attrs.xml` and the arithmetic stays in the JVM-tested [maxCardContentHeightPx].
 */
class MaxHeightNestedScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : NestedScrollView(context, attrs, defStyleAttr) {

    /** 0f (the default) means no cap, so the view behaves exactly like a plain NestedScrollView. */
    var maxHeightFraction: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Read the metrics at measure time, not at construction: the shell absorbs rotation, so the
        // display can flip between portrait and landscape while this card is showing.
        val cap = maxCardContentHeightPx(resources.displayMetrics.heightPixels, maxHeightFraction)
        if (cap <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(cap, MeasureSpec.AT_MOST))
    }
}
