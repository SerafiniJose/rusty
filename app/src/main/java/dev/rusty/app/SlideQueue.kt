package dev.rusty.app

/** One display step: a solo photo, or two portraits side-by-side. */
data class Slide(val primary: ImmichAsset, val secondary: ImmichAsset?)

/**
 * Pure slideshow queue (Android-free). Random batches from the server are deduped against a
 * recent-history ring + the pending queue; portrait pairing pulls the FIRST queued portrait as a
 * partner (landscape entries keep their place). Starvation rule: when an entire non-empty batch is
 * filtered out and nothing is queued, the history is cleared so small libraries repeat instead of
 * stalling the slideshow.
 */
class SlideQueue(private val historyCapacity: Int = 50) {
    private val queue = ArrayDeque<ImmichAsset>()
    private val history = LinkedHashSet<String>()   // insertion-ordered ring of shown asset ids

    val depth: Int get() = queue.size

    fun offer(batch: List<ImmichAsset>) {
        val fresh = batch.distinctBy { it.id }
            .filter { it.id !in history && queue.none { q -> q.id == it.id } }
        if (fresh.isEmpty() && queue.isEmpty() && batch.isNotEmpty()) {
            history.clear()
            queue.addAll(batch.distinctBy { it.id })
            return
        }
        queue.addAll(fresh)
    }

    fun nextSlide(splitView: Boolean): Slide? {
        val primary = queue.removeFirstOrNull() ?: return null
        if (!splitView || !primary.isPortrait) return Slide(primary, null)
        val partnerIndex = queue.indexOfFirst { it.isPortrait }
        val partner = if (partnerIndex >= 0) queue.removeAt(partnerIndex) else null
        return Slide(primary, partner)
    }

    fun noteShown(slide: Slide) {
        remember(slide.primary.id)
        slide.secondary?.let { remember(it.id) }
    }

    private fun remember(id: String) {
        history.remove(id)   // re-add moves it to newest position
        history.add(id)
        while (history.size > historyCapacity) history.remove(history.first())
    }
}
