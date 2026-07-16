package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererServiceBackendTest {
    @Test fun playCarriesLiveSpotifyAndFadeContext() {
        val e = RendererCommandTranslator.toEvent(
            RendererCommand.Play,
            spotifyPlaying = true, spotifySessionGen = 7L,
            mixMode = SpotifyInterruption.DUCK, fadeMs = 500L)
        assertTrue(e is RendererEvent.SoapPlay)
        e as RendererEvent.SoapPlay
        assertEquals(true, e.spotifyPlaying)
        assertEquals(7L, e.spotifySessionGen)
        assertEquals(SpotifyInterruption.DUCK, e.mixMode)
        assertEquals(500L, e.fadeMs)
    }

    @Test fun pauseStopSeekMapDirectly() {
        assertTrue(RendererCommandTranslator.toEvent(RendererCommand.Pause, false, 0, SpotifyInterruption.PAUSE, 0) is RendererEvent.SoapPause)
        assertTrue(RendererCommandTranslator.toEvent(RendererCommand.Stop, false, 0, SpotifyInterruption.PAUSE, 0) is RendererEvent.SoapStop)
        val seek = RendererCommandTranslator.toEvent(RendererCommand.Seek(999L), false, 0, SpotifyInterruption.PAUSE, 0)
        assertEquals(RendererEvent.SoapSeek(999L), seek)
    }
}
