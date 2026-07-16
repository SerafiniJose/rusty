package dev.rusty.app.renderer

enum class RendererTransport { NO_MEDIA_PRESENT, STOPPED, TRANSITIONING, PLAYING, PAUSED_PLAYBACK }

/** Where the announcement fade choreography stands. */
enum class FadePhase {
    NONE,
    /** Fading Spotify down; PlayPlayer is deferred until the fade timer fires. */
    DOWN_BEFORE_PLAY,
    /** Spotify resumed silent; waiting for its playing edge (or the fallback timer) to fade in. */
    FADE_IN_PENDING,
}

data class RendererMedia(val uri: String, val metadata: String, val mime: String?)

data class RendererState(
    val transport: RendererTransport = RendererTransport.NO_MEDIA_PRESENT,
    val transportStatus: String = "OK",              // "OK" | "ERROR_OCCURRED"
    val media: RendererMedia? = null,
    val mediaGeneration: Long = 0,                   // bumped by every SetUri; stale player events are dropped
    val durationMs: Long? = null,                    // null until PlayerReady; null = unknown
    val seekable: Boolean = false,
    val spotifyInterruption: SpotifyInterruption? = null,   // which interruption we owe Spotify a release for
    val spotifySessionGeneration: Long = 0,          // generation captured when we paused
    val resumePending: Boolean = false,              // deferred resume armed (replacement grace period)
    val fadePhase: FadePhase = FadePhase.NONE,
    val fadeMs: Long = 0,                            // fade stamped when the interruption was taken
) {
    /** Dynamic CurrentTransportActions (spec Architecture 4). */
    fun currentTransportActions(): String = when (transport) {
        RendererTransport.NO_MEDIA_PRESENT -> ""
        RendererTransport.STOPPED -> "Play"
        RendererTransport.TRANSITIONING -> "Stop"
        RendererTransport.PLAYING -> if (seekable) "Pause,Stop,Seek" else "Pause,Stop"
        RendererTransport.PAUSED_PLAYBACK -> if (seekable) "Play,Stop,Seek" else "Play,Stop"
    }
}

/** Everything that can happen to the renderer, from any thread. `spotifyPlaying` /
 *  `spotifySessionGen` snapshots are captured by the dispatcher AT DISPATCH TIME from
 *  RendererSpotifyBridge so the reducer stays pure. */
sealed class RendererEvent {
    data class SoapSetUri(val uri: String, val metadata: String, val mime: String?) : RendererEvent()
    data class SoapPlay(
        val spotifyPlaying: Boolean,
        val spotifySessionGen: Long,
        val mixMode: SpotifyInterruption,
        val fadeMs: Long = 0,                    // 0 = off; stamped from prefs at dispatch time
    ) : RendererEvent()
    object SoapPause : RendererEvent()
    object SoapStop : RendererEvent()
    data class SoapSeek(val positionMs: Long) : RendererEvent()
    data class PlayerReady(val generation: Long, val durationMs: Long?, val seekable: Boolean) : RendererEvent()
    data class PlayerPlaying(val generation: Long) : RendererEvent()
    data class PlayerPaused(val generation: Long) : RendererEvent()
    data class PlayerEnded(val generation: Long) : RendererEvent()
    data class PlayerError(val generation: Long, val message: String) : RendererEvent()
    object FocusLost : RendererEvent()                            // transient OR permanent → stop HA audio
    data class SpotifyStartedPlaying(val sessionGen: Long) : RendererEvent()
    data class ResumeTimerFired(val receiverPlaying: Boolean, val receiverSessionGen: Long) : RendererEvent()
    /** The fade timer elapsed. Generation-stamped at SCHEDULE time so a fire that outlives its
     *  media (SetUri bumped the generation) is inert; receiverSessionGen is snapshotted at fire
     *  time so a takeover mid-fade never pauses the new session. */
    data class FadeTimerFired(val mediaGeneration: Long, val receiverSessionGen: Long) : RendererEvent()
    data class Shutdown(val receiverPlaying: Boolean, val receiverSessionGen: Long) : RendererEvent()
}

/** Side effects the store hands to its EffectHandler, in order. */
sealed class RendererEffect {
    data class PreparePlayer(val uri: String, val mime: String?, val generation: Long) : RendererEffect()
    object PlayPlayer : RendererEffect()
    object PausePlayer : RendererEffect()
    object StopPlayer : RendererEffect()
    data class SeekPlayer(val positionMs: Long) : RendererEffect()
    object PauseSpotify : RendererEffect()
    object ResumeSpotify : RendererEffect()
    /** Fade the Spotify volume to the duck level (NativeBridge.DUCK_FACTOR) over [fadeMs]. */
    data class DuckSpotify(val fadeMs: Long) : RendererEffect()
    /** Fade the Spotify volume to silence over [fadeMs] (pause mode's pre-pause fade). */
    data class MuteSpotify(val fadeMs: Long) : RendererEffect()
    /** Fade the Spotify volume back to full over [fadeMs]. Emitted unconditionally by every
     *  debt-release path — a restore against an unattenuated (or gone) mixer is a native no-op,
     *  and unconditional emission is what guarantees pause mode's silence can't outlive the debt. */
    data class RestoreSpotifyVolume(val fadeMs: Long) : RendererEffect()
    data class ScheduleFadeTimer(val delayMs: Long, val mediaGeneration: Long) : RendererEffect()
    object CancelFadeTimer : RendererEffect()
    /**
     * Arms the release of whatever interruption we owe Spotify, after a grace period.
     *
     * The grace exists to absorb HA's announcement chain: a multi-part announcement arrives as
     * Stop → SetUri → Play, so releasing the instant the first part ends would let Spotify come
     * back for the gap and be interrupted again. The delay ([ResumeGrace.MS]) is decided here in
     * the pure layer, not in the service, so a test can pin it.
     */
    data class ScheduleResumeTimer(val delayMs: Long) : RendererEffect()
    object CancelResumeTimer : RendererEffect()
}

/**
 * How long the renderer waits, after its audio stops, before releasing Spotify — whether the
 * interruption it owes is a pause or a duck.
 *
 * Kept as short as the announcement chain allows: under a PAUSE interruption this window is dead
 * air the user hears, and under a DUCK one it is music still sitting at reduced volume. It cannot
 * go to zero — HA delivers a multi-part announcement as separate Stop → SetUri → Play chains, and
 * releasing in the gap would let Spotify surge back between the parts only to be interrupted again.
 */
object ResumeGrace {
    const val MS = 1_000L
}

/** Scheduled on top of the fade duration so the native ramp's final (deadline-clamped) step
 *  always lands before the timer starts the announcement. */
object FadeSlack {
    const val MS = 50L
}

/** How long a silent resumed Spotify may wait for its playing edge before the fade-in runs
 *  anyway. Covers a resume the session never acknowledges. */
object FadeInFallback {
    const val MS = 2_000L
}
