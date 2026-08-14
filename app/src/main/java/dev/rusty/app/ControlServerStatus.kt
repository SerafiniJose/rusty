package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * In-memory, process-static publisher of what the remote-control server is ACTUALLY doing.
 *
 * The design doc keeps this deliberately separate from the preference: `desiredEnabled` (the
 * toggle, persisted by [ControlSettings]) says what the user wants; this says what happened. A
 * bind failure must NOT flip the toggle off — it leaves the toggle ON, surfaces the reason in the
 * settings row, and is retried by the next service start (boot, toggle cycle, app launch). Mixing
 * the two into one persisted "enabled" flag would either hide the failure or silently disable a
 * feature the user asked for.
 *
 * Not persisted, for the same reason [dev.rusty.app.renderer.RendererStatusPublisher] is not: a URL
 * written to disk outlives the process that could serve it, so a restored [State.Running] would
 * advertise an address nothing is listening on. If the OS kills the process, this and its listeners
 * die with it and the next start re-derives the truth.
 *
 * The SERVICE is the sole source of [State.Running]: only it knows the bind succeeded. Callers with
 * no live service to speak for them may publish [State.Failed] (a start that never got off the
 * ground) or use [publishStoppedIfInactive] (a disable that has no running service to report its
 * own teardown).
 */
object ControlServerStatus {

    /**
     * [State.Running.url] is the page/address to show and advertise — non-empty only once the bind
     * succeeded. It is empty when the server is bound but the device has no routable LAN address
     * right now; like the renderer's null-URL-under-RUNNING, "running" and "reachable at X" are
     * different facts and the second one must never be faked (0.0.0.0 is not an address a phone on
     * the LAN can open).
     */
    sealed class State {
        object Stopped : State()
        object Starting : State()
        data class Running(val url: String) : State()
        data class Failed(val message: String) : State()
    }

    private val listeners = mutableSetOf<(State) -> Unit>()
    private var state: State = State.Stopped

    /** Posts to the main thread in production; tests inject an inline dispatcher. */
    private var dispatch: (Runnable) -> Unit = { r ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }

    fun setDispatcher(d: (Runnable) -> Unit) = synchronized(this) { dispatch = d }

    fun current(): State = synchronized(this) { state }

    /** Registration REPLAYS the current value: the service is normally already running long
     *  before the settings panel is opened, so a change-only listener would stay blank forever. */
    fun addListener(l: (State) -> Unit) {
        val (d, snap) = synchronized(this) {
            listeners.add(l)
            dispatch to state
        }
        d(Runnable { if (synchronized(this) { l in listeners }) l(snap) })
    }

    fun removeListener(l: (State) -> Unit) = synchronized(this) {
        listeners.remove(l)
        Unit
    }

    fun publish(next: State) {
        val (d, targets) = synchronized(this) {
            state = next
            dispatch to listeners.toList()
        }
        targets.forEach { l ->
            // Re-check membership INSIDE the dispatched runnable: a listener removed between
            // publish() and the main thread draining the queue must not be called.
            d(Runnable { if (synchronized(this) { l in listeners }) l(next) })
        }
    }

    /**
     * Stop path. `stopService()` is a request — a live service publishes [State.Stopped] itself
     * from `onDestroy`. The two states with no live service to do that are [State.Failed] (which
     * is sticky, so that the settings row keeps showing why, and would otherwise survive the user
     * turning the feature off) and [State.Starting] (a rapid on→off can cancel the pending creation
     * before `onStartCommand` ever runs).
     */
    fun publishStoppedIfInactive() {
        val s = current()
        if (s is State.Failed || s is State.Starting) publish(State.Stopped)
    }

    /** Clears listeners and returns to [State.Stopped]. Test-only: keeps JVM tests of this
     *  process-wide singleton independent of each other. Deliberately leaves the dispatcher alone,
     *  so a test that installed an inline one before resetting keeps it. */
    @VisibleForTesting
    fun resetForTest() = synchronized(this) {
        listeners.clear()
        state = State.Stopped
    }
}
