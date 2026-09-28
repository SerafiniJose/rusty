package dev.rusty.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bar is DRAWN, not laid out: a progress change must repaint the fill without a layout pass
 * (the old width-resizing FrameLayout re-measured the whole now-playing tree every second), and
 * the fill must cover exactly the leading fraction of the track.
 * Device-bound: run via `connectedDebugAndroidTest` (Task 7).
 */
@RunWith(AndroidJUnit4::class)
class ProgressBarViewTest {

    private fun measured(): ProgressBarView {
        val view = ProgressBarView(InstrumentationRegistry.getInstrumentation().targetContext)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        return view
    }

    private fun render(view: ProgressBarView): Bitmap {
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        return bitmap
    }

    // Every test performs its view work inside runOnMainSync but asserts OUTSIDE it: an
    // AssertionError thrown on the main thread there propagates as a process crash instead of a
    // clean test failure, so the values needed for the assertions are captured into locals first.

    @Test fun fillCoversTheLeadingHalfAtProgressHalf() {
        var filledPixel = 0
        var unfilledPixel = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = measured()
            view.fillColor = Color.RED
            view.progress = 0.5f
            val bitmap = render(view)
            val cy = view.height / 2
            filledPixel = bitmap.getPixel(100, cy)
            unfilledPixel = bitmap.getPixel(300, cy)
        }
        assertEquals(Color.RED, filledPixel)
        assertNotEquals(Color.RED, unfilledPixel)
        // The track is translucent white, so the unfilled part is neither red nor transparent.
        assertNotEquals(0, Color.alpha(unfilledPixel))
    }

    @Test fun progressChangesDoNotRequestLayout() {
        var layoutRequestedBefore = true
        var layoutRequestedAfter = true
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = measured()
            layoutRequestedBefore = view.isLayoutRequested
            view.progress = 0.25f
            view.progress = 0.75f
            layoutRequestedAfter = view.isLayoutRequested
        }
        assertFalse(layoutRequestedBefore)
        assertFalse("progress must only invalidate, never requestLayout", layoutRequestedAfter)
    }

    @Test fun measuresThirtyTwoDpTall() {
        var expectedHeight = -1
        var actualHeight = -1
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = measured()
            val density = view.resources.displayMetrics.density
            expectedHeight = (32 * density).toInt()
            actualHeight = view.height
        }
        assertEquals(expectedHeight, actualHeight)
    }

    @Test fun ratioAtMapsTheTrack() {
        var ratioNegative = -1f
        var ratioQuarter = -1f
        var ratioBeyondEnd = -1f
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = measured()
            ratioNegative = view.ratioAt(-10f)
            ratioQuarter = view.ratioAt(100f)
            ratioBeyondEnd = view.ratioAt(999f)
        }
        assertEquals(0f, ratioNegative, 0.001f)
        assertEquals(0.25f, ratioQuarter, 0.001f)
        assertEquals(1f, ratioBeyondEnd, 0.001f)
    }
}
