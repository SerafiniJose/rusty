package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class DlnaScreenStateTest {
    private fun snap(status: RendererStatus, transport: RendererTransport? = null,
                     transportStatus: String = "OK") = RendererUiSnapshot(
        state = transport?.let { RendererState(transport = it, transportStatus = transportStatus) },
        identity = RendererIdentity("Rusty", if (status == RendererStatus.RUNNING) "http://x" else null, status))

    @Test fun stopped() = assertEquals(DlnaScreen.STOPPED, dlnaScreenFor(snap(RendererStatus.STOPPED)))
    @Test fun starting() = assertEquals(DlnaScreen.STARTING, dlnaScreenFor(snap(RendererStatus.STARTING)))
    @Test fun failed() = assertEquals(DlnaScreen.FAILED, dlnaScreenFor(snap(RendererStatus.FAILED)))

    @Test fun runningNoMedia() =
        assertEquals(DlnaScreen.READY, dlnaScreenFor(snap(RendererStatus.RUNNING, RendererTransport.NO_MEDIA_PRESENT)))
    @Test fun runningIdleStopped() =
        assertEquals(DlnaScreen.READY, dlnaScreenFor(snap(RendererStatus.RUNNING, RendererTransport.STOPPED)))
    @Test fun runningTransitioning() =
        assertEquals(DlnaScreen.BUFFERING, dlnaScreenFor(snap(RendererStatus.RUNNING, RendererTransport.TRANSITIONING)))
    @Test fun playing() =
        assertEquals(DlnaScreen.NOW_PLAYING, dlnaScreenFor(snap(RendererStatus.RUNNING, RendererTransport.PLAYING)))
    @Test fun paused() =
        assertEquals(DlnaScreen.NOW_PLAYING, dlnaScreenFor(snap(RendererStatus.RUNNING, RendererTransport.PAUSED_PLAYBACK)))

    @Test fun playbackErrorOverridesTransport() =
        assertEquals(DlnaScreen.PLAYBACK_ERROR,
            dlnaScreenFor(snap(RendererStatus.RUNNING, RendererTransport.PLAYING, "ERROR_OCCURRED")))

    @Test fun runningButStateNullIsReady() =
        assertEquals(DlnaScreen.READY, dlnaScreenFor(snap(RendererStatus.RUNNING)))
}
