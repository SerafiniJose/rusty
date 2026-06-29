package dev.rusty.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CanvasPlayerViewTest {
    @Test fun playerIsMutedAndLooping() {
        val instr = InstrumentationRegistry.getInstrumentation()
        instr.runOnMainSync {
            val view = CanvasPlayerView(instr.targetContext, null)
            // Use a dummy URI — loading is async and irrelevant to the synchronous
            // mute/loop assertions (volume and repeatMode are set before prepare()).
            // No binary asset is required; this test is compile-only (no device attached).
            view.play("file:///android_asset/none.mp4")
            assertTrue("Canvas audio must be muted (librespot is the real audio)", view.isMutedForTest)
            assertTrue("Canvas must loop", view.isLoopingForTest)
            view.release()
        }
    }
}
