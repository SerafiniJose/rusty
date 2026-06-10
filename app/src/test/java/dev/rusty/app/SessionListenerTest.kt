package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListenerTest {
    @Test
    fun sessionLinePrefersDisplayNameOverUsername() {
        val state = ReceiverDashboardState(
            receiverName = "Salotto",
            sessionUser = "31abc",
            sessionDisplayName = "Jose Serafini"
        )
        assertEquals("Session: Jose Serafini", state.sessionLine)
    }

    @Test
    fun sessionLineFallsBackToUsernameThenIdle() {
        assertEquals("Session: 31abc", ReceiverDashboardState("X", sessionUser = "31abc").sessionLine)
        assertEquals(ReceiverDashboardState.NO_SESSION_LINE, ReceiverDashboardState("X").sessionLine)
    }

    @Test
    fun listenersHasOneEntryWhenUserPresentEmptyOtherwise() {
        assertTrue(ReceiverDashboardState("X").listeners.isEmpty())
        val state = ReceiverDashboardState(
            receiverName = "X",
            sessionUser = "31abc",
            sessionDisplayName = "Jose",
            sessionAvatarUrl = "https://img"
        )
        assertEquals(1, state.listeners.size)
        assertEquals("Jose", state.listeners[0].name)
        assertEquals("Spotify · controlling playback", state.listeners[0].subtitle)
        assertEquals("https://img", state.listeners[0].avatarUrl)
    }
}
