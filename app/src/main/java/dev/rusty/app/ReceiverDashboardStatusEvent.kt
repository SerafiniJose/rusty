package dev.rusty.app

/**
 * Shared broadcast contract for service control signals.
 *
 * The old ACTION_STATUS / ACTION_PLAYBACK producers and all their EXTRA_* keys have been removed
 * (Task 13). All receiver state is now delivered exclusively via [ReceiverStateStore]. Only the two
 * non-data signals remain:
 *  - [ACTION_STOP] — sent by the notification stop button to the service.
 *  - [ACTION_TOKEN] — bare signal that the access token in [TokenStore] has been refreshed;
 *    consumed by [LyricsActivity].
 */
object ReceiverDashboardBroadcast {
    const val ACTION_STOP = "dev.rusty.app.action.STOP_SERVICE"
    // Bare "access token refreshed" signal (no payload — the token lives in TokenStore). Consumed by LyricsActivity.
    const val ACTION_TOKEN = "dev.rusty.app.action.TOKEN"
}

/**
 * Android-free status event that maps service lifecycle broadcasts into display state.
 */
data class ReceiverDashboardStatusEvent(
    val receiverName: String,
    val lifecycle: Lifecycle,
    val message: String? = null,
    val sessionUser: String? = null,
    val sessionDisplayName: String? = null,
    val sessionAvatarUrl: String? = null
) {
    enum class Lifecycle {
        FOREGROUND,
        NATIVE_STARTING,
        CONNECTED,
        RESTARTING,
        STOPPED,
        OFF,
        ERROR,
        PERMISSION_DENIED;

        companion object {
            fun fromWireName(value: String?): Lifecycle? = entries.firstOrNull { it.name == value }
        }
    }

    fun toDashboardState(previous: ReceiverDashboardState): ReceiverDashboardState = when (lifecycle) {
        Lifecycle.FOREGROUND -> previous.copy(
            receiverName = receiverName,
            status = "Starting",
            discoveryLine = "Discovery: starting",
            serviceLine = "Service: foreground active",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )

        Lifecycle.NATIVE_STARTING -> previous.copy(
            receiverName = receiverName,
            status = "Waiting",
            discoveryLine = "Discovery: active",
            serviceLine = "Service: listening for Spotify",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )

        Lifecycle.CONNECTED -> previous.copy(
            receiverName = receiverName,
            status = "Connected",
            discoveryLine = "Discovery: connected",
            serviceLine = "Service: connected to Spotify",
            sessionUser = sessionUser?.trim()?.takeIf { it.isNotBlank() },
            sessionDisplayName = sessionDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: previous.sessionDisplayName,
            sessionAvatarUrl = sessionAvatarUrl?.trim()?.takeIf { it.isNotBlank() } ?: previous.sessionAvatarUrl,
        )

        Lifecycle.RESTARTING -> previous.copy(
            receiverName = receiverName,
            status = "Restarting",
            discoveryLine = "Discovery: re-advertising",
            serviceLine = "Service: restarting",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )

        Lifecycle.STOPPED -> previous.copy(
            receiverName = receiverName,
            status = "Stopped",
            discoveryLine = "Discovery: stopped",
            serviceLine = "Service: stopped",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )

        Lifecycle.OFF -> previous.copy(
            receiverName = receiverName,
            status = "Off",
            discoveryLine = "Discovery: off",
            serviceLine = "Service: stopped",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )

        Lifecycle.ERROR -> previous.copy(
            receiverName = receiverName,
            status = "Error",
            discoveryLine = "Discovery: error",
            serviceLine = "Service: ${message?.takeIf { it.isNotBlank() } ?: "native startup failed"}",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )

        Lifecycle.PERMISSION_DENIED -> previous.copy(
            receiverName = receiverName,
            status = "Permission needed",
            discoveryLine = "Discovery: stopped",
            serviceLine = "Service: notification permission denied",
            sessionUser = null,
            sessionDisplayName = null,
            sessionAvatarUrl = null,
        )
    }
}
