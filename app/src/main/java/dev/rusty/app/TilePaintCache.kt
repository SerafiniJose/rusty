package dev.rusty.app

/** Last painted (state, text) per tile, so the one-second grid tick repaints only tiles whose
 *  visible status actually changed — a healthy grid repaints nothing. */
class TilePaintCache {
    private val last = HashMap<String, Pair<TileState, String?>>()

    fun update(cameraId: String, state: TileState, text: String?): Boolean {
        val next = state to text
        if (last[cameraId] == next) return false
        last[cameraId] = next
        return true
    }

    fun prune(keep: Set<String>) { last.keys.retainAll(keep) }
}
