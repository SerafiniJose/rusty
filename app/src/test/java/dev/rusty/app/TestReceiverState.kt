package dev.rusty.app

/**
 * Builds a ReceiverDashboardState carrying [sessionUser] by reducing a CONNECTED Status event
 * onto the real initial state (`ReceiverDashboardState.waiting`, mirrored from
 * ReceiverStateReducerTest) — keeps renderer tests decoupled from state internals.
 */
fun testReceiverState(sessionUser: String?): ReceiverDashboardState =
    reduceReceiverState(
        ReceiverDashboardState.waiting("Rusty"),
        ReceiverEvent.Status(
            ReceiverDashboardStatusEvent(
                receiverName = "Rusty",
                lifecycle = ReceiverDashboardStatusEvent.Lifecycle.CONNECTED,
                message = null,
                sessionUser = sessionUser,
                sessionDisplayName = null,
                sessionAvatarUrl = null,
            )
        ),
    )
