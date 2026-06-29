package dev.rusty.app

/**
 * Pure (Android-free) reducer: applies a [ReceiverEvent] to the current
 * [ReceiverDashboardState] by delegating to the existing, already-tested mappers.
 * No merge logic lives here — all domain rules stay in the mapper layer.
 */
fun reduceReceiverState(
    state: ReceiverDashboardState,
    event: ReceiverEvent
): ReceiverDashboardState = when (event) {
    is ReceiverEvent.Status   -> event.event.toDashboardState(state)
    is ReceiverEvent.Playback -> event.event.toDashboardState(state)
    is ReceiverEvent.Rename   -> state.copy(receiverName = event.name)
}
