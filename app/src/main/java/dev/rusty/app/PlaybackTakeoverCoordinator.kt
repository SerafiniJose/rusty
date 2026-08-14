package dev.rusty.app

/**
 * Process-scoped playback-takeover driver — the SINGLE edge detector for the three
 * takeover toggles. Lives in [RustyApp] (not an Activity) because a cast can start with
 * no Activity alive (boot-started service), and because a single detector with a
 * consume-once pending flag sidesteps the listener-replay and commitNow-after-save
 * hazards an Activity-registered listener would have.
 *
 * All Android effects are injected so the class stays JVM-testable:
 * [wakeScreen] receives whether the remote-control fake-off is active (it must wake via
 * [ScreenControlModel] then, a real wake lock otherwise); [launchHome] starts the
 * activity over other apps. Wake runs BEFORE launch — with the display off the launched
 * activity may never resume, so the screen must already be lighting up.
 */
class PlaybackTakeoverCoordinator(
    private val store: ReceiverStateStore,
    private val clock: MonotonicClock,
    private val toggles: () -> TakeoverToggles,
    private val canDrawOverlays: () -> Boolean,
    private val screenDesiredOn: () -> Boolean,
    private val wakeScreen: (fakeOffActive: Boolean) -> Unit,
    private val launchHome: () -> Unit,
) {
    private val processStartMs = clock.nowMs()
    private var prevVisual = VisualState.IDLE
    private var leftActiveAtMs: Long? = null
    private var pendingPageSwitch = false
    private var pageConsumer: (() -> Unit)? = null

    private val listener = ReceiverStateStore.Listener { snapshot ->
        val next = snapshot.state.visualState()
        val prev = prevVisual
        prevVisual = next
        val now = clock.nowMs()
        if (prev == VisualState.ACTIVE && next == VisualState.IDLE) leftActiveAtMs = now

        // Read once, for the wake below: the policy no longer branches on it (the merged toggle
        // always wakes before it launches, so a fake-off is simply cleared), but the wake still
        // needs to know WHICH path to take — ScreenControlModel under a fake-off, a real wake
        // lock otherwise.
        val desiredOn = screenDesiredOn()
        val actions = PlaybackTakeover.onVisualEdge(
            prev = prev,
            next = next,
            toggles = toggles(),
            canDrawOverlays = canDrawOverlays(),
            msSinceLastActive = leftActiveAtMs?.let { now - it },
            msSinceProcessStart = now - processStartMs,
        )
        // This listener runs inside ReceiverStateStore's drain loop. An exception escaping it
        // propagates out of the posted Runnable uncaught: the loop never reaches the branch
        // that clears drainScheduled, so every future dispatch silently stops delivering to
        // every listener in the app, forever. Each effect is therefore guarded independently,
        // so a real PowerManager/startActivity/fragment-commit failure in one effect can't
        // swallow the others or wedge the store.
        if (TakeoverAction.WAKE_SCREEN in actions) runCatching { wakeScreen(!desiredOn) }
        if (TakeoverAction.SWITCH_PAGE in actions) {
            val consumer = pageConsumer
            if (consumer != null) {
                // Consumed regardless of outcome: a throw here must not leave the switch
                // eligible to fire again on the next attach.
                runCatching { consumer() }
            } else {
                pendingPageSwitch = true
            }
        }
        if (TakeoverAction.BRING_TO_FRONT in actions) runCatching { launchHome() }
    }

    /** Seed prev from the live snapshot, then subscribe — the registration replay is prev==next. */
    fun start() {
        prevVisual = store.snapshot.state.visualState()
        store.addListener(listener)
    }

    /**
     * [HomeActivity] attaches in onResume and detaches in onPause — the only window where
     * the navigator's commitNow is safe on every API level. A pending switch recorded while
     * nobody was resumed (background cast, cold start) is delivered — and consumed — now.
     */
    fun attachPageConsumer(consumer: () -> Unit) {
        pageConsumer = consumer
        if (pendingPageSwitch) {
            // Clear before invoking, not after: same consume-once contract as the listener
            // above — a throwing consumer must not leave a switch that replays later.
            pendingPageSwitch = false
            runCatching { consumer() }
        }
    }

    fun detachPageConsumer() {
        pageConsumer = null
    }
}
