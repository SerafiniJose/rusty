package dev.rusty.app

import android.view.LayoutInflater
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.appcompat.view.ContextThemeWrapper
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The crossfade dissolve loads the next track's cover into [R.id.ivFullAlbumArtIncoming] with Coil,
 * and Coil cannot size a view that has never been laid out: `ViewSizeResolver` suspends on an
 * OnPreDrawListener until the view reports a size, and a GONE child is skipped by its parent's
 * measure pass, so it never reports one. The request then never completes — no success, no error —
 * and the dissolve (which is also what hands the new title to the screen) never starts.
 *
 * So the contract is: while the album-art card is showing, the incoming cover must be laid out.
 * INVISIBLE satisfies that — measured and laid out, simply not drawn — GONE does not.
 */
@RunWith(AndroidJUnit4::class)
class IncomingCoverLayoutTest {

    @Test
    fun incomingCoverIsLaidOutSoCoilCanResolveItsSize() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var width = -1
        var height = -1

        instrumentation.runOnMainSync {
            val context = ContextThemeWrapper(instrumentation.targetContext, R.style.Theme_Rusty)
            val root = LayoutInflater.from(context).inflate(R.layout.fragment_spotify, null)
            // The bloom shows the card on the idle→active edge; a dissolve only ever runs while
            // the dashboard is active, so that is the state this contract applies to.
            root.findViewById<View>(R.id.albumArtCard).visibility = View.VISIBLE

            val widthSpec = View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            root.measure(widthSpec, heightSpec)
            root.layout(0, 0, 1280, 800)

            val incoming = root.findViewById<View>(R.id.ivFullAlbumArtIncoming)
            width = incoming.width
            height = incoming.height
        }

        assertTrue(
            "The incoming cover must be measured so Coil can size the load into it, " +
                "but it is ${width}x$height — a GONE child is never measured.",
            width > 0 && height > 0,
        )
    }
}
