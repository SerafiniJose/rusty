package dev.rusty.app

/**
 * Pure dashboard display model shared by the compact dashboard, full now-playing view,
 * and service/native event mapping. Keep this Android-free so formatting stays unit-tested.
 */
data class ReceiverDashboardState(
    val receiverName: String,
    val status: String = "Waiting",
    val discoveryLine: String = "Discovery: active",
    val serviceLine: String = "Service: listening for Spotify",
    val audioLine: String = DEFAULT_AUDIO_LINE,
    val sessionUser: String? = null,
    val trackId: String? = null,
    val sessionDisplayName: String? = null,
    val sessionAvatarUrl: String? = null,
    val trackTitle: String = "Waiting for Spotify",
    val trackArtist: String = "Select this receiver from Spotify",
    val coverArtUrl: String? = null,
    val queueLine: String = DEFAULT_QUEUE_LINE,
    val elapsedMs: Long = 0L,
    val durationMs: Long = 0L
) {
    val elapsedLabel: String = formatDuration(elapsedMs)
    val durationLabel: String = formatDuration(durationMs)

    /** Display line for the connected Spotify account (real name preferred), or a neutral idle string. */
    val sessionLine: String =
        (sessionDisplayName?.trim()?.takeIf { it.isNotBlank() }
            ?: sessionUser?.trim()?.takeIf { it.isNotBlank() })
            ?.let { "Session: $it" } ?: NO_SESSION_LINE

    /** One row in the info sheet's session section. Forward-compatible for future Jam members. */
    data class SessionListener(
        val name: String,
        val subtitle: String,
        val avatarUrl: String?
    )

    /** The current listeners: one entry for the connected account today (empty when idle). */
    val listeners: List<SessionListener>
        get() {
            val name = sessionDisplayName?.trim()?.takeIf { it.isNotBlank() }
                ?: sessionUser?.trim()?.takeIf { it.isNotBlank() }
                ?: return emptyList()
            return listOf(
                SessionListener(
                    name = name,
                    subtitle = "Spotify · controlling playback",
                    avatarUrl = sessionAvatarUrl?.trim()?.takeIf { it.isNotBlank() }
                )
            )
        }

    val isPlaybackClockRunning: Boolean
        get() = status == "Playing" && durationMs > 0L && elapsedMs < durationMs

    fun advanceElapsedBy(deltaMs: Long): ReceiverDashboardState {
        if (!isPlaybackClockRunning || deltaMs <= 0L) return this
        return copy(elapsedMs = (elapsedMs + deltaMs).coerceAtMost(durationMs))
    }

    companion object {
        const val DEFAULT_AUDIO_LINE = "Audio: AAudio • Cache: app cache"
        const val QUEUE_UNAVAILABLE_LINE = "Queue unavailable from receiver API"
        const val DEFAULT_QUEUE_LINE = QUEUE_UNAVAILABLE_LINE
        const val NO_SESSION_LINE = "No active session"

        fun starting(receiverName: String): ReceiverDashboardState = ReceiverDashboardState(
            receiverName = receiverName,
            status = "Starting",
            discoveryLine = "Discovery: starting",
            serviceLine = "Service: starting"
        )

        fun waiting(receiverName: String): ReceiverDashboardState = ReceiverDashboardState(
            receiverName = receiverName
        )

        fun restarting(receiverName: String): ReceiverDashboardState = ReceiverDashboardState(
            receiverName = receiverName,
            status = "Restarting",
            discoveryLine = "Discovery: re-advertising",
            serviceLine = "Service: restarting"
        )

        fun off(receiverName: String): ReceiverDashboardState = ReceiverDashboardState(
            receiverName = receiverName,
            status = "Off",
            discoveryLine = "Discovery: off",
            serviceLine = "Service: stopped"
        )

        fun playing(
            receiverName: String,
            title: String,
            artist: String,
            elapsedMs: Long,
            durationMs: Long,
            queueLine: String = DEFAULT_QUEUE_LINE
        ): ReceiverDashboardState = ReceiverDashboardState(
            receiverName = receiverName,
            status = "Playing",
            trackTitle = title.ifBlank { "Unknown track" },
            trackArtist = artist.ifBlank { "Unknown artist" },
            queueLine = queueLine.ifBlank { DEFAULT_QUEUE_LINE },
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L)
        )

        private fun formatDuration(milliseconds: Long): String {
            val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
