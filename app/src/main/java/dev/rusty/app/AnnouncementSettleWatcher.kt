package dev.rusty.app

/**
 * Turns a stream of "is this pipeline busy?" readings into the single moment an announcement is
 * finished, for [ControlService] to report to [AnnouncementRelay].
 *
 * The rule is a busy -> quiet transition, never a bare quiet reading, because the renderer store
 * replays its current snapshot to every new listener: at the instant we attach, the clip we handed
 * over has not reached the player, so the pipeline still reads idle. Requiring it to have been busy
 * first is what stops the card vanishing 2.5s into a long announcement.
 *
 * The cost of the rule is the pathological case — a hand-off that reports success but never plays,
 * where no busy reading ever arrives and this never settles. [AnnouncementCardModel.MAX_DWELL_MS]
 * is the backstop for that, which is exactly what a ceiling is for.
 *
 * Not thread-safe: one watcher per announcement, driven by that store's single drain.
 */
class AnnouncementSettleWatcher {

    private var everBusy = false
    private var done = false

    /** True exactly once — on the reading that ends the announcement. */
    fun onState(busy: Boolean): Boolean {
        if (done) return false
        if (busy) { everBusy = true; return false }
        if (!everBusy) return false
        done = true
        return true
    }
}
