package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementSettleWatcherTest {

    /**
     * The renderer store replays its current snapshot the moment a listener attaches, and at that
     * point the clip we just handed over has not reached the player yet — so the pipeline still
     * reads idle. Settling there would pull the card down 2.5s into a 30s announcement.
     */
    @Test fun theIdleStateBeforePlaybackStartsDoesNotSettleIt() {
        val watcher = AnnouncementSettleWatcher()

        assertFalse(watcher.onState(busy = false))
    }

    @Test fun playbackStartingDoesNotSettleIt() {
        val watcher = AnnouncementSettleWatcher()

        assertTrue(!watcher.onState(busy = true))
    }

    @Test fun goingQuietAfterPlayingSettlesIt() {
        val watcher = AnnouncementSettleWatcher()
        watcher.onState(busy = true)

        assertTrue(watcher.onState(busy = false))
    }

    /** The store keeps delivering; the announcement only finishes once. */
    @Test fun itSettlesOnlyOnce() {
        val watcher = AnnouncementSettleWatcher()
        watcher.onState(busy = true)
        watcher.onState(busy = false)

        assertFalse(watcher.onState(busy = false))
    }
}
