package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * One announcement, from the moment [ControlService] hands its clip to a playback pipeline.
 *
 * [id] is what makes two identical announcements in a row two announcements: the words are not the
 * identity, the hand-off is. [settled] is the voicing pipeline reporting it has nothing left to
 * finish; how long the card survives that is [AnnouncementCardModel]'s business, not this class's.
 */
data class Announcement(
    val id: Long,
    val text: String,
    val publishedAtMs: Long,
    val settled: Boolean,
)

/**
 * Control service -> screens channel for the text the device is currently speaking. Same
 * process-wide-singleton-with-listeners idiom as [CameraStatusRelay]: [ControlService] publishes
 * from the announce route, [AnnouncementCard] subscribes from whichever activity is frontmost.
 * Callbacks run on the publishing thread, which for the announce route is an HTTP pool thread — so
 * subscribers post to their own looper.
 *
 * Nothing here clears itself. A stale announcement is simply one [AnnouncementCardModel] no longer
 * calls visible, which is also what makes a screen opening mid-announcement show the card and a
 * screen opening after it show nothing, with no extra bookkeeping.
 */
object AnnouncementRelay {
    private val lock = Any()
    private var nextId = 1L
    private var announcement: Announcement? = null
    private val listeners = ArrayList<() -> Unit>()

    /** Records [text] as the announcement now being voiced and returns its [Announcement.id]. */
    fun publish(text: String, nowMs: Long): Long {
        val id = synchronized(lock) {
            val id = nextId++
            announcement = Announcement(id, text, nowMs, settled = false)
            id
        }
        notifyListeners()
        return id
    }

    /** Reports that [id]'s pipeline has finished. A superseded [id] is ignored. */
    fun settle(id: Long) {
        val changed = synchronized(lock) {
            val current = announcement
            if (current == null || current.id != id || current.settled) false
            else { announcement = current.copy(settled = true); true }
        }
        if (changed) notifyListeners()
    }

    fun current(): Announcement? = synchronized(lock) { announcement }

    fun addListener(l: () -> Unit) { synchronized(lock) { listeners.add(l) } }

    fun removeListener(l: () -> Unit) { synchronized(lock) { listeners.remove(l) } }

    private fun notifyListeners() {
        val targets = synchronized(lock) { ArrayList(listeners) }
        targets.forEach { it() }
    }

    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) { announcement = null; nextId = 1L; listeners.clear() }
    }
}
