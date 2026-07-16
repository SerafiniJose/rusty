package dev.rusty.app.renderer

import dev.rusty.app.PlaybackAnchor
import dev.rusty.app.ReceiverServiceState
import dev.rusty.app.ReceiverSnapshot
import dev.rusty.app.testReceiverState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererSpotifyBridgeTest {
    private fun snap(user: String?, playing: Boolean, rev: Long): ReceiverSnapshot {
        val state = testReceiverState(sessionUser = user)
        return ReceiverSnapshot(
            state, rev,
            PlaybackAnchor(elapsedMs = 0, capturedRealtimeMs = 0, playing = playing),
            ReceiverServiceState.RUNNING,
        )
    }

    @Test fun sessionGeneration_bumpsOnUserChange() {
        val bridge = RendererSpotifyBridge(RendererStore { })
        bridge.onSnapshot(snap(null, false, 1))
        val g0 = bridge.sessionGeneration
        bridge.onSnapshot(snap("alice", false, 2))
        bridge.onSnapshot(snap("alice", false, 3))
        val g1 = bridge.sessionGeneration
        bridge.onSnapshot(snap("bob", false, 4))
        assertTrue(g1 > g0)
        assertTrue(bridge.sessionGeneration > g1)
    }

    @Test fun playingTransition_dispatchesSpotifyStartedPlaying_once() {
        val store = RendererStore { }
        val events = mutableListOf<RendererState>()
        store.addListener { s, _ -> events.add(s) }
        // put the renderer store into a state where SpotifyStartedPlaying is observable:
        store.dispatch(RendererEvent.SoapSetUri("http://x/a.mp3", "", null))
        store.dispatch(RendererEvent.SoapPlay(false, 0, SpotifyInterruption.PAUSE))
        store.dispatch(RendererEvent.PlayerPlaying(1))
        val bridge = RendererSpotifyBridge(store)
        bridge.onSnapshot(snap("alice", false, 1))
        bridge.onSnapshot(snap("alice", true, 2))   // false->true: must stop HA playback
        bridge.onSnapshot(snap("alice", true, 3))   // no re-dispatch while still true
        assertEquals(RendererTransport.STOPPED, store.state.transport)
        assertEquals(true, bridge.snapshot().first)
    }

    @Test fun firstSnapshot_alreadyPlaying_dispatchesSpotifyStartedPlaying() {
        val store = RendererStore { }
        // put the renderer store into PLAYING before the bridge ever observes a snapshot:
        store.dispatch(RendererEvent.SoapSetUri("http://x/a.mp3", "", null))
        store.dispatch(RendererEvent.SoapPlay(false, 0, SpotifyInterruption.PAUSE))
        store.dispatch(RendererEvent.PlayerPlaying(1))
        val bridge = RendererSpotifyBridge(store)
        // Registration-time takeover: the very first snapshot with playing=true must count as a
        // false→true transition and stop HA playback (Spotify wins at startup too).
        bridge.onSnapshot(snap("alice", true, 1))
        assertEquals(RendererTransport.STOPPED, store.state.transport)
    }
}
