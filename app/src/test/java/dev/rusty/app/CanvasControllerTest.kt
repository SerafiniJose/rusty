package dev.rusty.app

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CanvasControllerTest {

    private val synchronousPoster = MainPoster { it.run() }

    private fun store(trackId: String? = null): ReceiverStateStore =
        ReceiverStateStore(
            ReceiverDashboardState.waiting("A").copy(trackId = trackId),
            synchronousPoster,
            MonotonicClock { 0L },
        )

    private fun playing(trackId: String) = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "A",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "t", trackArtist = "a", coverArtUrl = null,
            trackId = trackId, sessionUser = "u", elapsedMs = 0L, durationMs = 1L,
        ),
    )

    private fun paused(trackId: String) = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "A",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PAUSED,
            trackTitle = "t", trackArtist = "a", coverArtUrl = null,
            trackId = trackId, sessionUser = "u", elapsedMs = 0L, durationMs = 1L,
        ),
    )

    private class FakeFetcher(val map: MutableMap<String, CanvasFetch> = mutableMapOf()) : CanvasFetcher {
        val calls = mutableListOf<Pair<String, String>>()
        override fun fetch(trackId: String, token: String): CanvasFetch {
            calls.add(trackId to token)
            return map[trackId] ?: CanvasFetch.Success(CanvasResult.None)
        }
    }

    private fun controller(
        store: ReceiverStateStore,
        fetcher: CanvasFetcher,
        scope: TestScope,
        dispatcher: TestDispatcher,
        enabled: Boolean = true,
        token: suspend (Boolean) -> String? = { "tok" },
    ) = CanvasController(
        store = store,
        fetcher = fetcher,
        tokenProvider = token,
        isEnabled = { enabled },
        scope = scope,
        ioDispatcher = dispatcher,
        debounceMs = 250L,
    )

    @Test fun foundCanvasEmitsLoadingThenFound() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher(mutableMapOf("t1" to CanvasFetch.Success(CanvasResult.Found("u1"))))
        val seen = mutableListOf<CanvasState>()
        val c = controller(s, fetcher, this, dispatcher)
        c.addListener { seen.add(it) }
        c.start()
        seen.clear() // drop the initial None that addListener delivers on subscribe
        s.dispatch(playing("t1"))
        advanceUntilIdle()
        assertEquals(listOf(CanvasState.Loading, CanvasState.Found("u1")), seen)
        c.stop()
    }

    @Test fun noCanvasEmitsLoadingThenNone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher()
        val seen = mutableListOf<CanvasState>()
        val c = controller(s, fetcher, this, dispatcher)
        c.addListener { seen.add(it) }
        c.start()
        seen.clear() // drop the initial None that addListener delivers on subscribe
        s.dispatch(playing("t1"))
        advanceUntilIdle()
        assertEquals(listOf(CanvasState.Loading, CanvasState.None), seen)
        c.stop()
    }

    @Test fun toggleOffSkipsFetchAndStaysNone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher(mutableMapOf("t1" to CanvasFetch.Success(CanvasResult.Found("u1"))))
        val c = controller(s, fetcher, this, dispatcher, enabled = false)
        c.start()
        s.dispatch(playing("t1"))
        advanceUntilIdle()
        assertEquals(CanvasState.None, c.state)
        assertEquals(0, fetcher.calls.size)
        c.stop()
    }

    @Test fun rapidSkipDebouncesToLastTrackOnly() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher(mutableMapOf(
            "t1" to CanvasFetch.Success(CanvasResult.Found("u1")),
            "t2" to CanvasFetch.Success(CanvasResult.Found("u2")),
        ))
        val c = controller(s, fetcher, this, dispatcher)
        c.start()
        s.dispatch(playing("t1"))
        s.dispatch(playing("t2")) // arrives within the debounce window — t1's job is cancelled
        advanceUntilIdle()
        assertEquals(CanvasState.Found("u2"), c.state)
        assertEquals(listOf("t2"), fetcher.calls.map { it.first })
        c.stop()
    }

    @Test fun unauthorizedRefreshesTokenAndRetriesOnce() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = object : CanvasFetcher {
            val tokens = mutableListOf<String>()
            override fun fetch(trackId: String, token: String): CanvasFetch {
                tokens.add(token)
                return if (token == "stale") CanvasFetch.Unauthorized
                else CanvasFetch.Success(CanvasResult.Found("u1"))
            }
        }
        var forced = false
        val c = controller(s, fetcher, this, dispatcher, token = { force ->
            forced = force; if (force) "fresh" else "stale"
        })
        c.start()
        s.dispatch(playing("t1"))
        advanceUntilIdle()
        assertEquals(CanvasState.Found("u1"), c.state)
        assertEquals(listOf("stale", "fresh"), fetcher.tokens)
        assertEquals(true, forced)
        c.stop()
    }

    @Test fun pauseReleasesCanvasEvenWithSameTrack() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher(mutableMapOf("t1" to CanvasFetch.Success(CanvasResult.Found("u1"))))
        val c = controller(s, fetcher, this, dispatcher)
        c.start()
        s.dispatch(playing("t1"))
        advanceUntilIdle()
        assertEquals(CanvasState.Found("u1"), c.state)
        // Same track, now paused — the canvas must drop to None (codec release per spec).
        s.dispatch(paused("t1"))
        advanceUntilIdle()
        assertEquals(CanvasState.None, c.state)
        c.stop()
    }

    @Test fun reevaluateAfterToggleOffHidesCanvas() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher(mutableMapOf("t1" to CanvasFetch.Success(CanvasResult.Found("u1"))))
        var enabled = true
        val c = CanvasController(
            store = s, fetcher = fetcher, tokenProvider = { "tok" },
            isEnabled = { enabled }, scope = this, ioDispatcher = dispatcher, debounceMs = 250L,
        )
        c.start()
        s.dispatch(playing("t1"))
        advanceUntilIdle()
        assertEquals(CanvasState.Found("u1"), c.state)
        // User flips the toggle off in settings (no store event) then returns: reevaluate() hides it.
        enabled = false
        c.reevaluate()
        advanceUntilIdle()
        assertEquals(CanvasState.None, c.state)
        c.stop()
    }

    @Test fun stopReleasesListenerAndResetsToNone() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val s = store()
        val fetcher = FakeFetcher(mutableMapOf("t1" to CanvasFetch.Success(CanvasResult.Found("u1"))))
        val seen = mutableListOf<CanvasState>()
        val c = controller(s, fetcher, this, dispatcher)
        c.addListener { seen.add(it) }
        c.start()
        c.stop()
        seen.clear()
        s.dispatch(playing("t1")) // after stop, no further callbacks
        advanceUntilIdle()
        assertEquals(emptyList<CanvasState>(), seen)
        assertEquals(CanvasState.None, c.state)
    }
}
