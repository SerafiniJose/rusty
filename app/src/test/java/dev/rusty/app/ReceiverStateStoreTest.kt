package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ReceiverStateStoreTest {

    private val synchronousPoster = MainPoster { it.run() }

    private fun store(
        poster: MainPoster = synchronousPoster,
        clock: MonotonicClock = MonotonicClock { 0L },
        initial: ReceiverDashboardState = ReceiverDashboardState.waiting("A"),
    ): ReceiverStateStore = ReceiverStateStore(initial, poster, clock)

    // CONNECTED reduces to status == "Connected" — a stable, single value used to detect torn reads.
    private fun connectedEvent(): ReceiverEvent.Status =
        ReceiverEvent.Status(
            ReceiverDashboardStatusEvent(
                receiverName = "A",
                lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
            )
        )

    private fun stoppedEvent(): ReceiverEvent.Status =
        ReceiverEvent.Status(
            ReceiverDashboardStatusEvent(
                receiverName = "A",
                lifecycle = ReceiverDashboardStatusEvent.Lifecycle.STOPPED,
            )
        )

    private fun playbackEvent(
        elapsedMs: Long,
        durationMs: Long = 200_000L,
        playing: Boolean = true,
    ): ReceiverEvent.Playback = ReceiverEvent.Playback(
        ReceiverDashboardPlaybackEvent(
            receiverName = "A",
            playbackState = if (playing) {
                ReceiverDashboardPlaybackEvent.PlaybackState.PLAYING
            } else {
                ReceiverDashboardPlaybackEvent.PlaybackState.PAUSED
            },
            trackTitle = "Song",
            trackArtist = "Artist",
            elapsedMs = elapsedMs,
            durationMs = durationMs,
        )
    )

    @Test fun listenerReceivesStrictlyIncreasingRevisionsNeverSkips() {
        val s = store()
        val seen = mutableListOf<Long>()
        s.addListener { seen.add(it.revision) }
        repeat(10) { s.dispatch(connectedEvent()) }
        // initial delivery (rev 0) + 10 dispatches
        assertEquals((0L..10L).toList(), seen)
    }

    @Test fun eightThreadsFiftyDispatchesAdvanceExactly400AndDeliveredSorted() {
        repeat(5) { iteration ->
            val executor = Executors.newSingleThreadExecutor()
            try {
                val s = store(poster = MainPoster { executor.execute(it) })
                val delivered = Collections.synchronizedList(mutableListOf<Long>())
                val doneLatch = CountDownLatch(1)
                s.addListener {
                    delivered.add(it.revision)
                    // initial(0) + 400 dispatches => last revision 400
                    if (it.revision == 400L) doneLatch.countDown()
                }
                val start = CountDownLatch(1)
                val threads = (0 until 8).map {
                    Thread {
                        start.await()
                        repeat(50) { s.dispatch(connectedEvent()) }
                    }
                }
                threads.forEach { it.start() }
                start.countDown()
                threads.forEach { it.join() }
                assertTrue(
                    "iteration $iteration: drain did not finish",
                    doneLatch.await(10, TimeUnit.SECONDS)
                )
                // exactly 400 dispatches => final revision is 400
                assertEquals(400L, s.snapshot.revision)
                // delivered (snapshot under lock) is strictly sorted, no rev2 before rev1
                val copy = synchronized(delivered) { delivered.toList() }
                assertEquals(copy.sorted(), copy)
                assertEquals(copy.distinct(), copy)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun addListenerMidStormFirstSeenLeqEverySubsequent() {
        repeat(5) {
            val executor = Executors.newSingleThreadExecutor()
            try {
                val s = store(poster = MainPoster { executor.execute(it) })
                val seen = Collections.synchronizedList(mutableListOf<Long>())
                val start = CountDownLatch(1)
                val threads = (0 until 8).map {
                    Thread {
                        start.await()
                        repeat(50) { s.dispatch(connectedEvent()) }
                    }
                }
                threads.forEach { it.start() }
                start.countDown()
                // register mid-storm
                Thread.sleep(1)
                s.addListener { seen.add(it.revision) }
                threads.forEach { it.join() }
                // let the drain finish
                val flush = CountDownLatch(1)
                executor.execute { flush.countDown() }
                assertTrue(flush.await(10, TimeUnit.SECONDS))
                val copy = synchronized(seen) { seen.toList() }
                assertTrue("listener saw nothing", copy.isNotEmpty())
                val first = copy.first()
                assertTrue("first-seen overtaken", copy.all { it >= first })
                assertEquals(copy.sorted(), copy)
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test fun snapshotStateAndRevisionAlwaysCorrespondUnderContendedReads() {
        repeat(5) {
            val s = store()
            val failure = AtomicLong(-1L)
            val start = CountDownLatch(1)
            val writer = Thread {
                start.await()
                repeat(1000) { s.dispatch(connectedEvent()) }
            }
            val readers = (0 until 4).map {
                Thread {
                    start.await()
                    repeat(1000) {
                        val snap = s.snapshot
                        // status must be the reduced value for this content; never torn
                        if (snap.revision > 0 && snap.state.status != "Connected") {
                            failure.set(snap.revision)
                        }
                    }
                }
            }
            writer.start()
            readers.forEach { it.start() }
            start.countDown()
            writer.join()
            readers.forEach { it.join() }
            assertEquals("torn read at revision ${failure.get()}", -1L, failure.get())
        }
    }

    @Test fun dispatchPlaybackUpdatesStateAndAnchorInOneRevision() {
        var now = 1_000L
        val s = store(clock = { now })
        s.dispatch(playbackEvent(elapsedMs = 5_000L, playing = true))
        val snap = s.snapshot
        assertEquals("Playing", snap.state.status)
        assertEquals(5_000L, snap.anchor.elapsedMs)
        assertEquals(1_000L, snap.anchor.capturedRealtimeMs)
        assertTrue(snap.anchor.playing)
        // liveElapsedMs extrapolates while playing
        now = 4_000L
        assertEquals(5_000L + 3_000L, s.liveElapsedMs())
    }

    @Test fun pausedPlaybackDoesNotExtrapolate() {
        var now = 1_000L
        val s = store(clock = { now })
        s.dispatch(playbackEvent(elapsedMs = 5_000L, playing = false))
        now = 9_000L
        assertEquals(5_000L, s.liveElapsedMs())
    }

    @Test fun dispatchStatusWithServiceUpdatesDisplayAndServiceInOneRevision() {
        val s = store()
        val torn = CopyOnWriteArrayList<String>()
        s.addListener {
            // any snapshot carrying the new status MUST carry the new service together
            if (it.state.status == "Connected" && it.service != ReceiverServiceState.RUNNING) {
                torn.add("rev=${it.revision} status=${it.state.status} service=${it.service}")
            }
        }
        s.dispatch(connectedEvent(), service = ReceiverServiceState.RUNNING)
        assertTrue(torn.toString(), torn.isEmpty())
        assertEquals("Connected", s.snapshot.state.status)
        assertEquals(ReceiverServiceState.RUNNING, s.snapshot.service)
        assertEquals(1L, s.snapshot.revision)
    }

    @Test fun transitionServiceBumpsRevisionWithoutChangingState() {
        val s = store()
        val before = s.snapshot.state
        s.transitionService(ReceiverServiceState.STARTING)
        assertEquals(ReceiverServiceState.STARTING, s.snapshot.service)
        assertEquals(before, s.snapshot.state)
        assertEquals(1L, s.snapshot.revision)
    }

    @Test fun listenerReadingSnapshotInsideOnSnapshotDoesNotDeadlock() {
        val s = store()
        val seen = mutableListOf<Long>()
        s.addListener {
            // re-enter the store from within the callback
            val r = s.snapshot.revision
            seen.add(r)
        }
        s.dispatch(connectedEvent())
        assertFalse(seen.isEmpty())
    }

    /**
     * Regression: a listener registered while PRE-registration snapshots are still sitting in the
     * pending queue (not yet drained) must NOT replay that stale history. It may only see the
     * current snapshot (enqueued by addListener) and future ones.
     *
     * Reproduced deterministically with a manual poster: drains are queued and only run when we
     * call [ManualPoster.drainAll]. We dispatch rev1 (STOPPED) and rev2 (PLAYING/Connected) WITHOUT
     * draining, THEN addListener, THEN drain. The new listener must see ONLY rev2.
     *
     * On the OLD code (Wrapper seeded at -1L) the listener would accept rev1 then rev2 (both > -1),
     * replaying the stale STOPPED snapshot — so this assertion fails pre-fix.
     */
    @Test fun newListenerDoesNotReplayPreRegistrationPendingSnapshots() {
        repeat(5) {
            val manual = ManualPoster()
            val s = store(poster = manual)
            // rev1: STOPPED, rev2: CONNECTED — both committed and queued, but NOT drained yet.
            s.dispatch(stoppedEvent())
            s.dispatch(connectedEvent())
            // The current snapshot is now rev2 (Connected). Register while rev1/rev2 still pending.
            val seen = mutableListOf<Long>()
            val statuses = mutableListOf<String>()
            s.addListener {
                seen.add(it.revision)
                statuses.add(it.state.status)
            }
            // Now run every queued drain.
            manual.drainAll()
            // The new listener must NOT have seen rev1 (the pre-registration STOPPED snapshot).
            assertEquals("new listener replayed pre-registration history: $seen", listOf(2L), seen)
            assertEquals(listOf("Connected"), statuses)
        }
    }

    /** Poster that queues runnables and only executes them on demand (deterministic drain control). */
    private class ManualPoster : MainPoster {
        private val queue = ArrayDeque<Runnable>()
        override fun post(runnable: Runnable) {
            queue.add(runnable)
        }
        fun drainAll() {
            while (queue.isNotEmpty()) {
                queue.removeFirst().run()
            }
        }
    }

    @Test fun removedListenerStopsReceiving() {
        val s = store()
        val seen = mutableListOf<Long>()
        val l = ReceiverStateStore.Listener { seen.add(it.revision) }
        s.addListener(l)
        s.dispatch(connectedEvent())
        val sizeAfterOne = seen.size
        s.removeListener(l)
        s.dispatch(stoppedEvent())
        assertEquals(sizeAfterOne, seen.size)
    }
}
