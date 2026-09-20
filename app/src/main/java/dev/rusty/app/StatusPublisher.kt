package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * The listener machinery shared by this app's in-memory status holders ([ControlServerStatus],
 * [CameraShareStatus]): hold the last published [S], replay it to every new listener, and deliver
 * changes on the main thread.
 *
 * None of these holders persists anything, and that is the point: a state written to disk outlives
 * the process that could serve it, so a restored "running" would advertise a server nothing is
 * listening on. If the OS kills the process, a holder and its listeners die with it and the next
 * start re-derives the truth. The subclasses document what their own states mean.
 *
 * Subclasses are `object`s, so `this` is the singleton and every method below locks the same
 * monitor a subclass's own `synchronized(this)` does.
 */
open class StatusPublisher<S : Any>(private val initial: S) {

    private val listeners = mutableSetOf<(S) -> Unit>()
    private var state: S = initial

    /** Posts to the main thread in production; tests inject an inline dispatcher. */
    private var dispatch: (Runnable) -> Unit = { r ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }

    fun setDispatcher(d: (Runnable) -> Unit) = synchronized(this) { dispatch = d }

    fun current(): S = synchronized(this) { state }

    /** Registration REPLAYS the current value: the service is normally already running long
     *  before the settings panel is opened, so a change-only listener would stay blank forever. */
    fun addListener(l: (S) -> Unit) {
        val (d, snap) = synchronized(this) {
            listeners.add(l)
            dispatch to state
        }
        d(Runnable { if (synchronized(this) { l in listeners }) l(snap) })
    }

    fun removeListener(l: (S) -> Unit) = synchronized(this) {
        listeners.remove(l)
        Unit
    }

    fun publish(next: S) {
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

    /** Clears listeners and returns to the initial state. Test-only: keeps JVM tests of these
     *  process-wide singletons independent of each other. Deliberately leaves the dispatcher alone,
     *  so a test that installed an inline one before resetting keeps it. */
    @VisibleForTesting
    fun resetForTest() = synchronized(this) {
        listeners.clear()
        state = initial
    }
}
