package dev.rusty.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImmichSlideshowControllerTest {

    private fun asset(id: String, portrait: Boolean = false) =
        ImmichAsset(id, portrait, null, null, emptyList())

    private class Recorder : SlideshowController.Listener {
        val slides = mutableListOf<Slide>()
        val statuses = mutableListOf<SlideshowStatus>()
        override fun onSlide(slide: Slide) { slides.add(slide) }
        override fun onStatus(status: SlideshowStatus) { statuses.add(status) }
    }

    @Test fun advancesOnIntervalMeasuredFromCrossfadeEnd() = runTest {
        val rec = Recorder()
        val controller = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(asset("a"), asset("b"), asset("c"),
                asset("d"), asset("e"), asset("f"))) },
            prefetch = { true },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = rec,
        )
        controller.start()
        advanceTimeBy(1)                       // first slide is immediate
        assertEquals(listOf("a"), rec.slides.map { it.primary.id })
        advanceTimeBy(SlideshowController.CROSSFADE_MS + 10_001L)  // crossfade + interval (+1: advanceTimeBy doesn't run tasks scheduled exactly at the boundary)
        assertEquals(listOf("a", "b"), rec.slides.map { it.primary.id })
        controller.stop()
        advanceTimeBy(60_000L)
        assertEquals(2, rec.slides.size)       // stop() halts the loop
    }

    @Test fun startIsIdempotentAndRestartsAfterStop() = runTest {
        val rec = Recorder()
        val controller = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(asset("a"), asset("b"))) },
            prefetch = { true },
            scope = backgroundScope,
            intervalMs = { 1_000L },
            splitView = { false },
            listener = rec,
        )
        controller.start(); controller.start()  // second start is a no-op
        advanceTimeBy(1)
        assertEquals(1, rec.slides.size)
        controller.stop(); controller.stop()    // second stop is a no-op
        controller.start()                      // restartable on the same instance
        advanceTimeBy(1)
        assertEquals(2, rec.slides.size)
    }

    @Test fun failedPrimaryPrefetchSkipsAssetFailedSecondaryDegradesToSolo() = runTest {
        val rec = Recorder()
        val controller = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(
                asset("bad"), asset("p1", true), asset("p2broken", true), asset("l1"))) },
            prefetch = { it.id != "bad" && it.id != "p2broken" },
            scope = backgroundScope,
            intervalMs = { 1_000L },
            splitView = { true },
            listener = rec,
        )
        controller.start()
        advanceTimeBy(1)
        // "bad" skipped entirely; p1 paired with p2broken but the pair degrades to solo p1.
        assertEquals("p1", rec.slides.first().primary.id)
        assertEquals(null, rec.slides.first().secondary)
        controller.stop()
    }

    @Test fun authErrorEmitsStatusAndRetriesWithBackoff() = runTest {
        var calls = 0
        val rec = Recorder()
        val controller = SlideshowController(
            fetchBatch = { calls++; ImmichResult.Error(ImmichErrorKind.AUTH) },
            prefetch = { true },
            scope = backgroundScope,
            intervalMs = { 1_000L },
            splitView = { false },
            listener = rec,
        )
        controller.start()
        advanceTimeBy(1)
        assertTrue(rec.statuses.contains(SlideshowStatus.Auth))
        val callsAfterFirst = calls
        advanceTimeBy(5_001L)      // first backoff step (+1 for the boundary)
        assertTrue(calls > callsAfterFirst)
        advanceTimeBy(200_000L)    // backoff is capped, keeps retrying, never crashes
        assertTrue(calls > callsAfterFirst + 1)
        assertEquals(0, rec.slides.size)
        controller.stop()
    }

    @Test fun emptyLibraryEmitsNoPhotosAndRecoversWhenPhotosAppear() = runTest {
        var batches = 0
        val rec = Recorder()
        val controller = SlideshowController(
            fetchBatch = {
                batches++
                if (batches == 1) ImmichResult.Ok(emptyList())
                else ImmichResult.Ok(listOf(asset("a"), asset("b")))
            },
            prefetch = { true },
            scope = backgroundScope,
            intervalMs = { 1_000L },
            splitView = { false },
            listener = rec,
        )
        controller.start()
        advanceTimeBy(1)
        assertTrue(rec.statuses.contains(SlideshowStatus.NoPhotos))
        advanceTimeBy(60_001L)     // empty-retry delay elapses, second batch has photos
        assertTrue(rec.slides.isNotEmpty())
        assertEquals(SlideshowStatus.Showing, rec.statuses.last())
        controller.stop()
    }

    /**
     * A healthy API paired with permanently failing image prefetches must NOT hot-loop: without a
     * throttle every asset fails, the queue drains, a fresh batch is fetched immediately and the
     * queue's starvation rule re-admits the same failing assets — unbounded network calls on an
     * always-on device.
     */
    @Test fun permanentlyFailingPrefetchThrottlesInsteadOfSpinning() = runTest {
        var fetches = 0
        var prefetches = 0
        val rec = Recorder()
        val controller = SlideshowController(
            fetchBatch = {
                fetches++
                ImmichResult.Ok(listOf(asset("a"), asset("b"), asset("c"),
                    asset("d"), asset("e"), asset("f")))
            },
            prefetch = { prefetches++; false },
            scope = backgroundScope,
            intervalMs = { 1_000L },
            splitView = { false },
            listener = rec,
        )
        controller.start()
        advanceTimeBy(300_000L)    // five minutes of virtual time
        assertTrue("prefetch spun: $prefetches calls", prefetches < 30)
        assertTrue("fetchBatch spun: $fetches calls", fetches < 30)
        assertEquals(0, rec.slides.size)   // nothing decoded, so nothing is ever announced
        controller.stop()
    }

    /**
     * The class doc promises "no callback outlives the loop's Job." Job.cancel() is asynchronous
     * and only takes effect at the loop's next suspension point, so an injected lambda that
     * swallows CancellationException (very plausible once a network call is wrapped in a broad
     * try/catch) must not let the loop sneak a callback out after stop() has returned.
     */
    @Test fun stopPreventsCallbacksAfterCancellationIsSwallowed() = runTest {
        val rec = Recorder()
        val prefetch: suspend (ImmichAsset) -> Boolean = {
            try {
                delay(1_000L)
                true
            } catch (e: CancellationException) {
                true  // mirrors a broad catch around a network call swallowing cancellation
            }
        }
        val controller = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(asset("a"), asset("b"))) },
            prefetch = prefetch,
            scope = backgroundScope,
            intervalMs = { 1_000L },
            splitView = { false },
            listener = rec,
        )
        controller.start()
        advanceTimeBy(1)          // loop reaches prefetch and suspends inside its delay
        assertEquals(0, rec.slides.size)
        controller.stop()         // cancel while prefetch is mid-flight
        runCurrent()              // let the swallowed CancellationException resume prefetch
        advanceTimeBy(60_000L)    // confirm nothing fires even as virtual time keeps moving
        assertEquals(0, rec.slides.size)
        assertEquals(0, rec.statuses.size)
    }

    /** Builds a controller over a fixed asset list with all-succeeding prefetch. */
    private fun TestScope.controllerOver(
        assets: List<ImmichAsset>,
        rec: Recorder,
        intervalMs: Long = 10_000L,
        splitView: Boolean = false,
        onFetch: () -> Unit = {},
    ) = SlideshowController(
        fetchBatch = { onFetch(); ImmichResult.Ok(assets) },
        prefetch = { true },
        scope = backgroundScope,
        intervalMs = { intervalMs },
        splitView = { splitView },
        listener = rec,
    )

    private val manyAssets = (1..10).map { asset("a$it") }

    @Test fun pauseParksTheLoopAndResumeStartsAFreshDwell() = runTest {
        val rec = Recorder()
        var fetches = 0
        val c = controllerOver(manyAssets, rec, onFetch = { fetches++ })
        c.start()
        advanceTimeBy(1)
        assertEquals(1, rec.slides.size)
        val fetchesAtPause = fetches

        c.pause()
        advanceTimeBy(1)
        assertTrue(c.isPaused)
        advanceTimeBy(10 * 60_000L)                 // ten minutes parked
        assertEquals(1, rec.slides.size)            // no advance
        assertEquals(fetchesAtPause, fetches)       // and no network traffic

        c.resume()
        advanceTimeBy(1)
        assertFalse(c.isPaused)
        assertEquals(1, rec.slides.size)            // resume shows the current photo for a fresh dwell
        advanceTimeBy(SlideshowController.CROSSFADE_MS + 10_001L)
        assertEquals(2, rec.slides.size)            // then advances normally
        c.stop()
    }

    @Test fun nextInterruptsTheDwellInsteadOfQueueingBehindIt() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        assertEquals(listOf("a1"), rec.slides.map { it.primary.id })

        c.next()
        advanceTimeBy(1)                            // well inside the 12 s dwell
        assertEquals(listOf("a1", "a2"), rec.slides.map { it.primary.id })
        c.stop()
    }

    @Test fun repeatedNextAdvancesOncePerTapRatherThanCollapsing() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.next(); c.next(); c.next()
        advanceTimeBy(1)
        assertEquals(listOf("a1", "a2", "a3", "a4"), rec.slides.map { it.primary.id })
        c.stop()
    }

    @Test fun nextWhilePausedMovesOneSlideAndStaysPaused() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.pause()
        advanceTimeBy(1)

        c.next()
        advanceTimeBy(1)
        assertEquals(listOf("a1", "a2"), rec.slides.map { it.primary.id })
        assertTrue(c.isPaused)
        advanceTimeBy(10 * 60_000L)
        assertEquals(2, rec.slides.size)             // still parked after the manual step
        c.stop()
    }

    @Test fun previousReAnnouncesPriorSlideAndNextWalksForwardThroughHistory() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.next(); c.next()
        advanceTimeBy(1)
        assertEquals(listOf("a1", "a2", "a3"), rec.slides.map { it.primary.id })

        c.previous()
        advanceTimeBy(1)
        assertEquals("a2", rec.slides.last().primary.id)
        c.previous()
        advanceTimeBy(1)
        assertEquals("a1", rec.slides.last().primary.id)

        c.next()                                     // inside history: step forward, not a new photo
        advanceTimeBy(1)
        assertEquals("a2", rec.slides.last().primary.id)
        c.stop()
    }

    @Test fun previousAtTheOldestEndIsANoOp() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        assertEquals(1, rec.slides.size)

        c.previous(); c.previous(); c.previous()     // nothing older than the first slide
        advanceTimeBy(1)
        assertEquals(1, rec.slides.size)
        assertEquals("a1", rec.slides.last().primary.id)
        c.stop()
    }

    @Test fun dwellInsideHistoryWalksForwardBeforeResumingLive() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.next()
        advanceTimeBy(1)
        c.previous()
        advanceTimeBy(1)
        assertEquals("a1", rec.slides.last().primary.id)

        // Left alone: the dwell walks forward through history first...
        advanceTimeBy(SlideshowController.CROSSFADE_MS + 10_001L)
        assertEquals("a2", rec.slides.last().primary.id)
        // ...then continues live.
        advanceTimeBy(SlideshowController.CROSSFADE_MS + 10_001L)
        assertEquals("a3", rec.slides.last().primary.id)
        c.stop()
    }

    @Test fun displayedHistoryIsBoundedAndDropsTheOldest() = runTest {
        val rec = Recorder()
        val c = controllerOver((1..40).map { asset("a$it") }, rec)
        c.start()
        advanceTimeBy(1)
        repeat(30) { c.next(); advanceTimeBy(1) }    // 31 slides displayed, cap is 20
        val newest = rec.slides.last().primary.id

        repeat(25) { c.previous(); advanceTimeBy(1) }
        // Walked back at most (cap - 1) steps from the newest, never past the evicted head.
        val oldestReachable = rec.slides.last().primary.id
        assertEquals("a12", oldestReachable)         // a31 newest, 19 steps back
        assertEquals("a31", newest)
        c.stop()
    }

    @Test fun historyRecordsTheDegradedPairNotTheRequestedOne() = runTest {
        val rec = Recorder()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(
                asset("p1", true), asset("p2", true), asset("p3", true), asset("p4", true))) },
            prefetch = { it.id != "p2" },            // pair p1+p2 degrades to solo p1
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { true },
            listener = rec,
        )
        c.start()
        advanceTimeBy(1)
        c.next()
        advanceTimeBy(1)
        c.previous()
        advanceTimeBy(1)
        // Going back reproduces exactly what was on screen: p1 solo, not the p1+p2 pair.
        assertEquals("p1", rec.slides.last().primary.id)
        assertNull(rec.slides.last().secondary)
        c.stop()
    }

    @Test fun failedPrimaryPrefetchDoesNotBlacklistTheUntriedPartner() = runTest {
        val rec = Recorder()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(asset("bad", true), asset("good", true))) },
            prefetch = { it.id != "bad" },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { true },
            listener = rec,
        )
        c.start()
        advanceTimeBy(1)
        // "bad" was pulled as primary with "good" as its partner. "good" was never decoded, so it
        // must come back rather than being buried in the dedupe ring for the next 50 slides.
        assertEquals("good", rec.slides.single().primary.id)
        c.stop()
    }

    @Test fun commandsSentWhileStoppedAreDroppedOnRestart() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.stop()
        c.next(); c.next()                           // taps on a dead loop
        c.start()
        advanceTimeBy(1)
        // Restart shows exactly one new slide, not three: the stale taps were discarded.
        assertEquals(listOf("a1", "a2"), rec.slides.map { it.primary.id })
        c.stop()
    }

    @Test fun commandSentRightAfterStartIsHonouredNotDrained() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        c.next()                                     // tapped before the loop coroutine is dispatched
        advanceTimeBy(1)
        assertEquals(listOf("a1", "a2"), rec.slides.map { it.primary.id })
        c.stop()
    }

    /**
     * The drain in start() sits AFTER the job?.isActive early-return guard on purpose: it exists
     * to discard taps issued while stopped, not to police an already-running loop. If the drain
     * were hoisted above the guard, a redundant start() on a live loop would swallow a command the
     * user just issued — reintroducing the bug the drain was moved to fix.
     *
     * A command sent while the loop is parked in dwell()'s commands.receive() hands off straight
     * to that waiting receiver and never touches the buffer, so it can't expose a wrongly-placed
     * drain. To land the command IN the buffer, this test catches the loop mid-prefetch (a real
     * suspension point that isn't commands.receive()) before firing the redundant start().
     */
    @Test fun redundantStartOnARunningLoopDoesNotDrainAPendingCommand() = runTest {
        val rec = Recorder()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(listOf(asset("a"), asset("b"))) },
            prefetch = { delay(1_000L); true },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = rec,
        )
        c.start()
        advanceTimeBy(1)                 // loop is mid-prefetch (running, but not on commands.receive())
        assertEquals(0, rec.slides.size)

        c.next()                         // lands in the buffer: nobody is receiving right now
        c.start()                        // redundant start() on the already-running loop
        advanceTimeBy(1_001L)            // prefetch("a") completes, first slide announces
        assertEquals(listOf("a"), rec.slides.map { it.primary.id })

        // The buffered NEXT, if it survived, makes dwell() return immediately so the loop pulls
        // "b" right away — which needs its own prefetch delay to actually announce.
        advanceTimeBy(1_001L)
        assertEquals(listOf("a", "b"), rec.slides.map { it.primary.id })
        c.stop()
    }

    @Test fun stopWhilePausedLeavesNoParkedCoroutine() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.pause()
        advanceTimeBy(1)
        c.stop()
        c.start()                                    // restartable after a paused stop
        advanceTimeBy(1)
        assertTrue(rec.slides.size >= 2)
        c.stop()
    }

    /**
     * isPaused deliberately survives stop()/start(): same viewing session, an activity
     * pause/resume must not silently un-pause. A play/pause button rendered from isPaused on
     * mount depends on this — if a restart cleared the flag, the button would show "playing"
     * over a slideshow that is actually still frozen.
     */
    @Test fun isPausedSurvivesAStopStartCycle() = runTest {
        val rec = Recorder()
        val c = controllerOver(manyAssets, rec)
        c.start()
        advanceTimeBy(1)
        c.pause()
        advanceTimeBy(1)
        assertTrue(c.isPaused)
        c.stop()
        assertTrue(c.isPaused)                       // stop() must not clear the flag
        c.start()
        advanceTimeBy(1)
        assertTrue(c.isPaused)                       // nor does a restart
        c.stop()
    }

    // ---- Screen-off suppression (remote-control fake-off) -------------------

    /**
     * Suppression is the screen renderer's gate, not a second pause button: while the panel is
     * faked off there is nobody to look at a photo, so the loop must stop before the batch fetch
     * (mobile data / server load) as well as before the decode (CPU, cache churn).
     */
    @Test fun suppressionParksTheLoopWithoutFetchingOrAdvancing() = runTest {
        val rec = Recorder()
        var fetches = 0
        val c = controllerOver(manyAssets, rec, onFetch = { fetches++ })
        c.start()
        advanceTimeBy(1)
        assertEquals(1, rec.slides.size)
        val fetchesAtSuppress = fetches

        c.setSuppressed(true)
        advanceTimeBy(10 * 60_000L)                 // ten minutes with the screen faked off
        assertEquals(1, rec.slides.size)            // no advance
        assertEquals(fetchesAtSuppress, fetches)    // and no network traffic

        c.setSuppressed(false)
        advanceTimeBy(1)
        assertEquals(2, rec.slides.size)            // the wake resumes the slideshow at once
        c.stop()
    }

    /**
     * A suppression landing while a batch fetch is in flight must still stop the loop before it
     * decodes anything: the fetch was already paid for, the decode has not been.
     */
    @Test fun suppressionLandingMidFetchStopsBeforeTheDecode() = runTest {
        val rec = Recorder()
        var prefetches = 0
        val c = SlideshowController(
            fetchBatch = { delay(1_000L); ImmichResult.Ok(manyAssets) },
            prefetch = { prefetches++; true },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = rec,
        )
        c.start()
        advanceTimeBy(500L)                         // inside the in-flight fetch
        c.setSuppressed(true)
        advanceTimeBy(10 * 60_000L)                 // the batch lands, but nothing may be decoded
        assertEquals(0, prefetches)
        assertEquals(0, rec.slides.size)

        c.setSuppressed(false)
        advanceTimeBy(1)
        assertEquals(1, rec.slides.size)
        c.stop()
    }

    /**
     * Suppression and the user's manual pause are independent: waking the screen must never
     * resume a slideshow the user had deliberately paused before the screen went off.
     */
    @Test fun clearingSuppressionDoesNotClearAManualPause() = runTest {
        val rec = Recorder()
        var fetches = 0
        val c = controllerOver(manyAssets, rec, onFetch = { fetches++ })
        c.start()
        advanceTimeBy(1)
        c.pause()
        advanceTimeBy(1)
        val fetchesAtPause = fetches

        c.setSuppressed(true)
        advanceTimeBy(1)
        c.setSuppressed(false)                      // screen back on, but the pause stands
        advanceTimeBy(10 * 60_000L)
        assertTrue(c.isPaused)
        assertEquals(1, rec.slides.size)
        assertEquals(fetchesAtPause, fetches)
        c.stop()
    }

    /** …and the converse: clearing the pause while suppressed must not light the loop back up. */
    @Test fun manualPauseAndResumeUnderSuppressionStaysSuppressed() = runTest {
        val rec = Recorder()
        var fetches = 0
        val c = controllerOver(manyAssets, rec, onFetch = { fetches++ })
        c.start()
        advanceTimeBy(1)
        c.setSuppressed(true)
        advanceTimeBy(1)
        val fetchesAtSuppress = fetches

        c.pause()
        advanceTimeBy(1)
        c.resume()                                  // user un-pauses while the panel is dark
        advanceTimeBy(10 * 60_000L)
        assertFalse(c.isPaused)
        assertEquals(1, rec.slides.size)            // still parked: suppression outranks the pause
        assertEquals(fetchesAtSuppress, fetches)
        c.stop()
    }

    // ---- Stepping back re-decodes before it re-announces --------------------

    /** Interleaved log of prefetch calls and announcements, so ORDER can be asserted. */
    private class Trace {
        val events = mutableListOf<String>()
        val recorder = object : SlideshowController.Listener {
            override fun onSlide(slide: Slide) {
                events += "slide:" + slide.primary.id + (slide.secondary?.let { "+" + it.id } ?: "")
            }
            override fun onStatus(status: SlideshowStatus) {}
        }
        val slides: List<String> get() = events.filter { it.startsWith("slide:") }
    }

    /**
     * The "no blank panes" contract rests on every image being decoded BEFORE onSlide fires: the
     * theme binds the back layer asynchronously and immediately crossfades, so an undecoded slide
     * fades a stale photo in first. reAnnounce() used to skip the prefetch entirely.
     */
    @Test fun previousPrefetchesTheSlideBeforeAnnouncingIt() = runTest {
        val t = Trace()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(manyAssets) },
            prefetch = { t.events += "prefetch:" + it.id; true },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = t.recorder,
        )
        c.start()
        advanceTimeBy(1)
        c.next()
        advanceTimeBy(1)
        assertEquals(listOf("slide:a1", "slide:a2"), t.slides)

        val before = t.events.size
        c.previous()
        advanceTimeBy(1)
        val stepBack = t.events.drop(before)
        assertEquals("stepping back must decode then announce, got $stepBack",
            listOf("prefetch:a1", "slide:a1"), stepBack)
        c.stop()
    }

    /** A split pair must have BOTH panes decoded before it is re-announced. */
    @Test fun previousPrefetchesBothPanesOfAPair() = runTest {
        val t = Trace()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok((1..8).map { asset("p$it", portrait = true) }) },
            prefetch = { t.events += "prefetch:" + it.id; true },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { true },
            listener = t.recorder,
        )
        c.start()
        advanceTimeBy(1)
        c.next()
        advanceTimeBy(1)
        assertEquals(listOf("slide:p1+p2", "slide:p3+p4"), t.slides)

        val before = t.events.size
        c.previous()
        advanceTimeBy(1)
        assertEquals(listOf("prefetch:p1", "prefetch:p2", "slide:p1+p2"), t.events.drop(before))
        c.stop()
    }

    /**
     * The asset was evicted from the caches and is gone from the server. Stepping back must keep
     * the current photo up rather than announcing a slide it cannot decode — and must not spin:
     * one prefetch attempt per press, with the cursor left where the screen actually is.
     */
    @Test fun previousWhoseAssetNoLongerDecodesKeepsTheCurrentPhotoUp() = runTest {
        val t = Trace()
        val dead = mutableSetOf<String>()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(manyAssets) },
            prefetch = { t.events += "prefetch:" + it.id; it.id !in dead },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = t.recorder,
        )
        c.start()
        advanceTimeBy(1)
        c.next()
        advanceTimeBy(1)
        assertEquals(listOf("slide:a1", "slide:a2"), t.slides)

        dead += "a1"                                  // a1 vanishes from the server after display
        c.previous()
        advanceTimeBy(1)
        assertEquals("must not announce an undecodable slide",
            listOf("slide:a1", "slide:a2"), t.slides)
        val attempts = t.events.count { it == "prefetch:a1" }
        c.previous(); c.previous()
        advanceTimeBy(1)
        assertEquals("one attempt per press, no retry spin",
            attempts + 2, t.events.count { it == "prefetch:a1" })
        assertEquals(listOf("slide:a1", "slide:a2"), t.slides)

        // Not stalled: the dwell still expires and the slideshow advances live from where it is.
        advanceTimeBy(SlideshowController.CROSSFADE_MS + 10_001L)
        assertEquals("slide:a3", t.slides.last())
        c.stop()
    }

    /**
     * Walking FORWARD through history onto a dead asset must not park the slideshow on it forever:
     * the dwell would retry the same failing entry every interval and never reach the live edge.
     */
    @Test fun forwardThroughHistoryOntoADeadAssetReturnsToTheLiveEdge() = runTest {
        val t = Trace()
        val dead = mutableSetOf<String>()
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(manyAssets) },
            prefetch = { t.events += "prefetch:" + it.id; it.id !in dead },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = t.recorder,
        )
        c.start()
        advanceTimeBy(1)
        c.next(); c.next()
        advanceTimeBy(1)
        c.previous(); c.previous()
        advanceTimeBy(1)
        assertEquals("slide:a1", t.slides.last())     // cursor is two steps back

        dead += "a2"                                  // the next step forward is gone
        c.next()
        advanceTimeBy(1)
        // Abandons the broken history tail and pulls a NEW live slide instead of stalling on a2.
        assertEquals("slide:a4", t.slides.last())
        c.stop()
    }

    /** stop() during a step-back prefetch must cancel the loop, not be swallowed as a failure. */
    @Test fun stopDuringAStepBackPrefetchCancelsTheLoop() = runTest {
        val rec = Recorder()
        var slow = false
        val c = SlideshowController(
            fetchBatch = { ImmichResult.Ok(manyAssets) },
            prefetch = { if (slow) delay(1_000L); true },
            scope = backgroundScope,
            intervalMs = { 10_000L },
            splitView = { false },
            listener = rec,
        )
        c.start()
        advanceTimeBy(1)
        c.next()
        advanceTimeBy(1)
        assertEquals(2, rec.slides.size)

        slow = true
        c.previous()
        advanceTimeBy(1)                              // parked inside the step-back prefetch
        assertEquals(2, rec.slides.size)
        c.stop()
        advanceTimeBy(60_000L)
        assertEquals("cancellation must propagate out of the step-back prefetch",
            2, rec.slides.size)
    }
}
