package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementRoutingTest {

    private fun playing() = RendererState(
        transport = RendererTransport.PLAYING,
        media = RendererMedia("file:///clip.wav", "", "audio/wav"),
        spotifyInterruption = SpotifyInterruption.PAUSE,
    )

    private fun finished() = RendererState(
        transport = RendererTransport.STOPPED,
        media = RendererMedia("file:///clip.wav", "", "audio/wav"),
    )

    // -- host ---------------------------------------------------------------

    @Test fun rendererDown_routesLocal() {
        assertEquals(AnnounceHost.LOCAL, AnnouncementRouting.host(rendererAlive = false, localState = null))
    }

    @Test fun rendererUp_withNoLocalPipeline_routesRenderer() {
        assertEquals(AnnounceHost.RENDERER, AnnouncementRouting.host(rendererAlive = true, localState = null))
    }

    @Test fun rendererUp_whileLocalStillPlaying_staysLocal() {
        // Handing the next announcement to the renderer here would put TWO players on the speaker
        // and TWO Spotify debts in flight; whichever released first would restore the music under
        // the other one's clip.
        assertEquals(AnnounceHost.LOCAL, AnnouncementRouting.host(rendererAlive = true, localState = playing()))
    }

    @Test fun rendererUp_afterLocalWentIdle_handsOver() {
        assertEquals(AnnounceHost.RENDERER, AnnouncementRouting.host(rendererAlive = true, localState = finished()))
    }

    @Test fun rendererDown_whileLocalIdle_stillRoutesLocal() {
        assertEquals(AnnounceHost.LOCAL, AnnouncementRouting.host(rendererAlive = false, localState = finished()))
    }

    // -- busy ---------------------------------------------------------------

    @Test fun freshPipelineIsNotBusy() {
        assertFalse(AnnouncementRouting.busy(RendererState()))
    }

    @Test fun playingIsBusy() {
        assertTrue(AnnouncementRouting.busy(RendererState(transport = RendererTransport.PLAYING)))
    }

    @Test fun transitioningIsBusy() {
        assertTrue(AnnouncementRouting.busy(RendererState(transport = RendererTransport.TRANSITIONING)))
    }

    @Test fun pausedIsBusy() {
        assertTrue(AnnouncementRouting.busy(RendererState(transport = RendererTransport.PAUSED_PLAYBACK)))
    }

    @Test fun stoppedWithAnUnreleasedSpotifyDebtIsBusy() {
        // The clip ended but the grace timer has not fired: Spotify is still paused/ducked and
        // this pipeline is the one that owes the release.
        assertTrue(
            AnnouncementRouting.busy(
                RendererState(
                    transport = RendererTransport.STOPPED,
                    spotifyInterruption = SpotifyInterruption.DUCK,
                    resumePending = true,
                ),
            ),
        )
    }

    @Test fun stoppedMidFadeIsBusy() {
        assertTrue(
            AnnouncementRouting.busy(
                RendererState(transport = RendererTransport.STOPPED, fadePhase = FadePhase.FADE_IN_PENDING),
            ),
        )
    }

    @Test fun stoppedAndSettledIsNotBusy() {
        assertFalse(AnnouncementRouting.busy(finished()))
    }

    // -- shouldReleaseLocal -------------------------------------------------

    @Test fun noLocalPipeline_nothingToRelease() {
        assertFalse(AnnouncementRouting.shouldReleaseLocal(rendererAlive = true, localState = null))
        assertFalse(AnnouncementRouting.shouldReleaseLocal(rendererAlive = false, localState = null))
    }

    @Test fun idleLocalPipeline_isReleasedOnceTheRendererCanTakeOver() {
        assertTrue(AnnouncementRouting.shouldReleaseLocal(rendererAlive = true, localState = finished()))
    }

    @Test fun busyLocalPipeline_isKeptEvenWithTheRendererUp() {
        assertFalse(AnnouncementRouting.shouldReleaseLocal(rendererAlive = true, localState = playing()))
    }

    @Test fun rendererDown_keepsTheLocalPipeline() {
        // It is the only pipeline there is — releasing it would just rebuild it on the next
        // announcement, dropping the ExoPlayer's warm state for nothing.
        assertFalse(AnnouncementRouting.shouldReleaseLocal(rendererAlive = false, localState = finished()))
    }
}
