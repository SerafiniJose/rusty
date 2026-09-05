package dev.rusty.app

/**
 * Inputs to the camera playback arbitration state machine.
 *
 * The wiring layer maps real audio-focus callbacks, [ReceiverStateStore] playback state and the
 * DLNA renderer state onto these events; the reducer itself never touches the platform.
 */
sealed interface ArbEvent {
    /** A live view was opened; [audioWanted] is false for a video-only stream. */
    data class LiveOpened(val audioWanted: Boolean) : ArbEvent

    /** The live view was closed (or torn down). */
    object LiveClosed : ArbEvent

    /** The live view stayed open but switched to another camera/profile. */
    data class CameraSwitched(val audioWanted: Boolean) : ArbEvent

    object FocusGranted : ArbEvent

    object FocusDenied : ArbEvent

    object FocusLost : ArbEvent

    /**
     * Spotify playback changed. [generation] identifies the playback session; it increases whenever
     * a new play intent starts, so we only resume what we ourselves paused.
     */
    data class SpotifyPlayingChanged(val playing: Boolean, val generation: Long) : ArbEvent

    /** The DLNA renderer started/stopped video (consumed by the snapshot scheduler). */
    data class DlnaVideoPlayingChanged(val playing: Boolean) : ArbEvent
}

/** Side effects the wiring layer performs on behalf of the reducer. */
sealed interface ArbCommand {
    object RequestFocus : ArbCommand

    object AbandonFocus : ArbCommand

    object EnableCameraAudio : ArbCommand

    object MuteCameraAudio : ArbCommand

    object PauseSpotify : ArbCommand

    object ResumeSpotify : ArbCommand
}

/** Full arbitration state; every field is derived only from the events above. */
data class ArbState(
    val liveOpen: Boolean = false,
    val audioWanted: Boolean = false,
    val focusHeld: Boolean = false,
    val cameraAudioOn: Boolean = false,
    val spotifyPlaying: Boolean = false,
    val spotifyGeneration: Long = 0L,
    /** Generation at which WE paused Spotify; null when we did not pause it. */
    val pausedGeneration: Long? = null,
    val dlnaVideoPlaying: Boolean = false,
)

/**
 * Pure reducer deciding audio focus, camera audio and Spotify pause/resume.
 *
 * Invariants:
 * - a video-only session never requests focus and never touches Spotify;
 * - camera audio is only enabled after focus is actually granted;
 * - Spotify is resumed only when we paused it and nothing else changed playback since
 *   (`pausedGeneration == spotifyGeneration`);
 * - music started by the user wins: camera audio mutes and never pauses it back.
 */
object CameraArbitration {

    fun reduce(s: ArbState, e: ArbEvent): Pair<ArbState, List<ArbCommand>> = when (e) {
        is ArbEvent.LiveOpened ->
            if (e.audioWanted) {
                s.copy(liveOpen = true, audioWanted = true) to listOf(ArbCommand.RequestFocus)
            } else {
                s.copy(liveOpen = true, audioWanted = false) to emptyList()
            }

        ArbEvent.LiveClosed -> {
            val (released, commands) = releaseAudio(s)
            released.copy(liveOpen = false, audioWanted = false) to commands
        }

        is ArbEvent.CameraSwitched ->
            if (e.audioWanted) {
                if (s.focusHeld) {
                    // Focus already ours: just move the audio over to the new stream.
                    s.copy(audioWanted = true, cameraAudioOn = true) to
                        listOf(ArbCommand.EnableCameraAudio)
                } else {
                    s.copy(audioWanted = true) to listOf(ArbCommand.RequestFocus)
                }
            } else {
                val (released, commands) = releaseAudio(s)
                released.copy(audioWanted = false) to commands
            }

        ArbEvent.FocusGranted ->
            if (!s.liveOpen || !s.audioWanted) {
                // Stray grant after the session ended: the release path abandons focus.
                s to emptyList()
            } else if (s.spotifyPlaying) {
                s.copy(
                    focusHeld = true,
                    cameraAudioOn = true,
                    pausedGeneration = s.spotifyGeneration,
                ) to listOf(ArbCommand.PauseSpotify, ArbCommand.EnableCameraAudio)
            } else {
                s.copy(focusHeld = true, cameraAudioOn = true, pausedGeneration = null) to
                    listOf(ArbCommand.EnableCameraAudio)
            }

        ArbEvent.FocusDenied ->
            s.copy(focusHeld = false, cameraAudioOn = false) to emptyList()

        ArbEvent.FocusLost ->
            if (s.cameraAudioOn) {
                // The system took focus away; do NOT resume Spotify — whoever took focus owns audio.
                s.copy(focusHeld = false, cameraAudioOn = false, pausedGeneration = null) to
                    listOf(ArbCommand.MuteCameraAudio)
            } else {
                s.copy(focusHeld = false) to emptyList()
            }

        is ArbEvent.SpotifyPlayingChanged -> {
            val next = s.copy(spotifyPlaying = e.playing, spotifyGeneration = e.generation)
            if (e.playing && s.cameraAudioOn) {
                // Music wins: drop camera audio; the platform focus-loss callback follows.
                next.copy(cameraAudioOn = false, pausedGeneration = null) to
                    listOf(ArbCommand.MuteCameraAudio)
            } else {
                next to emptyList()
            }
        }

        is ArbEvent.DlnaVideoPlayingChanged ->
            s.copy(dlnaVideoPlaying = e.playing) to emptyList()
    }

    /**
     * Gives up camera audio and focus if we hold either, resuming Spotify only when we paused it
     * and its generation is unchanged. Emits nothing when there is nothing to release.
     */
    private fun releaseAudio(s: ArbState): Pair<ArbState, List<ArbCommand>> {
        if (!s.cameraAudioOn && !s.focusHeld) {
            return s.copy(pausedGeneration = null) to emptyList()
        }
        val commands = mutableListOf(ArbCommand.MuteCameraAudio, ArbCommand.AbandonFocus)
        if (s.pausedGeneration != null && s.pausedGeneration == s.spotifyGeneration) {
            commands += ArbCommand.ResumeSpotify
        }
        return s.copy(focusHeld = false, cameraAudioOn = false, pausedGeneration = null) to commands
    }
}
