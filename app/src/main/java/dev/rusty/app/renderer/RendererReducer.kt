package dev.rusty.app.renderer

/**
 * Pure arbitration core: decides transport transitions, player-lifecycle bookkeeping and the
 * Spotify interruption-ownership dance (pause OR duck — the recorded owner drives the release).
 */
fun reduceRenderer(state: RendererState, event: RendererEvent): Pair<RendererState, List<RendererEffect>> =
    when (event) {

        // Rule 1: SoapSetUri. While an interruption is owed, the release timer is RE-ARMED, not
        // cancelled: an abandoned replacement (SetUri never followed by Play) must still release
        // Spotify after the grace period — cancel-only left it paused/ducked forever. A Play that
        // arrives within the grace window cancels the timer (rule 2), preserving the normal
        // Stop → SetUri → Play announcement chain.
        is RendererEvent.SoapSetUri -> {
            val newGen = state.mediaGeneration + 1
            val owner = state.spotifyInterruption
            val owning = owner != null
            val fadingDown = state.fadePhase == FadePhase.DOWN_BEFORE_PLAY
            val timerFx = buildList {
                if (state.resumePending) add(RendererEffect.CancelResumeTimer)
                if (owner != null) add(RendererEffect.ScheduleResumeTimer(ResumeGrace.MS))
                // Mid-fade replacement: the timer is re-armed on the NEW generation (a fire
                // stamped with the old one would be inert), so the replacement still waits
                // for the fade. Schedule replaces the pending runnable in the handler.
                if (fadingDown) add(RendererEffect.ScheduleFadeTimer(state.fadeMs + FadeSlack.MS, newGen))
            }
            val newState = state.copy(
                transport = RendererTransport.STOPPED,
                transportStatus = "OK",
                media = RendererMedia(event.uri, event.metadata, event.mime),
                mediaGeneration = newGen,
                durationMs = null,
                seekable = false,
                resumePending = owning,
                // spotifyInterruption / spotifySessionGeneration / fadePhase / fadeMs preserved:
                // replacing media mid-announcement must not lose the debt or the fade wait.
            )
            newState to timerFx + RendererEffect.PreparePlayer(event.uri, event.mime, newGen)
        }

        // Rule 2: SoapPlay — takes ownership per the event-stamped mode, once. When a fade is
        // requested, the interruption is taken but PlayPlayer is DEFERRED until the fade-down
        // completes (rule 11b); fadeMs = 0 keeps today's exact instant semantics.
        is RendererEvent.SoapPlay -> {
            if (state.media == null) {
                state to emptyList()
            } else if (state.fadePhase == FadePhase.DOWN_BEFORE_PLAY) {
                // Already fading down (replacement chain or duplicate Play): the armed fade
                // timer will start playback; a PlayPlayer here would race the fade. But the
                // grace timer a SetUri may have armed must not fire mid-announcement.
                var s = state.copy(transport = RendererTransport.TRANSITIONING, transportStatus = "OK")
                val fx = mutableListOf<RendererEffect>()
                if (s.resumePending) {
                    fx += RendererEffect.CancelResumeTimer
                    s = s.copy(resumePending = false)
                }
                s to fx
            } else {
                var s = state.copy(transport = RendererTransport.TRANSITIONING, transportStatus = "OK")
                val fx = mutableListOf<RendererEffect>()
                if (s.resumePending) {
                    fx += RendererEffect.CancelResumeTimer
                    s = s.copy(resumePending = false)
                }
                var deferPlay = false
                if (event.spotifyPlaying && s.spotifyInterruption == null) {
                    s = s.copy(
                        spotifyInterruption = event.mixMode,
                        spotifySessionGeneration = event.spotifySessionGen,
                        fadeMs = event.fadeMs,
                    )
                    if (event.fadeMs > 0) {
                        fx += if (event.mixMode == SpotifyInterruption.DUCK)
                            RendererEffect.DuckSpotify(event.fadeMs)
                        else
                            RendererEffect.MuteSpotify(event.fadeMs)
                        fx += RendererEffect.ScheduleFadeTimer(event.fadeMs + FadeSlack.MS, s.mediaGeneration)
                        s = s.copy(fadePhase = FadePhase.DOWN_BEFORE_PLAY)
                        deferPlay = true
                    } else {
                        // A residual FADE_IN_PENDING (from a prior ResumeTimerFired that resumed
                        // while still muted) must not survive into this fresh interruption: its
                        // 2s fallback fade timer is still armed and would fire a premature
                        // RestoreSpotifyVolume mid-announcement. Reset the phase and kill it here.
                        if (s.fadePhase == FadePhase.FADE_IN_PENDING) {
                            s = s.copy(fadePhase = FadePhase.NONE)
                            fx += RendererEffect.CancelFadeTimer
                        }
                        fx += if (event.mixMode == SpotifyInterruption.DUCK)
                            RendererEffect.DuckSpotify(0)
                        else
                            RendererEffect.PauseSpotify
                    }
                }
                if (!deferPlay) fx += RendererEffect.PlayPlayer
                s to fx
            }
        }

        // Rule 3: SoapPause
        RendererEvent.SoapPause ->
            if (state.transport == RendererTransport.PLAYING)
                state.copy(transport = RendererTransport.PAUSED_PLAYBACK) to listOf(RendererEffect.PausePlayer)
            else state to emptyList()

        // Rule 4: SoapStop
        RendererEvent.SoapStop ->
            if (state.media == null) state to emptyList()
            else armResume(state.copy(transport = RendererTransport.STOPPED), listOf(RendererEffect.StopPlayer))

        // Rule 5: SoapSeek
        is RendererEvent.SoapSeek ->
            if ((state.transport == RendererTransport.PLAYING || state.transport == RendererTransport.PAUSED_PLAYBACK) &&
                state.seekable
            ) state to listOf(RendererEffect.SeekPlayer(event.positionMs))
            else state to emptyList()

        // Rule 6 (generation guard) + Rule 7: PlayerReady
        is RendererEvent.PlayerReady ->
            if (event.generation != state.mediaGeneration) state to emptyList()
            else state.copy(durationMs = event.durationMs, seekable = event.seekable) to emptyList()

        // Rule 6 + Rule 8 (amended): PlayerPlaying → PLAYING only from
        // TRANSITIONING/PLAYING/PAUSED_PLAYBACK (stop paths must not be resurrected).
        is RendererEvent.PlayerPlaying ->
            if (event.generation != state.mediaGeneration) {
                state to emptyList()
            } else if (state.transport == RendererTransport.TRANSITIONING ||
                state.transport == RendererTransport.PLAYING ||
                state.transport == RendererTransport.PAUSED_PLAYBACK
            ) {
                state.copy(transport = RendererTransport.PLAYING) to emptyList()
            } else {
                state to emptyList()
            }

        // Rule 6 + Rule 8: PlayerPaused
        is RendererEvent.PlayerPaused ->
            if (event.generation != state.mediaGeneration) {
                state to emptyList()
            } else if (state.transport == RendererTransport.PLAYING || state.transport == RendererTransport.TRANSITIONING) {
                state.copy(transport = RendererTransport.PAUSED_PLAYBACK) to emptyList()
            } else {
                state to emptyList()
            }

        // Rule 6 + Rule 9: PlayerEnded
        is RendererEvent.PlayerEnded ->
            if (event.generation != state.mediaGeneration) state to emptyList()
            else armResume(state.copy(transport = RendererTransport.STOPPED), listOf(RendererEffect.StopPlayer))

        // Rule 6 + Rule 10: PlayerError
        is RendererEvent.PlayerError ->
            if (event.generation != state.mediaGeneration) {
                state to emptyList()
            } else {
                armResume(
                    state.copy(transport = RendererTransport.STOPPED, transportStatus = "ERROR_OCCURRED"),
                    listOf(RendererEffect.StopPlayer),
                )
            }

        // Rule 11: FocusLost
        RendererEvent.FocusLost ->
            if (state.transport == RendererTransport.PLAYING || state.transport == RendererTransport.TRANSITIONING)
                armResume(state.copy(transport = RendererTransport.STOPPED), listOf(RendererEffect.StopPlayer))
            else state to emptyList()

        // Rule 11b: FadeTimerFired — the reducer-owned wait between fade-down and playback,
        // and the fallback for a fade-in whose playing edge never came.
        is RendererEvent.FadeTimerFired -> when (state.fadePhase) {
            FadePhase.DOWN_BEFORE_PLAY ->
                if (event.mediaGeneration != state.mediaGeneration) {
                    state to emptyList()   // stale: media was replaced and re-armed its own timer
                } else {
                    // Play may not have arrived yet (abandoned/slow replacement chain): complete
                    // the interruption either way, but only start playback when Play asked for it.
                    val play = state.transport == RendererTransport.TRANSITIONING
                    if (event.receiverSessionGen != state.spotifySessionGeneration) {
                        // Takeover mid-fade: the interrupted session is gone and the new
                        // session's fresh mixer was never attenuated — void the debt, never
                        // pause the NEW session. The restore is a native no-op, kept for
                        // uniformity ("every debt release restores").
                        state.copy(
                            fadePhase = FadePhase.NONE,
                            spotifyInterruption = null,
                            resumePending = false,
                        ) to buildList {
                            if (state.resumePending) add(RendererEffect.CancelResumeTimer)
                            add(RendererEffect.RestoreSpotifyVolume(0))
                            if (play) add(RendererEffect.PlayPlayer)
                        }
                    } else {
                        state.copy(fadePhase = FadePhase.NONE) to buildList {
                            if (state.spotifyInterruption == SpotifyInterruption.PAUSE) {
                                add(RendererEffect.PauseSpotify)   // pause lands only at silence
                            }
                            if (play) add(RendererEffect.PlayPlayer)
                        }
                    }
                }
            FadePhase.FADE_IN_PENDING ->
                // The playing edge never came (resume failed / very slow): fade in anyway so
                // Spotify can never be left silent.
                state.copy(fadePhase = FadePhase.NONE) to
                    listOf(RendererEffect.RestoreSpotifyVolume(state.fadeMs))
            FadePhase.NONE -> state to emptyList()
        }

        // Rule 12: SpotifyStartedPlaying — "Spotify wins": the user reaching for Spotify yields the
        // renderer. This inference is only SOUND when Spotify actually stopped, i.e. when we own a
        // PAUSE interruption (or own none at all, so Spotify was idle when the announcement began).
        //
        // A DUCK owner never stopped Spotify: it plays, attenuated, for the whole announcement — by
        // design, that IS the mode. So a false→true edge of `playing` there is NOT the user starting
        // Spotify, it is librespot loading a track: PlayerEvent::Loading publishes LOADING, and
        // ReceiverStateStore.anchorFor maps everything that is not PLAYING to playing=false, so any
        // ordinary track change (auto-advance without a completed preload, or the user hitting Next)
        // fabricates a start edge mid-announcement. Acting on it truncated the announcement and
        // released the duck early. The DUCK debt is released by the stop/end/error/focus-loss paths
        // (rules 4/9/10/11 → resume timer → rule 13, which restores unconditionally) and by
        // Shutdown (rule 14) — never from here.
        is RendererEvent.SpotifyStartedPlaying ->
            when {
                // Our own resume coming up: this edge IS the moment to fade the music back in.
                state.fadePhase == FadePhase.FADE_IN_PENDING ->
                    state.copy(fadePhase = FadePhase.NONE) to listOf(
                        RendererEffect.CancelFadeTimer,
                        RendererEffect.RestoreSpotifyVolume(state.fadeMs),
                    )

                state.spotifyInterruption == SpotifyInterruption.DUCK -> state to emptyList()

                state.transport == RendererTransport.PLAYING || state.transport == RendererTransport.TRANSITIONING -> {
                    val fadingDown = state.fadePhase == FadePhase.DOWN_BEFORE_PLAY
                    state.copy(
                        transport = RendererTransport.STOPPED,
                        spotifyInterruption = null,
                        resumePending = false,
                        fadePhase = FadePhase.NONE,
                    ) to buildList {
                        add(RendererEffect.StopPlayer)
                        add(RendererEffect.CancelResumeTimer)
                        if (fadingDown) add(RendererEffect.CancelFadeTimer)
                        // The PAUSE debt may have muted the mixer; silence must not outlive the debt.
                        if (state.spotifyInterruption != null) {
                            add(RendererEffect.RestoreSpotifyVolume(state.fadeMs))
                        }
                    }
                }

                // A PAUSE owner needs no ResumeSpotify — Spotify starting IS the user's resume —
                // but the mixer they resumed into is still muted: restore it (fade-in).
                state.resumePending ->
                    state.copy(spotifyInterruption = null, resumePending = false) to buildList {
                        add(RendererEffect.CancelResumeTimer)
                        if (state.spotifyInterruption != null) {
                            add(RendererEffect.RestoreSpotifyVolume(state.fadeMs))
                        }
                    }

                else -> state to emptyList()
            }

        // Rule 13: ResumeTimerFired — release by the RECORDED owner. PAUSE keeps the guards (do
        // not resume a Spotify the user already resumed, or a different session). DUCK always
        // restores: a restore against a gone/fresh session's mixer is a safe native no-op.
        is RendererEvent.ResumeTimerFired ->
            if (state.resumePending && state.spotifyInterruption != null) {
                val owner = state.spotifyInterruption
                var newState = state.copy(resumePending = false, spotifyInterruption = null)
                val fx: List<RendererEffect> = when (owner) {
                    SpotifyInterruption.DUCK -> listOf(RendererEffect.RestoreSpotifyVolume(state.fadeMs))
                    SpotifyInterruption.PAUSE ->
                        if (!event.receiverPlaying && event.receiverSessionGen == state.spotifySessionGeneration) {
                            if (state.fadeMs > 0) {
                                // Resume while still muted; the fade-in starts on the actual
                                // playing edge (rule 12), with a fallback so a resume the
                                // session never acknowledges can't leave it silent forever.
                                newState = newState.copy(fadePhase = FadePhase.FADE_IN_PENDING)
                                listOf(
                                    RendererEffect.ResumeSpotify,
                                    RendererEffect.ScheduleFadeTimer(FadeInFallback.MS, state.mediaGeneration),
                                )
                            } else {
                                listOf(RendererEffect.ResumeSpotify)
                            }
                        } else {
                            // Guards failed (user already resumed / different session): the
                            // mixer may still be muted — restore is unconditional.
                            listOf(RendererEffect.RestoreSpotifyVolume(state.fadeMs))
                        }
                    null -> emptyList()   // unreachable: guarded above
                }
                newState to fx
            } else {
                state to emptyList()
            }

        // Rule 14: Shutdown — same owner-driven release as rule 13, plus the fade timer and an
        // unconditional restore so silence/attenuation can never outlive the service.
        is RendererEvent.Shutdown -> {
            val fx = mutableListOf<RendererEffect>(
                RendererEffect.StopPlayer,
                RendererEffect.CancelResumeTimer,
                RendererEffect.CancelFadeTimer,
            )
            when (state.spotifyInterruption) {
                SpotifyInterruption.PAUSE -> {
                    fx += RendererEffect.RestoreSpotifyVolume(0)   // instant: nobody left to fade for
                    if (!event.receiverPlaying && event.receiverSessionGen == state.spotifySessionGeneration) {
                        fx += RendererEffect.ResumeSpotify
                    }
                }
                SpotifyInterruption.DUCK -> fx += RendererEffect.RestoreSpotifyVolume(0)
                null ->
                    // A silent resumed session waiting for its fade-in must not be orphaned:
                    // the native mixer outlives this service.
                    if (state.fadePhase == FadePhase.FADE_IN_PENDING) {
                        fx += RendererEffect.RestoreSpotifyVolume(0)
                    }
            }
            state.copy(
                transport = RendererTransport.NO_MEDIA_PRESENT,
                media = null,
                spotifyInterruption = null,
                resumePending = false,
                fadePhase = FadePhase.NONE,
            ) to fx
        }
    }

/** Shared release-arming used by rules 4/9/10/11. A stop that lands mid-fade-down also
 *  cancels the deferred playback — the announcement it was waiting for is gone. */
private fun armResume(state: RendererState, baseEffects: List<RendererEffect>): Pair<RendererState, List<RendererEffect>> {
    var s = state
    var fx = baseEffects
    if (s.fadePhase == FadePhase.DOWN_BEFORE_PLAY) {
        s = s.copy(fadePhase = FadePhase.NONE)
        fx = fx + RendererEffect.CancelFadeTimer
    }
    val owner = s.spotifyInterruption ?: return s to fx
    return s.copy(resumePending = true) to fx + RendererEffect.ScheduleResumeTimer(ResumeGrace.MS)
}
