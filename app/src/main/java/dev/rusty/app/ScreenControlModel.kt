package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * Immutable snapshot of the screen's desired state. [brightness] is 1..100 and is only meaningful
 * while [on] is true — "off" is represented by `on = false`, never by `brightness = 0`.
 */
data class ScreenDesired(val on: Boolean, val brightness: Int)

/**
 * What the Activity-bound renderer must actually apply for one [ScreenDesired]. Extracted as a
 * pure value so the *decisions* (which brightness mode, which numbers) are unit-testable — the
 * Activity half is then only "attach a View, assign these fields", which no JVM test can reach.
 *
 * @param overlayVisible whether the full-screen black fake-off overlay is up. Also the renderer's
 *   single source of truth for force-holding `FLAG_KEEP_SCREEN_ON`: fake-off must defeat the
 *   system's display timeout, or the panel genuinely sleeps and a remote wake cannot relight it.
 * @param windowBrightness value for `WindowManager.LayoutParams.screenBrightness` — a window-local
 *   override that needs no permission, or [BRIGHTNESS_OVERRIDE_NONE] to hand the panel back to the
 *   system (mandatory when [systemLevel] is written: an override would otherwise keep winning).
 * @param systemLevel value for `Settings.System.SCREEN_BRIGHTNESS` (0..255 scale), or null when
 *   the system setting must not be touched.
 */
data class ScreenRenderPlan(
    val overlayVisible: Boolean,
    val windowBrightness: Float,
    val systemLevel: Int?,
) {
    companion object {
        /** Mirrors `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`; duplicated so this layer
         *  stays Android-free and testable on the JVM. */
        const val BRIGHTNESS_OVERRIDE_NONE = -1.0f

        /** Not 0f: a true zero is "screen off" to some OEM display stacks, and the panel must stay
         *  awake behind the black overlay so a remote wake is instant. */
        const val OFF_WINDOW_BRIGHTNESS = 0.01f

        private const val SYSTEM_SCALE = 255

        /**
         * The "touch nothing" plan: overlay down, our window override released, no system write —
         * the panel goes back to whatever the device itself was doing (including adaptive
         * brightness). What an on-state with no COMMANDED brightness must produce.
         */
        private val HANDS_OFF = ScreenRenderPlan(
            overlayVisible = false,
            windowBrightness = BRIGHTNESS_OVERRIDE_NONE,
            systemLevel = null,
        )

        /**
         * Maps a desired state, the CURRENT `Settings.System.canWrite` verdict (re-checked per
         * command — the user can grant or revoke WRITE_SETTINGS at any time) and whether a
         * brightness was ever actually COMMANDED, into what to apply.
         *
         * Two rules keep this from touching brightness the user never asked us to touch:
         *  - **Fake-off never writes the system brightness**, even holding the permission: the
         *    black overlay is what hides the panel, so going dark changes nothing device-wide (the
         *    device keeps its brightness and its adaptive-brightness mode while dark). What the
         *    WAKE writes is a separate decision made by the two rules below — the commanded
         *    brightness if one was ever commanded, otherwise nothing at all.
         *  - **[brightnessCommanded] `false` means hands off entirely.** [ScreenControlModel]
         *    reports a DEFAULT brightness (100) before anyone has ever set one, and the renderer
         *    re-applies the desired state on every Activity create — so without this gate, merely
         *    opening the app would slam a WRITE_SETTINGS-granted device to 255 and switch it out of
         *    adaptive brightness. A default is not a command.
         *
         * Passing `canWriteSystem = false` is also how the renderer expresses its
         * degrade-to-window-mode fallback when a write throws or is refused.
         */
        fun of(
            desired: ScreenDesired,
            canWriteSystem: Boolean,
            brightnessCommanded: Boolean,
        ): ScreenRenderPlan = when {
            !desired.on -> ScreenRenderPlan(
                overlayVisible = true,
                windowBrightness = OFF_WINDOW_BRIGHTNESS,
                systemLevel = null,
            )
            !brightnessCommanded -> HANDS_OFF
            canWriteSystem -> ScreenRenderPlan(
                overlayVisible = false,
                windowBrightness = BRIGHTNESS_OVERRIDE_NONE,
                systemLevel = (desired.brightness * SYSTEM_SCALE / 100).coerceIn(1, SYSTEM_SCALE),
            )
            else -> ScreenRenderPlan(
                overlayVisible = false,
                windowBrightness = desired.brightness / 100f,
                systemLevel = null,
            )
        }
    }
}

/**
 * App-scoped, thread-safe holder of the DESIRED screen state. It never touches Views — an HTTP
 * pool thread calls [set] to record what the control page/API wants, while [HomeActivity]
 * attaches/detaches a renderer while started to actually apply it (black overlay, brightness).
 * Because desired state lives here rather than on the Activity, it survives Activity recreation
 * (rotation, process restart while backgrounded) and can be written even when no Activity is
 * currently attached — [rendererAttached] (surfaced by callers as `screen.available`) tells
 * writers whether anything is actually rendering the state right now.
 *
 * ## Last-on-brightness rule
 * Brightness is only meaningful while the screen is on. Turning the screen off does not erase the
 * brightness the user had chosen: [lastOnBrightness] remembers the most recent brightness the user
 * COMMANDED (or that was in effect while on), and turning back on with `brightness = null`
 * restores it. "Commanded" deliberately includes a brightness sent alongside `on = false` — the API
 * accepts and reports such a value, so it must also be the one the next wake honours.
 *
 * ## Threading
 * Field state (`on`/`brightness`/`lastOnBrightness`) is guarded by [lock] and always reflects the
 * latest committed write the instant [set] returns — [desired] never lags behind a caller's own
 * [set].
 *
 * Renderer *notification*, however, needs an extra guarantee beyond "outside the lock": with
 * concurrent writers (an HTTP pool thread calling [set] while [HomeActivity] calls
 * [attachRenderer]/another request calls [set]) a naive "compute under lock, release, then invoke"
 * can deliver callbacks OUT OF ORDER — thread A commits state1 and releases the lock, gets
 * preempted before invoking its renderers; thread B commits the genuinely newer state2 and
 * delivers it to completion; thread A resumes and delivers the now-stale state1 last. The renderer
 * would then be left applying state1 forever, even though [desired] already reports state2.
 *
 * This is exactly the ordering hazard [ReceiverStateStore] calls "the crux": every write appends a
 * delivery job to one shared [pending] FIFO queue (under [lock]); at most one thread is ever
 * "draining" that queue at a time ([draining]), and it delivers jobs strictly in commit order.
 * Unlike [ReceiverStateStore] there is no injected poster/executor here (this object does no
 * threading of its own beyond [lock] — posting to the main thread, if needed, is the caller's
 * job): instead, whichever caller wins the right to drain (finds [draining] `false`) walks the
 * queue itself, on its own thread, until it is empty; every other concurrent writer just enqueues
 * its job and returns, trusting the current drainer to deliver it in turn. Because the drain loop
 * pops one job under the lock and invokes it OUTSIDE the lock before looping again, deliveries are
 * both ordered AND non-blocking, and a renderer may safely call back into this object (e.g.
 * [desired], even [set]) without deadlocking.
 */
object ScreenControlModel {
    private const val DEFAULT_BRIGHTNESS = 100
    private const val MIN_BRIGHTNESS = 1
    private const val MAX_BRIGHTNESS = 100

    private val lock = Any()

    // --- mutable state, all guarded by `lock` ---
    private var on: Boolean = true
    private var brightness: Int = DEFAULT_BRIGHTNESS
    private var lastOnBrightness: Int = DEFAULT_BRIGHTNESS
    private val renderers = ArrayList<(ScreenDesired) -> Unit>()

    /** Whether the attached renderer's window is currently visible — see [setRendererVisible]. */
    private var rendererVisible = false

    /**
     * Whether anyone has ever actually COMMANDED a brightness (a non-null value through [set]).
     * [brightness] otherwise reports [DEFAULT_BRIGHTNESS], which a renderer cannot tell apart from
     * a real "100 %" — and acting on that default would change the device's brightness on nothing
     * more than an Activity create. Latched: once the user has chosen a brightness, every later
     * restore of [lastOnBrightness] is still their choice.
     */
    private var brightnessCommanded = false

    /**
     * Whether the LAST attempted system-brightness write failed (threw, or was refused with a
     * `false` return) even though `canWrite()` had said yes. Written by the renderer, read by the
     * API snapshot so the reported `mode` is what the device actually got, not what the permission
     * check promised. Purely a report: it is never an input to [ScreenRenderPlan], so the next
     * command retries the system write and a success clears it.
     */
    private var systemWriteDegraded = false

    /**
     * One queued delivery: a desired-state value paired with the exact renderers it must go to,
     * both captured together under [lock] at enqueue time so that later jobs can never be
     * delivered ahead of earlier ones (see class doc).
     */
    private class DeliveryJob(val state: ScreenDesired, val targets: List<(ScreenDesired) -> Unit>)

    /** FIFO of deliveries awaiting invocation; drained by whichever thread holds [draining]. */
    private val pending = ArrayDeque<DeliveryJob>()
    private var draining = false

    /** Atomic read of the current desired state. Safe to call from any thread. */
    fun desired(): ScreenDesired = synchronized(lock) { ScreenDesired(on, brightness) }

    /**
     * Updates the desired state and returns the RESULTING [ScreenDesired] (never an echo of the
     * request). [brightness] is clamped into 1..100; `null` means "keep current" -- except when
     * turning on after being off, where it means "restore [lastOnBrightness]" (see class doc).
     * Enqueues a delivery to every attached renderer, invoked outside the lock, in commit order.
     */
    fun set(on: Boolean, brightness: Int?): ScreenDesired {
        val result: ScreenDesired
        var shouldDrain = false
        synchronized(lock) {
            val wasOn = this.on
            val resolvedBrightness = when {
                brightness != null -> brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
                !wasOn && on -> lastOnBrightness // turning on after off: restore remembered brightness
                else -> this.brightness
            }
            this.on = on
            this.brightness = resolvedBrightness
            if (brightness != null) brightnessCommanded = true
            // An EXPLICIT brightness is always the user's on-brightness, even when it arrives on an
            // `off` command — the shipped control page leaves its slider live long enough for that
            // to happen, and the 200 response reports the new value, so remembering it only while
            // `on` would mean accepting a value, reporting it, and then silently discarding it at
            // the next wake. A `null` brightness on an `off` still changes nothing (that is what
            // makes off/on a lossless round-trip).
            if (on || brightness != null) lastOnBrightness = resolvedBrightness
            result = ScreenDesired(this.on, this.brightness)
            shouldDrain = enqueueLocked(DeliveryJob(result, ArrayList(renderers)))
        }
        if (shouldDrain) drain()
        return result
    }

    /**
     * Attaches [r] and enqueues an immediate delivery of the current desired state to it alone
     * (existing renderers are unaffected by another renderer attaching). Also invoked on every
     * subsequent [set]. Multiple renderers may be attached at once. Posting to the main thread, if
     * needed, is the caller's job.
     */
    fun attachRenderer(r: (ScreenDesired) -> Unit) {
        var shouldDrain = false
        synchronized(lock) {
            renderers.add(r)
            val current = ScreenDesired(on, brightness)
            shouldDrain = enqueueLocked(DeliveryJob(current, listOf(r)))
        }
        if (shouldDrain) drain()
    }

    /** Removes [r]; a no-op if it was never attached (or already detached). */
    fun detachRenderer(r: (ScreenDesired) -> Unit) {
        synchronized(lock) { renderers.remove(r) }
    }

    /**
     * Whether at least one renderer is attached — i.e. whether a desired state written now will be
     * applied to a window at all. Deliberately NOT the same question as [screenControlAvailable];
     * see [setRendererVisible].
     *
     * Nothing in production reads this today (the API reports [screenControlAvailable]); it is kept
     * because it is the other half of that distinction and the tests use it to assert that
     * "unavailable" never silently degrades into "detached". Read-only, so unlike a spare *writer*
     * it cannot invite a caller into the wrong behaviour.
     */
    fun rendererAttached(): Boolean = synchronized(lock) { renderers.isNotEmpty() }

    /**
     * Whether the attached renderer's window is currently VISIBLE (between `onStart` and `onStop`).
     *
     * This exists because the two facts genuinely differ, and conflating them made the API lie.
     * [HomeActivity] attaches at `onCreate` and detaches at `onDestroy` — deliberately, so that
     * merely opening another app does not make the device look uncontrollable and so a desired
     * state written meanwhile still survives and re-applies. But the fake-off mechanism is
     * `FLAG_KEEP_SCREEN_ON`, and that flag only holds a *visible* window awake: fake the screen off
     * and then leave Rusty, and the panel genuinely sleeps behind the invisible overlay, at which
     * point no `POST /api/screen {"on":true}` can relight it — there is no wake-lock path from a
     * backgrounded HTTP request. Reporting `available: true` there would tell Home Assistant's
     * light entity that a dark, unreachable panel is on and controllable.
     *
     * So: [rendererAttached] answers "will this be applied?", this answers "can it take effect
     * right now?", and the API's `screen.available` is the latter.
     */
    fun setRendererVisible(visible: Boolean) {
        synchronized(lock) { rendererVisible = visible }
    }

    /**
     * What the API reports as `screen.available`: a renderer is attached AND its window is visible.
     * Both halves matter — attached-but-backgrounded cannot take effect now (see
     * [setRendererVisible]), and a stale `visible = true` from an Activity that was destroyed
     * without an `onStop` is neutralised by the attachment half.
     */
    fun screenControlAvailable(): Boolean =
        synchronized(lock) { renderers.isNotEmpty() && rendererVisible }

    /**
     * Whether a brightness has ever been commanded (see [brightnessCommanded]). Read by the
     * renderer alongside [desired]; the two are separate reads on purpose — the flag is latched
     * one-way, so the worst a race can do is make ONE apply act on the older "not commanded yet"
     * verdict, and the very [set] that flipped the flag has its own delivery queued behind it.
     */
    fun brightnessEverCommanded(): Boolean = synchronized(lock) { brightnessCommanded }

    /**
     * Records the outcome of an attempted `Settings.System.SCREEN_BRIGHTNESS` write. Called by the
     * renderer after every attempt so [systemBrightnessUsable] reports reality: a device whose
     * `canWrite()` says yes but whose `putInt` refuses must not keep telling the control page that
     * the DEVICE brightness moved when only this app's window dimmed.
     */
    fun noteSystemBrightnessWrite(succeeded: Boolean) {
        synchronized(lock) { systemWriteDegraded = !succeeded }
    }

    /**
     * Whether the API should report brightness `mode: "system"` — the permission is held AND the
     * last write through it actually landed. Drives both `mode` and `writable` in the snapshot.
     *
     * Timing, deliberately: a `POST /api/screen` response is built synchronously while the apply is
     * still only posted to the main thread, so it necessarily reports the PREVIOUS apply's outcome;
     * the next `GET /api/state` poll (or the next command) carries the current one. Making the
     * response wait for the apply would mean blocking an HTTP thread on the UI thread while the
     * command lock is held — exactly what the renderer is designed never to do.
     */
    fun systemBrightnessUsable(canWriteSystem: Boolean): Boolean =
        canWriteSystem && synchronized(lock) { !systemWriteDegraded }

    /**
     * Returns the number of deliveries currently queued but not yet invoked. Test-only: used to
     * assert that a misbehaving (throwing) renderer never leaves deliveries stuck undrained. Does
     * NOT change runtime behaviour -- it only reads [pending].size under the existing [lock], the
     * same discipline as [ReceiverStateStore.listenerCount].
     */
    @VisibleForTesting
    fun pendingCount(): Int = synchronized(lock) { pending.size }

    /** Restores defaults and clears all renderers/pending deliveries. Test-only: keeps JVM tests
     *  of this process-wide singleton independent of each other. */
    fun resetForTest() {
        synchronized(lock) {
            on = true
            brightness = DEFAULT_BRIGHTNESS
            lastOnBrightness = DEFAULT_BRIGHTNESS
            brightnessCommanded = false
            systemWriteDegraded = false
            rendererVisible = false
            renderers.clear()
            pending.clear()
            draining = false
        }
    }

    /**
     * Must be called under [lock]. Appends [job] to [pending] and returns whether THIS caller is
     * the one that must now [drain] it (true only if no drain was already in flight).
     */
    private fun enqueueLocked(job: DeliveryJob): Boolean {
        pending.addLast(job)
        if (draining) return false
        draining = true
        return true
    }

    /**
     * Pops and invokes queued deliveries strictly in FIFO order until the queue is empty, popping
     * under [lock] but invoking OUTSIDE it. Only ever runs on the thread that won [enqueueLocked]
     * (`draining` was false); any job enqueued by another thread while this loop is running gets
     * picked up by this same loop rather than delivered out of turn.
     *
     * A renderer is untrusted third-party code (in production, [HomeActivity]'s View-attaching
     * logic; in tests, arbitrary lambdas) and MUST NOT be able to wedge this loop if it throws: an
     * exception escaping straight out of `r(job.state)` here would skip the `draining = false`
     * reset below entirely (it only runs on the next loop iteration's empty-queue check), leaving
     * [draining] stuck `true` forever — every subsequent [set]/[attachRenderer] would then silently
     * enqueue into [pending] with nothing left to ever drain it: no renderer would receive another
     * notification app-wide, and the queue would grow unboundedly. [deliverSafely] isolates each
     * renderer invocation with its own try/catch so one misbehaving renderer can neither swallow
     * deliveries to the *other* targets of the same job nor to later queued jobs, and the loop
     * always reaches the empty-queue check and releases [draining] normally.
     *
     * Deliberate choices, both made explicit rather than incidental:
     *  - **Isolate and swallow, don't propagate.** Whichever thread happens to win the right to
     *    drain is incidental — often a completely different HTTP request than the one whose write
     *    caused a renderer to misbehave — so propagating the exception to it would misattribute
     *    the failure to an unrelated caller. A renderer that wants visibility into its own
     *    failures is responsible for its own try/catch and logging before returning here.
     *  - **Catch `Exception`, not `Throwable`.** Deliberately narrower than "nothing can ever
     *    escape": an `Error` (e.g. `AssertionError` from a JUnit assertion made *inside* a test's
     *    renderer callback, as several tests in `ScreenControlModelTest` do) is intentionally left
     *    to propagate, so a test assertion failing inside a renderer still fails the test instead
     *    of being silently swallowed as "just another misbehaving renderer". This matches the rest
     *    of the codebase's convention of catching `Exception`, not `Throwable` (see e.g.
     *    `ControlFilters.parse`), and avoids a broader catch that would also have to defend against
     *    reset-races on [draining] from an outer `finally` running on every normal exit too.
     */
    private fun drain() {
        while (true) {
            val job: DeliveryJob
            synchronized(lock) {
                val next = pending.removeFirstOrNull()
                if (next == null) {
                    draining = false
                    return
                }
                job = next
            }
            for (r in job.targets) deliverSafely(r, job.state)
        }
    }

    /** Invokes [r] with [state], isolating (and discarding) any [Exception] it throws so it cannot
     *  affect delivery to other renderers in the same job or to later queued jobs. Deliberately
     *  does not catch broader [Throwable]/[Error] -- see [drain]'s KDoc for why. */
    private fun deliverSafely(r: (ScreenDesired) -> Unit, state: ScreenDesired) {
        try {
            r(state)
        } catch (e: Exception) {
            // Intentionally swallowed -- see drain()'s KDoc.
        }
    }
}
