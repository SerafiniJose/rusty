package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTakeoverCoordinatorTest {

    private val synchronousPoster = MainPoster { it.run() }
    private var nowMs = 0L
    private val allOn = TakeoverToggles(switchPage = true, showOnPlayback = true)

    private fun store(): ReceiverStateStore = ReceiverStateStore(
        ReceiverDashboardState.waiting("A"),
        synchronousPoster,
        MonotonicClock { nowMs },
    )

    private fun playing() = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "A",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING,
            trackTitle = "t", trackArtist = "a", coverArtUrl = null,
            trackId = "id1", sessionUser = "u", elapsedMs = 0L, durationMs = 1L,
        ),
    )

    private fun stopped() = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "A",
            playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.STOPPED,
        ),
    )

    private class Recorder {
        val wakes = mutableListOf<Boolean>()
        var launches = 0
        var pageSwitches = 0
    }

    private fun coordinator(
        store: ReceiverStateStore,
        rec: Recorder,
        toggles: TakeoverToggles = allOn,
        canDrawOverlays: Boolean = true,
        screenOn: Boolean = true,
    ): PlaybackTakeoverCoordinator = PlaybackTakeoverCoordinator(
        store = store,
        clock = MonotonicClock { nowMs },
        toggles = { toggles },
        canDrawOverlays = { canDrawOverlays },
        screenDesiredOn = { screenOn },
        wakeScreen = { fakeOff -> rec.wakes += fakeOff },
        launchHome = { rec.launches++ },
    ).also { it.start() }

    /** Coordinator constructed at nowMs=0; jump past the startup grace before casting. */
    private fun pastGrace() { nowMs = PlaybackTakeover.STARTUP_GRACE_MS + 1_000L }

    @Test fun edgeFiresAllConfiguredActions() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec)
        coord.attachPageConsumer { rec.pageSwitches++ }
        pastGrace()
        store.dispatch(playing())
        assertEquals(1, rec.pageSwitches)
        assertEquals(1, rec.launches)
        assertEquals(listOf(false), rec.wakes) // screen on → not the fake-off wake path
    }

    @Test fun pageSwitchIsPendingUntilConsumerAttaches_andConsumesOnce() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec, toggles = TakeoverToggles(true, false))
        pastGrace()
        store.dispatch(playing())
        assertEquals(0, rec.pageSwitches) // nobody resumed → pending
        coord.attachPageConsumer { rec.pageSwitches++ }
        assertEquals(1, rec.pageSwitches) // delivered on attach
        coord.detachPageConsumer()
        coord.attachPageConsumer { rec.pageSwitches++ }
        assertEquals(1, rec.pageSwitches) // consume-once: no re-delivery
    }

    @Test fun startupGraceSuppressesEdgesRightAfterProcessStart() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec)
        coord.attachPageConsumer { rec.pageSwitches++ }
        nowMs = PlaybackTakeover.STARTUP_GRACE_MS - 1_000L
        store.dispatch(playing())
        assertEquals(0, rec.pageSwitches)
        assertEquals(0, rec.launches)
        assertEquals(0, rec.wakes.size)
    }

    @Test fun quickIdleExcursionIsDebounced() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec)
        coord.attachPageConsumer { rec.pageSwitches++ }
        pastGrace()
        store.dispatch(playing())      // fires (1)
        nowMs += 60_000L
        store.dispatch(stopped())      // leaves ACTIVE
        nowMs += PlaybackTakeover.REENTRY_DEBOUNCE_MS - 1_000L
        store.dispatch(playing())      // too soon → suppressed
        assertEquals(1, rec.pageSwitches)
        nowMs += 60_000L
        store.dispatch(stopped())
        nowMs += PlaybackTakeover.REENTRY_DEBOUNCE_MS + 1_000L
        store.dispatch(playing())      // long enough idle → fires (2)
        assertEquals(2, rec.pageSwitches)
    }

    @Test fun registrationOntoAnAlreadyActiveStoreIsNoEdge() {
        val store = store()
        nowMs = PlaybackTakeover.STARTUP_GRACE_MS + 60_000L
        store.dispatch(playing()) // already playing before the coordinator exists
        val rec = Recorder()
        val coord = coordinator(store, rec)
        coord.attachPageConsumer { rec.pageSwitches++ }
        assertEquals(0, rec.pageSwitches)
        assertEquals(0, rec.launches)
    }

    @Test fun fakeOffWakeUsesTheFakeOffPath() {
        val store = store()
        val rec = Recorder()
        coordinator(store, rec, screenOn = false)
        pastGrace()
        store.dispatch(playing())
        assertEquals(listOf(true), rec.wakes) // fake-off active → model wake path
        assertEquals(1, rec.launches)         // wake toggle on → launch allowed
    }

    @Test fun noOverlayPermissionMeansNoLaunch() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec, canDrawOverlays = false)
        coord.attachPageConsumer { rec.pageSwitches++ }
        pastGrace()
        store.dispatch(playing())
        assertEquals(0, rec.launches)
        assertEquals(1, rec.pageSwitches) // page toggle is on independently
    }

    @Test fun throwingWakeScreenDoesNotPreventLaunchOrPropagate() {
        val store = store()
        val rec = Recorder()
        val coord = PlaybackTakeoverCoordinator(
            store = store,
            clock = MonotonicClock { nowMs },
            toggles = { allOn },
            canDrawOverlays = { true },
            screenDesiredOn = { true },
            wakeScreen = { throw RuntimeException("boom") },
            launchHome = { rec.launches++ },
        ).also { it.start() }
        coord.attachPageConsumer { rec.pageSwitches++ }
        pastGrace()
        store.dispatch(playing()) // must not throw out of dispatch
        assertEquals(1, rec.launches)
        assertEquals(1, rec.pageSwitches)
    }

    @Test fun throwingAttachedConsumerDoesNotPropagateOrBlockOtherActions() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec)
        coord.attachPageConsumer { throw RuntimeException("boom") }
        pastGrace()
        store.dispatch(playing()) // must not throw out of dispatch
        assertEquals(1, rec.launches)
        assertEquals(listOf(false), rec.wakes)
    }

    @Test fun throwingPendingConsumerStillConsumesTheFlagOnce() {
        val store = store()
        val rec = Recorder()
        val coord = coordinator(store, rec, toggles = TakeoverToggles(true, false))
        pastGrace()
        store.dispatch(playing()) // nobody attached → pending
        assertEquals(0, rec.pageSwitches)
        coord.attachPageConsumer { throw RuntimeException("boom") } // must not throw out
        coord.detachPageConsumer()
        coord.attachPageConsumer { rec.pageSwitches++ }
        assertEquals(0, rec.pageSwitches) // consumed by the throwing delivery: no replay
    }
}
