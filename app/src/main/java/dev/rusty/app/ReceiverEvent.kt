package dev.rusty.app

/**
 * Pure (Android-free) sealed event type that wraps the existing domain event classes.
 * Each variant is routed to the appropriate mapper in [reduceReceiverState].
 */
sealed interface ReceiverEvent {
    data class Status(val event: ReceiverDashboardStatusEvent) : ReceiverEvent
    data class Playback(val event: ReceiverDashboardPlaybackEvent) : ReceiverEvent
    data class Rename(val name: String) : ReceiverEvent
}
