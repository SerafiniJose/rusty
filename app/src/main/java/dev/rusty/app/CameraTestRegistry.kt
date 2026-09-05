package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * App-scoped registry of camera ids currently being connection-tested from Settings.
 *
 * [SnapshotScheduler] already enforces the design's "Test / frame-grab / live view never target
 * the same camera concurrently" rule — its `setSuspended(testRunning = ...)` parameter skips a
 * busy camera's turn — but nothing fed it: the settings Test button
 * ([CameraSettingsPanel.testButton]'s click listener) has no reference to the mounted
 * [CameraFragment], let alone its scheduler. This is that channel, the same
 * process-wide-singleton-with-a-change-listener idiom as [CameraControlRelay] and
 * [SlideshowConfigRelay]: the panel marks/clears a camera id around its probe, and
 * [CameraFragment] subscribes for its `onStart`..`onStop` window (mirroring every other
 * suspension trigger — the DLNA listener, `setMode`) so a test starting or finishing is reflected
 * in scheduler suspension immediately rather than waiting for the next grid tick.
 *
 * Threading: [testing] and [listeners] are guarded by [lock]; listener callbacks are invoked
 * outside the lock, on the calling thread (in practice always main — both the settings panel's
 * probe and the fragment's registration run on `Dispatchers.Main`/the main looper).
 */
object CameraTestRegistry {

    private val lock = Any()
    private val testing = HashSet<String>()
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) {
        synchronized(lock) { listeners.add(l) }
    }

    fun removeListener(l: () -> Unit) {
        synchronized(lock) { listeners.remove(l) }
    }

    /** Marks [cameraId] as under test. A no-op (no notification) if it was already marked. */
    fun markTesting(cameraId: String) {
        val changed = synchronized(lock) { testing.add(cameraId) }
        if (changed) notifyChanged()
    }

    /** Clears [cameraId]'s under-test mark. Always safe to call — including for an id that was
     *  never marked, e.g. a brand-new camera's Test (nothing to race against yet: it has no
     *  scheduler entry until Save). */
    fun clearTesting(cameraId: String) {
        val changed = synchronized(lock) { testing.remove(cameraId) }
        if (changed) notifyChanged()
    }

    /** Camera ids currently under test, or empty. */
    fun current(): Set<String> = synchronized(lock) { testing.toSet() }

    private fun notifyChanged() {
        val targets = synchronized(lock) { ArrayList(listeners) }
        targets.forEach { it() }
    }

    /** Test-only: keeps JVM tests of this process-wide singleton independent of each other. */
    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            testing.clear()
            listeners.clear()
        }
    }
}
