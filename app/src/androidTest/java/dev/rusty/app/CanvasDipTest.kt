package dev.rusty.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Canvas layer's half of a track transition, on the two channels it moves.
 *
 * The bug this pins: the dip between two loops used to ride the video view's OWN alpha. A Canvas
 * layer is always stacked on the picture it replaces — the album-art card on the now-playing
 * screen, the blurred wash on the lockscreen — so fading the video out does not darken it, it
 * uncovers the cover art underneath, which the on-screen crossfade is dissolving at that same
 * moment. Canvas → canvas therefore read as canvas → cover → next cover → next canvas.
 *
 * Assertions are on the animation TARGETS, not on the values after a wait: these views are never
 * attached to a window here, and a ViewPropertyAnimator on a detached view does not run.
 */
@RunWith(AndroidJUnit4::class)
class CanvasDipTest {

    @Test fun aTrackChangeDarkensTheLoopAndLeavesItsOwnOpacityAlone() {
        onMain { view ->
            val layer = CanvasLayer(view)
            layer.apply(CanvasState.Found(LOOP_A), TRANSITION_MS)
            assertEquals("a loop arriving over the art fades up", 1f, view.opacityTargetForTest, 0f)
            assertEquals("and arrives clear, never dark", 0f, view.dimTargetForTest, 0f)

            // The track changed; the next loop's URL is still resolving.
            layer.apply(CanvasState.Loading, TRANSITION_MS)
            assertEquals("the dip must aim the scrim at black", 1f, view.dimTargetForTest, 0f)
            assertEquals(
                "the loop's own opacity must not carry the dip — that uncovers the album art",
                1f,
                view.opacityTargetForTest,
                0f,
            )
        }
    }

    @Test fun oneLoopReplacingAnotherDipsFirstAndNeverFadesOut() {
        onMain { view ->
            val layer = CanvasLayer(view)
            layer.apply(CanvasState.Found(LOOP_A), TRANSITION_MS)
            // The next loop's URL resolved without a Loading in between: dip first anyway, the
            // media item is only replaced once the scrim is opaque.
            layer.apply(CanvasState.Found(LOOP_B), TRANSITION_MS)

            assertEquals("a swap dips to black", 1f, view.dimTargetForTest, 0f)
            assertEquals(
                "the outgoing loop must stay put — it is being darkened, not dismissed",
                1f,
                view.opacityTargetForTest,
                0f,
            )
            assertTrue("the layer still owns the screen throughout", layer.isShowing)
        }
    }

    @Test fun aTrackWithNoCanvasFadesTheLoopOutAgainstTheArt() {
        onMain { view ->
            val layer = CanvasLayer(view)
            layer.apply(CanvasState.Found(LOOP_A), TRANSITION_MS)
            layer.apply(CanvasState.None, TRANSITION_MS)
            // Leaving IS a dissolve with the album art, so this one is the opacity channel's job.
            assertEquals(0f, view.opacityTargetForTest, 0f)
        }
    }

    private fun onMain(body: (CanvasPlayerView) -> Unit) {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync {
            val view = CanvasPlayerView(instr.targetContext, null)
            try {
                body(view)
            } finally {
                view.release()
            }
        }
    }

    private companion object {
        // Loading is async and irrelevant here: every assertion is about the choreography, which is
        // decided before a byte is fetched. No binary asset is required.
        const val LOOP_A = "file:///android_asset/a.mp4"
        const val LOOP_B = "file:///android_asset/b.mp4"
        const val TRANSITION_MS = 4_000L
    }
}
