package dev.rusty.app

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SlideshowStatus {
    /** Slides are flowing; the theme hides any status line. */
    object Showing : SlideshowStatus
    object Auth : SlideshowStatus
    object Unreachable : SlideshowStatus
    object NoPhotos : SlideshowStatus
}

/**
 * The slideshow engine (Android-free; mirrors CanvasController's start/stop + listener shape).
 * One loop coroutine: refill the queue when low, prefetch every image of the next slide BEFORE
 * announcing it (the current slide simply stays up while the next one isn't decoded — no blanks),
 * then wait crossfade + interval. Network errors keep the current photo up and retry with bounded
 * backoff (5 s doubling to 60 s); an empty filter result retries every 60 s. Cancellation via
 * [stop] is the staleness guard: no callback outlives the loop's Job. [pause]/[resume]/[next]/
 * [previous] drive the loop from the UI thread through a command channel; [setSuppressed] is the
 * screen renderer's own, separate park for a faked-off panel (see its KDoc for why it is not just
 * another pause).
 */
class SlideshowController(
    private val fetchBatch: suspend (Int) -> ImmichResult<List<ImmichAsset>>,
    private val prefetch: suspend (ImmichAsset) -> Boolean,
    private val scope: CoroutineScope,
    private val intervalMs: () -> Long,
    private val splitView: () -> Boolean,
    private val listener: Listener,
) {
    interface Listener {
        fun onSlide(slide: Slide)
        fun onStatus(status: SlideshowStatus)
    }

    /** What the loop should do when the dwell wait returns. */
    private enum class Command { NEXT, PREVIOUS, WAKE }

    private val queue = SlideQueue()
    private var job: Job? = null

    /**
     * Slides that were ACTUALLY announced, newest last. Distinct from [SlideQueue]'s dedupe
     * ring, which cannot back a "previous": that ring stores bare ids, records assets whose
     * prefetch FAILED (so they aren't retried in a loop), is cleared wholesale by the starvation
     * rule, and flattens a split pair into two independent ids. This deque stores the degraded
     * [Slide] exactly as the viewer saw it, so stepping back reproduces the real screen.
     * Touched only from the loop coroutine.
     */
    private val displayed = ArrayDeque<Slide>()

    /** Steps back from the newest displayed slide; 0 = live edge. Loop-coroutine only. */
    private var cursor = 0

    /**
     * Buffered, not conflated: conflation would collapse a [Command.NEXT] and a [Command.PREVIOUS]
     * into whichever landed last. DROP_OLDEST bounds the backlog when a user out-taps the crossfade.
     */
    private val commands = Channel<Command>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Pause is a flag, not a channel message: on the channel it could be dropped by DROP_OLDEST or
     * reordered behind a NEXT, leaving the flag and the loop disagreeing about whether slides
     * advance. Written from the main thread, read on the loop coroutine — hence @Volatile.
     */
    @Volatile
    private var pausedFlag = false

    val isPaused: Boolean get() = pausedFlag

    /** Idempotent; restartable after [stop] on the same instance (activity pause/resume cycles). */
    fun start() {
        if (job?.isActive == true) return
        // Drop taps that landed while stopped so a restart doesn't act on a stale one. Done HERE
        // rather than inside loop(): the loop body runs whenever its dispatcher gets around to it,
        // so a drain in there would also swallow commands issued right after start() returns.
        while (commands.tryReceive().isSuccess) { /* drain */ }
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Parks the slideshow: no advance, no fetch, no CPU, until [resume]/[next]/[previous]. */
    fun pause() {
        pausedFlag = true
        commands.trySend(Command.WAKE)
    }

    /** Resumes with a FRESH dwell on the current photo, then advances normally. */
    fun resume() {
        pausedFlag = false
        commands.trySend(Command.WAKE)
    }

    /**
     * Screen-off suppression, DELIBERATELY separate from [pausedFlag]: the remote-control API can
     * fake the panel off, and while it is dark nobody can see a photo, so the loop must stop. Two
     * flags rather than one because they answer to different owners — waking the screen must not
     * resume a slideshow the user had paused, and un-pausing must not light up a dark panel. Same
     * @Volatile reasoning as [pausedFlag]: written from the main thread, read on the loop coroutine.
     */
    @Volatile
    private var suppressedFlag = false

    /**
     * Parks (true) or releases (false) the loop for screen fake-off. Independent of [pause] /
     * [resume]: neither flag ever writes the other, so a wake releases only what the screen took.
     * The WAKE nudge is what unblocks a loop parked in [awaitUnsuppressed] or [dwell].
     */
    fun setSuppressed(suppressed: Boolean) {
        suppressedFlag = suppressed
        commands.trySend(Command.WAKE)
    }

    /** Forward one step: through history if the viewer stepped back, else a new slide. */
    fun next() {
        commands.trySend(Command.NEXT)
    }

    /** Back one displayed slide; a no-op at the oldest retained slide. */
    fun previous() {
        commands.trySend(Command.PREVIOUS)
    }

    private suspend fun loop() {
        var backoff = INITIAL_BACKOFF_MS
        // A failing image endpoint (5xx, cache-write or TLS failure) fails EVERY asset in turn,
        // which drains the queue and makes the queue's starvation rule re-admit the same assets.
        // Skips stay immediate while they look transient; a run of them earns its own backoff.
        var prefetchFailures = 0
        var prefetchBackoff = INITIAL_BACKOFF_MS
        while (true) {
            coroutineContext.ensureActive()
            awaitUnsuppressed() // gate 1: never spend the network while the panel is dark
            if (queue.depth < REFILL_THRESHOLD) {
                when (val result = fetchBatch(BATCH_SIZE)) {
                    is ImmichResult.Ok -> {
                        backoff = INITIAL_BACKOFF_MS
                        queue.offer(result.value)
                        if (queue.depth == 0) {
                            coroutineContext.ensureActive()
                            listener.onStatus(SlideshowStatus.NoPhotos)
                            delay(EMPTY_RETRY_MS)
                            continue
                        }
                    }
                    is ImmichResult.Error -> {
                        if (queue.depth == 0) {
                            // Nothing to show: surface the error and retry with backoff.
                            coroutineContext.ensureActive()
                            listener.onStatus(when (result.kind) {
                                ImmichErrorKind.AUTH -> SlideshowStatus.Auth
                                ImmichErrorKind.UNREACHABLE -> SlideshowStatus.Unreachable
                            })
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                            continue
                        }
                        // Queue still has slides: keep showing them; refill retries next iteration.
                    }
                }
            }
            // Gate 2: a suppression that landed WHILE the batch fetch was in flight still stops
            // before the decode — and before the queue is popped, so the pair is chosen (and
            // splitView re-read, in case the device rotated meanwhile) against the woken screen.
            awaitUnsuppressed()
            val slide = queue.nextSlide(splitView()) ?: continue
            if (!prefetch(slide.primary)) {
                // Only the PRIMARY failed. nextSlide() already pulled the partner out of the queue,
                // so blacklisting the pair would bury a photo that was never even decoded.
                queue.noteShown(Slide(slide.primary, null))
                slide.secondary?.let { queue.offer(listOf(it)) }
                if (++prefetchFailures >= PREFETCH_FAILURE_THRESHOLD) {
                    delay(prefetchBackoff)
                    prefetchBackoff = (prefetchBackoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
                continue
            }
            prefetchFailures = 0
            prefetchBackoff = INITIAL_BACKOFF_MS
            val shown = if (slide.secondary != null && !prefetch(slide.secondary)) {
                slide.copy(secondary = null)  // half-decoded pair degrades to a solo, never a blank pane
            } else slide
            coroutineContext.ensureActive()
            listener.onStatus(SlideshowStatus.Showing)
            announce(shown)
            queue.noteShown(slide)
            dwell()
        }
    }

    /** Emits a slide and records it as the newest displayed one, returning to the live edge. */
    private fun announce(slide: Slide) {
        listener.onSlide(slide)
        displayed.addLast(slide)
        while (displayed.size > DISPLAY_HISTORY_CAPACITY) displayed.removeFirst()
        cursor = 0
    }

    /**
     * Re-emits the slide at [cursor] without touching the queue — but only once every one of its
     * images is decoded again, exactly as the forward path does. The theme binds the incoming layer
     * ASYNCHRONOUSLY and starts its crossfade immediately, and the two layers alternate, so an
     * announcement made ahead of the decode fades in the slide from TWO steps ago before popping to
     * the right one (and, stepping back onto a pair, shows one stale photo beside one blank pane).
     * Normally a memory or disk hit; past the cache it is a re-read rather than a wrong photo.
     *
     * Returns false when an image no longer decodes (evicted AND gone from the server). The caller
     * must then leave the screen alone and put [cursor] back where the screen actually is: announcing
     * a slide we could not decode is precisely what this method exists to prevent. Failure costs one
     * attempt per press — there is no internal retry, so it cannot spin — and never stalls the
     * slideshow, because the dwell timeout still fires and moves on. Cancellation propagates:
     * [prefetch] rethrows CancellationException by contract and [stop] relies on that.
     */
    private suspend fun reAnnounce(): Boolean {
        val slide = displayed[displayed.size - 1 - cursor]
        if (!prefetch(slide.primary)) return false
        val secondary = slide.secondary
        // All-or-nothing: degrading the pair here would announce a slide the viewer never saw, and
        // `displayed` is defined as what was ACTUALLY on screen.
        if (secondary != null && !prefetch(secondary)) return false
        coroutineContext.ensureActive()
        listener.onSlide(slide)
        return true
    }

    /**
     * Parks the loop while the screen is suppressed, returning only once it is released. Like the
     * paused branch of [dwell] there is no timeout, so a suppressed slideshow issues no network
     * traffic and burns no CPU; cancellation propagates out of [Channel.receive], which is how
     * [stop] tears a suppressed loop down.
     *
     * Any command re-evaluates the flag (the received value is deliberately discarded — a NEXT
     * that arrived before the screen went dark must not advance a photo nobody can see), and
     * [setSuppressed] always sends one, so the release can never be missed. The manual pause is
     * NOT checked here: it has its own park inside [dwell], which stays deliberately permeable to
     * NEXT/PREVIOUS so a paused viewer can still step through photos.
     */
    private suspend fun awaitUnsuppressed() {
        while (suppressedFlag) {
            coroutineContext.ensureActive()
            commands.receive()
        }
    }

    /**
     * Waits out the slide's dwell, servicing transport commands; returns once the caller should
     * advance to a NEW slide. Cancellation propagates out as a CancellationException rather than a
     * return value. While paused there is no timeout at all, so a parked slideshow issues no
     * network traffic and burns no CPU. Interval is measured from crossfade END: 45 s means 45 s
     * fully visible; a transport command restarts the dwell so the new photo gets its full time.
     */
    private suspend fun dwell() {
        while (true) {
            coroutineContext.ensureActive()
            val command = if (pausedFlag) {
                commands.receive()
            } else {
                withTimeoutOrNull(CROSSFADE_MS + intervalMs()) { commands.receive() }
            }
            when (command) {
                // Dwell elapsed, or forward at the live edge: walk history first if the viewer
                // stepped back, so stepping back and walking away plays forward then continues live.
                null, Command.NEXT -> {
                    // A dwell expiring just as pause() lands must not sneak one advance through.
                    if (command == null && pausedFlag) continue
                    if (cursor == 0) return
                    cursor--
                    coroutineContext.ensureActive()
                    if (!reAnnounce()) {
                        // The next slide forward no longer decodes. Retrying it every dwell would
                        // park the slideshow on a dead entry and it would never reach the live edge
                        // again, so abandon the rest of the history tail and pull a NEW slide.
                        cursor = 0
                        return
                    }
                }
                Command.PREVIOUS -> {
                    if (cursor + 1 < displayed.size) {
                        cursor++
                        coroutineContext.ensureActive()
                        // Undecodable: keep the current photo up and put the cursor back where the
                        // screen is, or a later step would walk forward from a slide never shown.
                        if (!reAnnounce()) cursor--
                    }
                    // At the oldest retained slide: no-op, keep waiting.
                }
                // pause()/resume() flipped the flag: re-evaluate. Paused -> block; resumed -> fresh dwell.
                Command.WAKE -> {}
            }
        }
    }

    companion object {
        /** Crossfade duration between slides — the theme animates with this same constant. */
        const val CROSSFADE_MS = 2000L
        const val BATCH_SIZE = 30
        const val REFILL_THRESHOLD = 5
        /** Consecutive failed primary prefetches tolerated at full speed before throttling. */
        private const val PREFETCH_FAILURE_THRESHOLD = 3
        private const val INITIAL_BACKOFF_MS = 5_000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val EMPTY_RETRY_MS = 60_000L
        /** Slides retained for "previous". Deep enough to undo a mis-tap, bounded for memory. */
        private const val DISPLAY_HISTORY_CAPACITY = 20
    }
}
