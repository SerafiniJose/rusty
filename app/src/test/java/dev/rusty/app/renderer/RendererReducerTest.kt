package dev.rusty.app.renderer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RendererReducerTest {
    private val media = RendererMedia("http://ha:8123/tts.mp3", "<DIDL-Lite/>", "audio/mpeg")

    private fun loaded(): RendererState =
        reduceRenderer(RendererState(), RendererEvent.SoapSetUri(media.uri, media.metadata, media.mime)).first

    @Test fun setUri_bumpsGenerationAndPrepares() {
        val (s, fx) = reduceRenderer(RendererState(), RendererEvent.SoapSetUri(media.uri, media.metadata, media.mime))
        assertEquals(RendererTransport.STOPPED, s.transport)
        assertEquals(1L, s.mediaGeneration)
        assertEquals(listOf<RendererEffect>(RendererEffect.PreparePlayer(media.uri, media.mime, 1L)), fx)
    }

    @Test fun play_whileSpotifyPlaying_pausesSpotifyFirst() {
        val (s, fx) = reduceRenderer(
            loaded(),
            RendererEvent.SoapPlay(spotifyPlaying = true, spotifySessionGen = 42, mixMode = SpotifyInterruption.PAUSE),
        )
        assertEquals(RendererTransport.TRANSITIONING, s.transport)
        assertEquals(SpotifyInterruption.PAUSE, s.spotifyInterruption)
        assertEquals(42L, s.spotifySessionGeneration)
        assertEquals(listOf(RendererEffect.PauseSpotify, RendererEffect.PlayPlayer), fx)
    }

    @Test fun play_whileSpotifyIdle_doesNotTouchSpotify() {
        val (s, fx) = reduceRenderer(loaded(), RendererEvent.SoapPlay(false, 42, SpotifyInterruption.PAUSE))
        assertNull(s.spotifyInterruption)
        assertEquals(listOf<RendererEffect>(RendererEffect.PlayPlayer), fx)
    }

    @Test fun stop_whileOwned_armsDeferredResume_notImmediate() {
        val owned = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        val (s, fx) = reduceRenderer(owned, RendererEvent.SoapStop)
        assertTrue(s.resumePending); assertEquals(SpotifyInterruption.PAUSE, s.spotifyInterruption)
        assertEquals(listOf(RendererEffect.StopPlayer, RendererEffect.ScheduleResumeTimer(ResumeGrace.MS)), fx)
        assertFalse(fx.contains(RendererEffect.ResumeSpotify))
    }

    @Test fun replacement_stopSetUriPlay_neverResumesSpotify() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        val (s2, fx2) = reduceRenderer(s, RendererEvent.SoapSetUri("http://ha/next.mp3", "", "audio/mpeg"))
        // While an interruption is owed the grace timer is RE-ARMED (not merely cancelled), so an
        // abandoned replacement — a SetUri that never gets its Play — still releases Spotify.
        assertTrue(s2.resumePending); assertEquals(SpotifyInterruption.PAUSE, s2.spotifyInterruption)
        assertTrue(fx2.contains(RendererEffect.CancelResumeTimer))
        assertTrue(fx2.contains(RendererEffect.ScheduleResumeTimer(ResumeGrace.MS)))
        val (s3, fx3) = reduceRenderer(s2, RendererEvent.SoapPlay(false, 42, SpotifyInterruption.PAUSE))
        assertEquals(SpotifyInterruption.PAUSE, s3.spotifyInterruption) // ownership carried through
        assertTrue(fx3.contains(RendererEffect.CancelResumeTimer))     // Play cancels the re-armed timer
        assertFalse(fx3.contains(RendererEffect.PauseSpotify))         // already paused by us
    }

    @Test fun resumeTimer_resumesOnlyWhenSessionMatchesAndReceiverPaused() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.ResumeTimerFired(receiverPlaying = false, receiverSessionGen = 42))
        assertNull(s2.spotifyInterruption); assertFalse(s2.resumePending)
        assertEquals(listOf<RendererEffect>(RendererEffect.ResumeSpotify), fx)
        // session replaced meanwhile → release ownership WITHOUT resuming
        var t = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        t = reduceRenderer(t, RendererEvent.SoapStop).first
        val (t2, tfx) = reduceRenderer(t, RendererEvent.ResumeTimerFired(false, 43))
        assertNull(t2.spotifyInterruption)
        assertEquals(listOf(RendererEffect.RestoreSpotifyVolume(0)), tfx)
    }

    @Test fun spotifyStartedPlaying_whileHaPlaying_spotifyWins() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.PlayerPlaying(s.mediaGeneration)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.SpotifyStartedPlaying(42))
        assertEquals(RendererTransport.STOPPED, s2.transport)
        assertNull(s2.spotifyInterruption)
        assertEquals(
            listOf(RendererEffect.StopPlayer, RendererEffect.CancelResumeTimer, RendererEffect.RestoreSpotifyVolume(0)),
            fx,
        )
    }

    @Test fun stalePlayerEvents_areDropped() {
        var s = loaded()                                             // gen 1
        s = reduceRenderer(s, RendererEvent.SoapSetUri("http://ha/2.mp3", "", null)).first // gen 2
        val (s2, fx) = reduceRenderer(s, RendererEvent.PlayerEnded(generation = 1))
        assertEquals(s, s2); assertTrue(fx.isEmpty())
    }

    @Test fun playerError_setsErrorStatusAndArmsResume() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.PlayerError(s.mediaGeneration, "HTTP 404"))
        assertEquals(RendererTransport.STOPPED, s2.transport)
        assertEquals("ERROR_OCCURRED", s2.transportStatus)
        assertTrue(fx.contains(RendererEffect.ScheduleResumeTimer(ResumeGrace.MS)))
    }

    @Test fun shutdown_resumesImmediatelyWhenOwned() {
        val s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.Shutdown(receiverPlaying = false, receiverSessionGen = 42))
        assertEquals(RendererTransport.NO_MEDIA_PRESENT, s2.transport)
        assertTrue(fx.contains(RendererEffect.ResumeSpotify))
    }

    @Test fun transportActions_followStateAndSeekability() {
        assertEquals("", RendererState().currentTransportActions())
        val playingSeekable = RendererState(transport = RendererTransport.PLAYING, seekable = true)
        assertEquals("Pause,Stop,Seek", playingSeekable.currentTransportActions())
        assertEquals("Pause,Stop", playingSeekable.copy(seekable = false).currentTransportActions())
    }

    // --- Additional tests derived from the numbered rules ---

    @Test fun pause_whilePlaying_pauses() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(false, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.PlayerPlaying(s.mediaGeneration)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.SoapPause)
        assertEquals(RendererTransport.PAUSED_PLAYBACK, s2.transport)
        assertEquals(listOf<RendererEffect>(RendererEffect.PausePlayer), fx)
        // no-op when not PLAYING (e.g. still TRANSITIONING)
        val transitioning = reduceRenderer(loaded(), RendererEvent.SoapPlay(false, 42, SpotifyInterruption.PAUSE)).first
        val (s3, fx3) = reduceRenderer(transitioning, RendererEvent.SoapPause)
        assertEquals(transitioning, s3)
        assertTrue(fx3.isEmpty())
    }

    @Test fun playerEnded_currentGen_armsResume() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.PlayerPlaying(s.mediaGeneration)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.PlayerEnded(s.mediaGeneration))
        assertEquals(RendererTransport.STOPPED, s2.transport)
        assertTrue(s2.resumePending); assertEquals(SpotifyInterruption.PAUSE, s2.spotifyInterruption)
        assertEquals(listOf(RendererEffect.StopPlayer, RendererEffect.ScheduleResumeTimer(ResumeGrace.MS)), fx)
    }

    @Test fun focusLost_stopsAndArmsResume() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.PlayerPlaying(s.mediaGeneration)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.FocusLost)
        assertEquals(RendererTransport.STOPPED, s2.transport)
        assertTrue(s2.resumePending); assertEquals(SpotifyInterruption.PAUSE, s2.spotifyInterruption)
        assertEquals(listOf(RendererEffect.StopPlayer, RendererEffect.ScheduleResumeTimer(ResumeGrace.MS)), fx)
        // no-op when not PLAYING/TRANSITIONING (e.g. STOPPED)
        val stopped = loaded()
        val (s3, fx3) = reduceRenderer(stopped, RendererEvent.FocusLost)
        assertEquals(stopped, s3)
        assertTrue(fx3.isEmpty())
    }

    @Test fun playerReady_storesDurationAndSeekable() {
        val s = loaded()
        val (s2, fx) = reduceRenderer(s, RendererEvent.PlayerReady(s.mediaGeneration, durationMs = 12345L, seekable = true))
        assertEquals(12345L, s2.durationMs)
        assertTrue(s2.seekable)
        assertEquals(RendererTransport.STOPPED, s2.transport) // transport unchanged
        assertTrue(fx.isEmpty())
    }

    @Test fun spotifyStartedPlaying_whileResumePending_cancelsTimerAndReleases() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        assertTrue(s.resumePending); assertEquals(SpotifyInterruption.PAUSE, s.spotifyInterruption)
        val (s2, fx) = reduceRenderer(s, RendererEvent.SpotifyStartedPlaying(42))
        assertNull(s2.spotifyInterruption)
        assertFalse(s2.resumePending)
        assertEquals(
            listOf(RendererEffect.CancelResumeTimer, RendererEffect.RestoreSpotifyVolume(0)),
            fx,
        )
    }

    @Test fun playerPlaying_afterStop_isIgnored() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(false, 0, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        assertEquals(RendererTransport.STOPPED, s.transport)
        // late same-generation PlayerPlaying must NOT resurrect PLAYING after the stop
        val (s2, fx) = reduceRenderer(s, RendererEvent.PlayerPlaying(s.mediaGeneration))
        assertEquals(s, s2)
        assertEquals(RendererTransport.STOPPED, s2.transport)
        assertTrue(fx.isEmpty())
        // normal path still works: TRANSITIONING → PlayerPlaying → PLAYING
        val transitioning = reduceRenderer(loaded(), RendererEvent.SoapPlay(false, 0, SpotifyInterruption.PAUSE)).first
        assertEquals(RendererTransport.TRANSITIONING, transitioning.transport)
        val (s3, fx3) = reduceRenderer(transitioning, RendererEvent.PlayerPlaying(transitioning.mediaGeneration))
        assertEquals(RendererTransport.PLAYING, s3.transport)
        assertTrue(fx3.isEmpty())
    }

    @Test fun resumeTimer_receiverAlreadyPlaying_releasesWithoutResume() {
        var s = reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE)).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.ResumeTimerFired(receiverPlaying = true, receiverSessionGen = 42))
        assertNull(s2.spotifyInterruption); assertFalse(s2.resumePending)
        assertEquals(listOf(RendererEffect.RestoreSpotifyVolume(0)), fx)
    }

    // --- Fade-timer choreography (deferred playback, unconditional restores, edge-triggered fade-in) ---

    private fun playFading(mode: SpotifyInterruption, fadeMs: Long = 500L) =
        reduceRenderer(loaded(), RendererEvent.SoapPlay(true, 42, mode, fadeMs))

    private fun fired(s: RendererState, sessionGen: Long = 42) =
        reduceRenderer(s, RendererEvent.FadeTimerFired(s.mediaGeneration, sessionGen))

    @Test fun play_fadeDuck_fadesAndDefersPlay() {
        val (s, fx) = playFading(SpotifyInterruption.DUCK)
        assertEquals(FadePhase.DOWN_BEFORE_PLAY, s.fadePhase)
        assertEquals(500L, s.fadeMs)
        assertEquals(SpotifyInterruption.DUCK, s.spotifyInterruption)
        assertEquals(
            listOf(RendererEffect.DuckSpotify(500), RendererEffect.ScheduleFadeTimer(550, 1L)),
            fx,
        )
        assertFalse(fx.contains(RendererEffect.PlayPlayer))
    }

    @Test fun play_fadePause_mutesButDoesNotPauseYet() {
        val (s, fx) = playFading(SpotifyInterruption.PAUSE)
        assertEquals(
            listOf(RendererEffect.MuteSpotify(500), RendererEffect.ScheduleFadeTimer(550, 1L)),
            fx,
        )
        assertFalse(fx.contains(RendererEffect.PauseSpotify))
        assertEquals(FadePhase.DOWN_BEFORE_PLAY, s.fadePhase)
    }

    @Test fun play_fadeOff_pause_matchesTodayExactly() {
        val (s, fx) = playFading(SpotifyInterruption.PAUSE, fadeMs = 0L)
        assertEquals(listOf(RendererEffect.PauseSpotify, RendererEffect.PlayPlayer), fx)
        assertEquals(FadePhase.NONE, s.fadePhase)
    }

    @Test fun play_fadeOff_duck_isInstant() {
        val (_, fx) = playFading(SpotifyInterruption.DUCK, fadeMs = 0L)
        assertEquals(listOf(RendererEffect.DuckSpotify(0), RendererEffect.PlayPlayer), fx)
    }

    @Test fun secondPlay_whileFadingDown_isIdempotent() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val (s, fx) = reduceRenderer(fading, RendererEvent.SoapPlay(true, 42, SpotifyInterruption.PAUSE, 500L))
        assertEquals(RendererTransport.TRANSITIONING, s.transport)
        assertEquals(FadePhase.DOWN_BEFORE_PLAY, s.fadePhase)
        assertEquals(emptyList<RendererEffect>(), fx) // the armed fade timer will start playback
    }

    @Test fun fadeTimerFired_pauseMode_pausesThenPlays() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val (s, fx) = fired(fading)
        assertEquals(FadePhase.NONE, s.fadePhase)
        assertEquals(listOf(RendererEffect.PauseSpotify, RendererEffect.PlayPlayer), fx)
    }

    @Test fun fadeTimerFired_duckMode_justPlays() {
        val fading = playFading(SpotifyInterruption.DUCK).first
        val (_, fx) = fired(fading)
        assertEquals(listOf<RendererEffect>(RendererEffect.PlayPlayer), fx)
    }

    @Test fun fadeTimerFired_staleGeneration_isInert() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val (s, fx) = reduceRenderer(fading, RendererEvent.FadeTimerFired(fading.mediaGeneration - 1, 42))
        assertEquals(fading, s)
        assertEquals(emptyList<RendererEffect>(), fx)
    }

    @Test fun fadeTimerFired_sessionChanged_neverPausesNewSession() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val (s, fx) = fired(fading, sessionGen = 43)
        assertNull(s.spotifyInterruption) // old session's debt is void
        assertFalse(fx.contains(RendererEffect.PauseSpotify))
        assertEquals(
            listOf(RendererEffect.RestoreSpotifyVolume(0), RendererEffect.PlayPlayer),
            fx,
        )
    }

    @Test fun stop_duringFadeDown_cancelsFadeAndNeverPlays() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val (s, fx) = reduceRenderer(fading, RendererEvent.SoapStop)
        assertEquals(FadePhase.NONE, s.fadePhase)
        assertTrue(fx.contains(RendererEffect.CancelFadeTimer))
        assertFalse(fx.contains(RendererEffect.PlayPlayer))
        assertTrue(s.resumePending) // debt still owed, released via the grace path
    }

    @Test fun setUri_duringFadeDown_reArmsFadeForNewGeneration() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val (s, fx) = reduceRenderer(fading, RendererEvent.SoapSetUri("http://ha/next.mp3", "", "audio/mpeg"))
        assertEquals(FadePhase.DOWN_BEFORE_PLAY, s.fadePhase) // still fading; new media inherits the wait
        assertTrue(fx.contains(RendererEffect.ScheduleFadeTimer(550, s.mediaGeneration)))
    }

    @Test fun abandonedSetUri_duringFadeDown_fireWithoutPlay_pausesOnly() {
        val fading = playFading(SpotifyInterruption.PAUSE).first
        val replaced = reduceRenderer(fading, RendererEvent.SoapSetUri("http://ha/next.mp3", "", "audio/mpeg")).first
        val (s, fx) = fired(replaced) // Play never came: transport is STOPPED
        assertEquals(listOf<RendererEffect>(RendererEffect.PauseSpotify), fx) // no PlayPlayer
        assertEquals(FadePhase.NONE, s.fadePhase)
    }

    @Test fun resumeTimer_duck_restoresWithFade() {
        var s = playFading(SpotifyInterruption.DUCK).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.ResumeTimerFired(receiverPlaying = true, receiverSessionGen = 42))
        assertNull(s2.spotifyInterruption)
        assertEquals(listOf<RendererEffect>(RendererEffect.RestoreSpotifyVolume(500)), fx)
    }

    @Test fun resumeTimer_pauseGuardsPass_resumesSilentThenWaitsForEdge() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.ResumeTimerFired(receiverPlaying = false, receiverSessionGen = 42))
        assertEquals(FadePhase.FADE_IN_PENDING, s2.fadePhase)
        assertEquals(
            listOf(RendererEffect.ResumeSpotify, RendererEffect.ScheduleFadeTimer(FadeInFallback.MS, s.mediaGeneration)),
            fx,
        )
    }

    @Test fun resumeTimer_pauseGuardsFail_stillRestoresVolume() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.ResumeTimerFired(receiverPlaying = true, receiverSessionGen = 42))
        assertNull(s2.spotifyInterruption)
        assertEquals(listOf<RendererEffect>(RendererEffect.RestoreSpotifyVolume(500)), fx)
    }

    @Test fun spotifyEdge_whileFadeInPending_startsTheFadeIn() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        s = reduceRenderer(s, RendererEvent.ResumeTimerFired(false, 42)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.SpotifyStartedPlaying(42))
        assertEquals(FadePhase.NONE, s2.fadePhase)
        assertEquals(
            listOf(RendererEffect.CancelFadeTimer, RendererEffect.RestoreSpotifyVolume(500)),
            fx,
        )
    }

    @Test fun fadeTimer_whileFadeInPending_isTheFallbackRestore() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        s = reduceRenderer(s, RendererEvent.ResumeTimerFired(false, 42)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.FadeTimerFired(s.mediaGeneration, 42))
        assertEquals(FadePhase.NONE, s2.fadePhase)
        assertEquals(listOf<RendererEffect>(RendererEffect.RestoreSpotifyVolume(500)), fx)
    }

    @Test fun soapPlay_freshZeroFadeInterruption_resetsResidualFadeInPending() {
        // Reach FADE_IN_PENDING with its 2s fallback still armed (ResumeTimerFired's pause-mode
        // resume-while-muted path), then take a brand-new zero-fade interruption over it.
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        s = reduceRenderer(s, RendererEvent.ResumeTimerFired(false, 42)).first
        assertEquals(FadePhase.FADE_IN_PENDING, s.fadePhase) // precondition: residual fallback still armed

        val (s2, fx) = reduceRenderer(s, RendererEvent.SoapPlay(true, 99, SpotifyInterruption.PAUSE, fadeMs = 0L))
        assertEquals(FadePhase.NONE, s2.fadePhase) // the stale phase must not survive into the new announcement
        assertTrue(fx.contains(RendererEffect.CancelFadeTimer)) // and its stale fallback runnable must die with it
        assertTrue(fx.contains(RendererEffect.PauseSpotify))
        assertTrue(fx.contains(RendererEffect.PlayPlayer))
    }

    @Test fun userTakeover_pauseOwner_restoresVolumeUnconditionally() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.PlayerPlaying(s.mediaGeneration)).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.SpotifyStartedPlaying(42))
        assertEquals(RendererTransport.STOPPED, s2.transport)
        assertNull(s2.spotifyInterruption)
        assertTrue(fx.contains(RendererEffect.RestoreSpotifyVolume(500)))
        assertTrue(fx.contains(RendererEffect.StopPlayer))
    }

    @Test fun shutdown_pauseOwner_alwaysRestoresVolume() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        val (s2, fx) = reduceRenderer(s, RendererEvent.Shutdown(receiverPlaying = true, receiverSessionGen = 99))
        assertEquals(FadePhase.NONE, s2.fadePhase)
        assertTrue(fx.contains(RendererEffect.RestoreSpotifyVolume(0)))
        assertFalse(fx.contains(RendererEffect.ResumeSpotify)) // guards failed
        assertTrue(fx.contains(RendererEffect.CancelFadeTimer))
    }

    @Test fun shutdown_whileFadeInPending_restoresVolume() {
        var s = playFading(SpotifyInterruption.PAUSE).first
        s = fired(s).first
        s = reduceRenderer(s, RendererEvent.SoapStop).first
        s = reduceRenderer(s, RendererEvent.ResumeTimerFired(false, 42)).first
        val (_, fx) = reduceRenderer(s, RendererEvent.Shutdown(false, 42))
        assertTrue(fx.contains(RendererEffect.RestoreSpotifyVolume(0)))
    }
}
