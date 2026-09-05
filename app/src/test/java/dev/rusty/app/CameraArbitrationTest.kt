package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests for the camera playback-arbitration reducer. */
class CameraArbitrationTest {

    /** Applies events in order, collecting the command list emitted by each. */
    private fun run(
        start: ArbState = ArbState(),
        vararg events: ArbEvent,
    ): Pair<ArbState, List<List<ArbCommand>>> {
        var state = start
        val emitted = mutableListOf<List<ArbCommand>>()
        for (event in events) {
            val (next, commands) = CameraArbitration.reduce(state, event)
            state = next
            emitted += commands
        }
        return state to emitted
    }

    private fun step(state: ArbState, event: ArbEvent): Pair<ArbState, List<ArbCommand>> =
        CameraArbitration.reduce(state, event)

    @Test
    fun videoOnlySessionEmitsNoCommands() {
        val (state, emitted) = run(
            ArbState(spotifyPlaying = true, spotifyGeneration = 3L),
            ArbEvent.LiveOpened(audioWanted = false),
            ArbEvent.LiveClosed,
        )
        assertEquals(listOf(emptyList<ArbCommand>(), emptyList()), emitted)
        assertFalse(state.liveOpen)
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
        assertTrue(state.spotifyPlaying)
        assertNull(state.pausedGeneration)
    }

    @Test
    fun audioOpenPausesSpotifyAndResumesOnClose() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)

        var (next, commands) = step(state, ArbEvent.LiveOpened(audioWanted = true))
        state = next
        assertEquals(listOf(ArbCommand.RequestFocus), commands)
        assertTrue(state.liveOpen)
        assertTrue(state.audioWanted)
        assertFalse(state.cameraAudioOn)

        step(state, ArbEvent.FocusGranted).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.PauseSpotify, ArbCommand.EnableCameraAudio), c)
        }
        assertTrue(state.focusHeld)
        assertTrue(state.cameraAudioOn)
        assertEquals(7L, state.pausedGeneration)

        step(state, ArbEvent.LiveClosed).let { (s, c) ->
            state = s
            assertEquals(
                listOf(ArbCommand.MuteCameraAudio, ArbCommand.AbandonFocus, ArbCommand.ResumeSpotify),
                c,
            )
        }
        assertFalse(state.liveOpen)
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
        assertNull(state.pausedGeneration)
    }

    @Test
    fun audioOpenWithSpotifyIdleDoesNotPauseOrResume() {
        var state = ArbState(spotifyPlaying = false, spotifyGeneration = 2L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        step(state, ArbEvent.FocusGranted).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.EnableCameraAudio), c)
        }
        assertNull(state.pausedGeneration)
        step(state, ArbEvent.LiveClosed).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.MuteCameraAudio, ArbCommand.AbandonFocus), c)
        }
        assertFalse(state.cameraAudioOn)
    }

    @Test
    fun staleGenerationSuppressesResumeAndMusicWins() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first
        assertEquals(7L, state.pausedGeneration)

        step(state, ArbEvent.SpotifyPlayingChanged(playing = false, generation = 8L)).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertEquals(8L, state.spotifyGeneration)
        assertFalse(state.spotifyPlaying)

        // Music wins: a new session starts playing while camera audio is on.
        step(state, ArbEvent.SpotifyPlayingChanged(playing = true, generation = 9L)).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.MuteCameraAudio), c)
        }
        assertFalse(state.cameraAudioOn)
        assertTrue(state.spotifyPlaying)
        assertEquals(9L, state.spotifyGeneration)
        assertNull(state.pausedGeneration)

        step(state, ArbEvent.LiveClosed).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.MuteCameraAudio, ArbCommand.AbandonFocus), c)
        }
        assertFalse(state.liveOpen)
        assertFalse(state.focusHeld)
    }

    @Test
    fun focusDeniedEnablesNothing() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 4L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        step(state, ArbEvent.FocusDenied).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
        assertNull(state.pausedGeneration)
        assertTrue(state.spotifyPlaying)

        // Closing after a denial emits nothing either.
        step(state, ArbEvent.LiveClosed).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
    }

    @Test
    fun focusLostMutesWithoutResumingSpotify() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 5L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first
        step(state, ArbEvent.FocusLost).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.MuteCameraAudio), c)
        }
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
        assertTrue(state.liveOpen)
    }

    @Test
    fun switchToVideoOnlyCameraReleasesAudio() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first

        step(state, ArbEvent.CameraSwitched(audioWanted = false)).let { (s, c) ->
            state = s
            assertEquals(
                listOf(ArbCommand.MuteCameraAudio, ArbCommand.AbandonFocus, ArbCommand.ResumeSpotify),
                c,
            )
        }
        assertTrue(state.liveOpen)
        assertFalse(state.audioWanted)
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
        assertNull(state.pausedGeneration)
    }

    @Test
    fun switchToAnotherAudioCameraKeepsFocus() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first

        step(state, ArbEvent.CameraSwitched(audioWanted = true)).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.EnableCameraAudio), c)
        }
        assertTrue(state.focusHeld)
        assertTrue(state.cameraAudioOn)
        assertEquals(7L, state.pausedGeneration)
    }

    @Test
    fun switchToAudioCameraWithoutFocusRequestsIt() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = false)).first
        step(state, ArbEvent.CameraSwitched(audioWanted = true)).let { (s, c) ->
            state = s
            assertEquals(listOf(ArbCommand.RequestFocus), c)
        }
        assertTrue(state.audioWanted)
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
    }

    @Test
    fun dlnaEventsOnlyToggleTheirFlag() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first
        val before = state

        step(state, ArbEvent.DlnaVideoPlayingChanged(playing = true)).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertTrue(state.dlnaVideoPlaying)
        assertEquals(before, state.copy(dlnaVideoPlaying = false))

        step(state, ArbEvent.DlnaVideoPlayingChanged(playing = false)).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertEquals(before, state)
    }

    @Test
    fun secondLiveClosedIsIdempotent() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first
        state = step(state, ArbEvent.LiveClosed).first

        val closed = state
        step(state, ArbEvent.LiveClosed).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertEquals(closed, state)
    }

    @Test
    fun strayFocusEventsAfterCloseEmitNothing() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.LiveClosed).first
        val closed = state

        step(state, ArbEvent.FocusGranted).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertFalse(state.focusHeld)
        assertFalse(state.cameraAudioOn)
        assertEquals(closed, state)

        step(state, ArbEvent.FocusLost).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertEquals(closed, state)
    }

    @Test
    fun spotifyEventsAlwaysUpdateBookkeeping() {
        var state = ArbState()
        step(state, ArbEvent.SpotifyPlayingChanged(playing = true, generation = 11L)).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertTrue(state.spotifyPlaying)
        assertEquals(11L, state.spotifyGeneration)

        step(state, ArbEvent.SpotifyPlayingChanged(playing = false, generation = 12L)).let { (s, c) ->
            state = s
            assertEquals(emptyList<ArbCommand>(), c)
        }
        assertFalse(state.spotifyPlaying)
        assertEquals(12L, state.spotifyGeneration)
    }

    @Test
    fun audioToggledOffReleasesFocusAndResumesTheSpotifyWePaused() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first
        assertTrue(state.cameraAudioOn)

        val (next, commands) = step(state, ArbEvent.AudioToggled(wanted = false))
        assertEquals(
            listOf(ArbCommand.MuteCameraAudio, ArbCommand.AbandonFocus, ArbCommand.ResumeSpotify),
            commands,
        )
        assertFalse(next.cameraAudioOn)
        assertFalse(next.focusHeld)
        assertFalse(next.audioWanted)
        assertNull(next.pausedGeneration)
        // The session itself stays open — muting is not leaving the live view.
        assertTrue(next.liveOpen)
    }

    @Test
    fun audioToggledOnRequestsFocusAndStaysMutedUntilGranted() {
        val muted = ArbState(liveOpen = true, audioWanted = false, spotifyPlaying = false)

        val (next, commands) = step(muted, ArbEvent.AudioToggled(wanted = true))
        assertEquals(listOf(ArbCommand.RequestFocus), commands)
        assertTrue(next.audioWanted)
        assertFalse(next.cameraAudioOn)

        val (granted, grantCommands) = step(next, ArbEvent.FocusGranted)
        assertEquals(listOf(ArbCommand.EnableCameraAudio), grantCommands)
        assertTrue(granted.cameraAudioOn)
    }

    @Test
    fun audioToggledOnWhileFocusHeldEnablesAudioWithoutAskingAgain() {
        val held = ArbState(liveOpen = true, audioWanted = false, focusHeld = true)

        val (next, commands) = step(held, ArbEvent.AudioToggled(wanted = true))
        assertEquals(listOf(ArbCommand.EnableCameraAudio), commands)
        assertTrue(next.cameraAudioOn)
    }

    @Test
    fun audioToggledIsIgnoredWithNoLiveSession() {
        val idle = ArbState(liveOpen = false, spotifyPlaying = true, spotifyGeneration = 4L)

        val (next, commands) = step(idle, ArbEvent.AudioToggled(wanted = true))
        assertEquals(emptyList<ArbCommand>(), commands)
        assertEquals(idle, next)
    }

    @Test
    fun audioToggledOffDoesNotResumeSpotifyStartedByTheUser() {
        var state = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        state = step(state, ArbEvent.LiveOpened(audioWanted = true)).first
        state = step(state, ArbEvent.FocusGranted).first
        // The user starts a different track themselves: music wins and the generation moves on.
        state = step(state, ArbEvent.SpotifyPlayingChanged(playing = true, generation = 8L)).first

        val (_, commands) = step(state, ArbEvent.AudioToggled(wanted = false))
        assertFalse(commands.contains(ArbCommand.ResumeSpotify))
    }

    @Test
    fun reduceDoesNotMutateTheInputState() {
        val start = ArbState(spotifyPlaying = true, spotifyGeneration = 7L)
        val (after, _) = CameraArbitration.reduce(start, ArbEvent.LiveOpened(audioWanted = true))
        assertEquals(ArbState(spotifyPlaying = true, spotifyGeneration = 7L), start)
        assertTrue(after.liveOpen)
    }
}
