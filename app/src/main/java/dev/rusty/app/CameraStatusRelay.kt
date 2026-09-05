package dev.rusty.app

import androidx.annotation.VisibleForTesting

/** Live grid state for one camera, as the settings rows show it. Times are elapsedRealtime. */
data class CameraStatus(val state: TileState, val offlineSinceMs: Long?)

/**
 * Fragment -> settings channel for live camera status. Same process-wide-singleton-with-listener
 * idiom as [CameraTestRegistry]: [CameraFragment] publishes from its grid tick (and an empty map
 * in onStop), [CameraSettingsPanel] subscribes while mounted. Callbacks run on the publishing
 * thread (always main).
 */
object CameraStatusRelay {
    private val lock = Any()
    private var statuses: Map<String, CameraStatus> = emptyMap()
    private val listeners = ArrayList<() -> Unit>()

    fun publish(next: Map<String, CameraStatus>) {
        val changed = synchronized(lock) {
            if (next == statuses) false else { statuses = next.toMap(); true }
        }
        if (!changed) return
        val targets = synchronized(lock) { ArrayList(listeners) }
        targets.forEach { it() }
    }

    fun current(): Map<String, CameraStatus> = synchronized(lock) { statuses }

    fun addListener(l: () -> Unit) { synchronized(lock) { listeners.add(l) } }

    fun removeListener(l: () -> Unit) { synchronized(lock) { listeners.remove(l) } }

    @VisibleForTesting
    fun resetForTest() { synchronized(lock) { statuses = emptyMap(); listeners.clear() } }
}
