package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * In-memory, process-static publisher of what this device's camera-share RTSP server is ACTUALLY
 * doing. Same pattern as [ControlServerStatus] (see that class for the full rationale): not
 * persisted, because a restored [State.Streaming] or [State.Ready] would outlive the process that
 * could serve it — if the OS kills the process, this and its listeners die with it and the next
 * start re-derives the truth.
 */
object CameraShareStatus {

    sealed class State {
        object Off : State()
        object Ready : State()
        /** [width]/[height]/[fps]/[bps] are MEASURED (see StreamMeter) and 0 until the first
         *  window closes; a bare viewer count is the state before any frame left the encoder. */
        data class Streaming(
            val viewers: Int,
            val width: Int = 0,
            val height: Int = 0,
            val fps: Int = 0,
            val bps: Int = 0,
        ) : State()
        data class Unavailable(val reason: String) : State()
        data class Unsupported(val reason: String) : State()
    }

    private val listeners = mutableSetOf<(State) -> Unit>()
    private var state: State = State.Off

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

    /** Clears listeners and returns to [State.Off]. Test-only: keeps JVM tests of this
     *  process-wide singleton independent of each other. Deliberately leaves the dispatcher alone,
     *  so a test that installed an inline one before resetting keeps it. */
    @VisibleForTesting
    fun resetForTest() = synchronized(this) {
        listeners.clear()
        state = State.Off
    }
}
