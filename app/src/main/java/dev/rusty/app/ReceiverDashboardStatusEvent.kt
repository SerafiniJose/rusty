package dev.rusty.app

/**
 * Shared broadcast contract for service-to-dashboard status updates.
 */
object ReceiverDashboardBroadcast {
    const val ACTION_STATUS = "dev.rusty.app.action.RECEIVER_STATUS"
    const val ACTION_PLAYBACK = "dev.rusty.app.action.PLAYBACK_STATUS"
    const val ACTION_STOP = "dev.rusty.app.action.STOP_SERVICE"
    const val EXTRA_RECEIVER_NAME = "dev.rusty.app.extra.RECEIVER_NAME"
    const val EXTRA_LIFECYCLE = "dev.rusty.app.extra.LIFECYCLE"
    const val EXTRA_MESSAGE = "dev.rusty.app.extra.MESSAGE"
    const val EXTRA_SESSION_USER = "dev.rusty.app.extra.SESSION_USER"
    const val EXTRA_PLAYBACK_STATE = "dev.rusty.app.extra.PLAYBACK_STATE"
    const val EXTRA_TRACK_TITLE = "dev.rusty.app.extra.TRACK_TITLE"
    const val EXTRA_TRACK_ARTIST = "dev.rusty.app.extra.TRACK_ARTIST"
    const val EXTRA_ELAPSED_MS = "dev.rusty.app.extra.ELAPSED_MS"
    const val EXTRA_DURATION_MS = "dev.rusty.app.extra.DURATION_MS"
    const val EXTRA_QUEUE_TITLE = "dev.rusty.app.extra.QUEUE_TITLE"
    const val EXTRA_QUEUE_ARTIST = "dev.rusty.app.extra.QUEUE_ARTIST"
    const val EXTRA_QUEUE_UNAVAILABLE = "dev.rusty.app.extra.QUEUE_UNAVAILABLE"
    const val EXTRA_COVER_URL = "dev.rusty.app.extra.COVER_URL"
    // Bare "access token refreshed" signal (no payload — the token lives in TokenStore). Consumed by LyricsActivity.
    const val ACTION_TOKEN = "dev.rusty.app.action.TOKEN"
    const val EXTRA_TRACK_ID = "dev.rusty.app.extra.TRACK_ID"
    const val EXTRA_SESSION_DISPLAY_NAME = "dev.rusty.app.extra.SESSION_DISPLAY_NAME"
    const val EXTRA_SESSION_AVATAR_URL = "dev.rusty.app.extra.SESSION_AVATAR_URL"
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
        ERROR;

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
    }
}
