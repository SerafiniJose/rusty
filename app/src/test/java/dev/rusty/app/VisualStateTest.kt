package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class VisualStateTest {
    @Test fun waitingIsIdle() {
        assertEquals(VisualState.IDLE, ReceiverDashboardState.waiting("R").visualState())
    }

    @Test fun startingIsIdle() {
        assertEquals(VisualState.IDLE, ReceiverDashboardState.starting("R").visualState())
    }

    @Test fun playingIsActive() {
        val s = ReceiverDashboardState.playing("R", "T", "A", 0L, 1000L)
        assertEquals(VisualState.ACTIVE, s.visualState())
    }

    @Test fun pausedIsActive() {
        val s = ReceiverDashboardState.waiting("R").copy(status = "Paused", sessionUser = "jose")
        assertEquals(VisualState.ACTIVE, s.visualState())
    }

    @Test fun loadingIsActive() {
        val s = ReceiverDashboardState.waiting("R").copy(status = "Loading")
        assertEquals(VisualState.ACTIVE, s.visualState())
    }

    // A finished/stopped session still carries the logged-in user (Spotify keeps the session
    // alive) — the screen must still return to the clock, not stay on the now-playing face.
    @Test fun stoppedWithLingeringSessionIsIdle() {
        val s = ReceiverDashboardState.playing("R", "T", "A", 0L, 1000L)
            .copy(status = "Stopped", sessionUser = "jose")
        assertEquals(VisualState.IDLE, s.visualState())
    }

    @Test fun connectedButNotPlayingIsIdle() {
        val s = ReceiverDashboardState.waiting("R").copy(status = "Connected", sessionUser = "jose")
        assertEquals(VisualState.IDLE, s.visualState())
    }

    @Test fun idleStatusIsCalmReadyByDefault() {
        assertEquals("Ready for Spotify", ReceiverDashboardState.waiting("R").idleStatus().first)
    }

    @Test fun idleStatusReportsStarting() {
        assertEquals("Starting…", ReceiverDashboardState.starting("R").idleStatus().first)
    }

    @Test fun idleStatusReportsFaultOnError() {
        val s = ReceiverDashboardState.waiting("R").copy(status = "Error")
        assertEquals("Offline", s.idleStatus().first)
    }

    @Test fun idleStatusAfterPlaybackStopsIsReady() {
        val s = ReceiverDashboardState.waiting("R").copy(status = "Stopped")
        assertEquals("Ready for Spotify", s.idleStatus().first)
    }

    @Test fun idleStatusWhilePlayingShowsTrackWithNote() {
        val s = ReceiverDashboardState.playing("R", "Midnight City", "M83", 0L, 1000L)
        assertEquals("♪ Midnight City — M83", s.idleStatus().first)
    }

    @Test fun idleStatusWhilePausedShowsTrackWithNote() {
        val s = ReceiverDashboardState.playing("R", "Midnight City", "M83", 0L, 1000L)
            .copy(status = "Paused")
        assertEquals("♪ Midnight City — M83", s.idleStatus().first)
    }

    @Test fun idleStatusReportsPermission() {
        val s = ReceiverDashboardState.waiting("R").copy(status = "Permission needed")
        assertEquals("Notifications off — tap settings", s.idleStatus().first)
    }
}
