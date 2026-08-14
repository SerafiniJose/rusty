package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * App-scoped fan-out for "the slideshow's saved configuration just changed".
 *
 * The in-app filter picker can call [HomeActivity.onSlideshowConfigChanged] directly because it IS
 * the activity's own UI. The remote-control API cannot: a `PUT /api/slideshow/filters` arrives on
 * an HTTP pool thread inside a foreground service that holds no Activity reference — and there may
 * be no Activity at all (the service runs on boot without one). This relay is the seam: writers
 * announce, and whichever [HomeActivity] instance is currently alive subscribes for the lifetime of
 * its window. A mounted idle slideshow then reloads through exactly the same remount path the
 * in-app picker triggers, instead of the change only taking effect at the next show — which never
 * comes when the device is sitting idle with the saver up.
 *
 * ## Threading
 * [notifyChanged] may be called from any thread, so [listeners] is guarded and the callbacks are
 * invoked on a COPY taken under the lock and outside it — the same discipline as
 * [ScreenControlModel]: a listener that unsubscribes itself from inside its own callback (an
 * Activity being destroyed mid-dispatch) must not mutate the collection being iterated, and must
 * not deadlock re-entering this object. Callers that need a specific thread post there themselves
 * (the control runtime posts to the main looper before calling in, because its subscriber touches
 * Views).
 */
object SlideshowConfigRelay {

    private val lock = Any()
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        synchronized(lock) { listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(lock) { listeners.remove(l) }
    }

    fun notifyChanged() {
        val targets = synchronized(lock) { ArrayList(listeners) }
        targets.forEach { it() }
    }

    /** Test-only: keeps JVM tests of this process-wide singleton independent of each other. */
    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) { listeners.clear() }
    }
}
