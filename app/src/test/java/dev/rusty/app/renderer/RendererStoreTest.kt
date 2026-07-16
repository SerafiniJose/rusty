package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RendererStoreTest {
    @Test fun dispatch_appliesReducerAndDeliversEffectsInOrder() {
        val effects = mutableListOf<RendererEffect>()
        val store = RendererStore { effects.add(it) }
        store.dispatch(RendererEvent.SoapSetUri("http://x/a.mp3", "", "audio/mpeg"))
        store.dispatch(RendererEvent.SoapPlay(spotifyPlaying = true, spotifySessionGen = 1, mixMode = SpotifyInterruption.PAUSE))
        assertEquals(
            listOf(
                RendererEffect.PreparePlayer("http://x/a.mp3", "audio/mpeg", 1L),
                RendererEffect.PauseSpotify, RendererEffect.PlayPlayer,
            ),
            effects,
        )
        assertEquals(RendererTransport.TRANSITIONING, store.state.transport)
    }

    @Test fun listeners_seeEveryRevisionInOrder_withInitialDelivery() {
        val store = RendererStore { }
        val seen = mutableListOf<Long>()
        store.dispatch(RendererEvent.SoapSetUri("http://x/a.mp3", "", null))
        store.addListener { _, rev -> seen.add(rev) }
        store.dispatch(RendererEvent.SoapPlay(false, 1, SpotifyInterruption.PAUSE))
        assertEquals(listOf(1L, 2L), seen)
    }

    @Test fun concurrentDispatch_neverTearsOrder() {
        val store = RendererStore { }
        val revs = java.util.Collections.synchronizedList(mutableListOf<Long>())
        store.addListener { _, rev -> revs.add(rev) }
        val latch = CountDownLatch(8)
        repeat(8) { i ->
            Thread {
                store.dispatch(RendererEvent.SoapSetUri("http://x/$i.mp3", "", null))
                latch.countDown()
            }.start()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        // Drain is inline: after all dispatchers return, every revision was delivered strictly increasing.
        assertEquals(revs.sorted(), revs)
        assertEquals(9, revs.size) // initial delivery (rev 0) + 8 dispatches
    }

    @Test fun reentrantAddListener_midDrain_neverDuplicatesRevisions() {
        val lateSeen = mutableListOf<Long>()
        val lateListener = RendererStore.Listener { _, rev -> lateSeen.add(rev) }
        lateinit var store: RendererStore
        var first = true
        store = RendererStore {
            if (first) {
                first = false
                // Re-entrant registration mid-drain: the dispatch's own snapshot (rev 1) is
                // still pending in the queue when this runs.
                store.addListener(lateListener)
            }
        }
        store.dispatch(RendererEvent.SoapSetUri("http://x/a.mp3", "", "audio/mpeg"))
        assertEquals(listOf(1L), lateSeen)
    }

    @Test fun throwingListener_doesNotWedgeStore() {
        val store = RendererStore { }
        store.addListener { _, _ -> throw RuntimeException("boom") }
        val normalSeen = mutableListOf<Long>()
        store.addListener { _, rev -> normalSeen.add(rev) }
        store.dispatch(RendererEvent.SoapSetUri("http://x/a.mp3", "", null))
        store.dispatch(RendererEvent.SoapPlay(false, 1, SpotifyInterruption.PAUSE))
        assertEquals(listOf(0L, 1L, 2L), normalSeen)
        // A third dispatch still arrives: the drain was not wedged by the throwing listener.
        store.dispatch(RendererEvent.SoapPause)
        assertEquals(listOf(0L, 1L, 2L, 3L), normalSeen)
    }
}
