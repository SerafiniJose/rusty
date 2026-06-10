package dev.rusty.app

/**
 * Pure playback/queue event mapper for native player updates.
 */
data class ReceiverDashboardPlaybackEvent(
    val receiverName: String,
    val playbackState: PlaybackState,
    val trackTitle: String? = null,
    val trackArtist: String? = null,
    val coverArtUrl: String? = null,
    val trackId: String? = null,
    val sessionDisplayName: String? = null,
    val sessionAvatarUrl: String? = null,
    val sessionUser: String? = null,
    val elapsedMs: Long = 0L,
    val durationMs: Long = 0L,
    val queueTitle: String? = null,
    val queueArtist: String? = null,
    val queueUnavailable: Boolean = false
) {
    enum class PlaybackState {
        LOADING,
        PLAYING,
        PAUSED,
        STOPPED,
        UNAVAILABLE,
        ERROR;

        companion object {
            fun fromWireName(value: String?): PlaybackState? = entries.firstOrNull { it.name == value }
        }
    }

    val queueLine: String
        get() = when {
            queueUnavailable -> ReceiverDashboardState.QUEUE_UNAVAILABLE_LINE
            !queueTitle.isNullOrBlank() && !queueArtist.isNullOrBlank() ->
                "Next: ${queueTitle.trim()} — ${queueArtist.trim()}"
            !queueTitle.isNullOrBlank() -> "Next: ${queueTitle.trim()}"
            else -> ReceiverDashboardState.QUEUE_UNAVAILABLE_LINE
        }

    fun toDashboardState(previous: ReceiverDashboardState): ReceiverDashboardState {
        val safeTitle = trackTitle?.trim()?.takeIf { it.isNotBlank() }
        val safeArtist = trackArtist?.trim()?.takeIf { it.isNotBlank() }
        return previous.copy(
            receiverName = receiverName,
            status = playbackState.displayStatus,
            discoveryLine = playbackState.discoveryLine,
            serviceLine = playbackState.serviceLine,
            trackTitle = safeTitle ?: playbackState.defaultTitle,
            trackArtist = safeArtist ?: playbackState.defaultArtist,
            coverArtUrl = coverArtUrl?.trim()?.takeIf { it.isNotBlank() },
            // Keep the known account if a given event doesn't carry one; the STOPPED
            // status broadcast is what clears it on a real disconnect.
            sessionUser = sessionUser?.trim()?.takeIf { it.isNotBlank() } ?: previous.sessionUser,
            trackId = trackId?.trim()?.takeIf { it.isNotBlank() } ?: previous.trackId,
            sessionDisplayName = sessionDisplayName?.trim()?.takeIf { it.isNotBlank() } ?: previous.sessionDisplayName,
            sessionAvatarUrl = sessionAvatarUrl?.trim()?.takeIf { it.isNotBlank() } ?: previous.sessionAvatarUrl,
            queueLine = queueLine,
            elapsedMs = elapsedMs.coerceAtLeast(0L),
            durationMs = durationMs.coerceAtLeast(0L)
        )
    }

    private val PlaybackState.displayStatus: String
        get() = when (this) {
            PlaybackState.LOADING -> "Loading"
            PlaybackState.PLAYING -> "Playing"
            PlaybackState.PAUSED -> "Paused"
            PlaybackState.STOPPED -> "Stopped"
            PlaybackState.UNAVAILABLE -> "Unavailable"
            PlaybackState.ERROR -> "Error"
        }

    private val PlaybackState.discoveryLine: String
        get() = when (this) {
            PlaybackState.LOADING,
            PlaybackState.PLAYING,
            PlaybackState.PAUSED -> "Discovery: connected"
            PlaybackState.STOPPED -> "Discovery: active"
            PlaybackState.UNAVAILABLE -> "Discovery: connected"
            PlaybackState.ERROR -> "Discovery: error"
        }

    private val PlaybackState.serviceLine: String
        get() = when (this) {
            PlaybackState.LOADING -> "Service: connected to Spotify • loading track"
            PlaybackState.PLAYING -> "Service: connected to Spotify"
            PlaybackState.PAUSED -> "Service: connected to Spotify • paused"
            PlaybackState.STOPPED -> "Service: listening for Spotify"
            PlaybackState.UNAVAILABLE -> "Service: connected to Spotify • track unavailable"
            PlaybackState.ERROR -> "Service: playback error"
        }

    private val PlaybackState.defaultTitle: String
        get() = when (this) {
            PlaybackState.STOPPED -> "Waiting for Spotify"
            PlaybackState.UNAVAILABLE -> "Track unavailable"
            else -> "Unknown track"
        }

    private val PlaybackState.defaultArtist: String
        get() = when (this) {
            PlaybackState.STOPPED -> "Select this receiver from Spotify"
            else -> "Unknown artist"
        }
}
