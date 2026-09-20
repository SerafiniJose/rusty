package dev.rusty.app.renderer

/** Which [RendererPlaybackCore] an announcement is played through. */
enum class AnnounceHost {
    /** The core owned by a running [MediaRendererService] — the DLNA player screen and GENA
     *  subscribers see the announcement, exactly as they do for a Home Assistant one. */
    RENDERER,

    /** A private core owned by the control service, built because no DLNA player is running. */
    LOCAL,
}

/**
 * Pure arbitration between the two pipelines that can play an announcement.
 *
 * Announcements do not need the DLNA player: the sound is made by an ExoPlayer plus the reducer's
 * Spotify choreography ([RendererPlaybackCore]), and SSDP/HTTP/GENA contribute nothing to it. But
 * when the DLNA player IS running, its core is the one that must play — a second player would put
 * two clips on the speaker at once, and, worse, two Spotify interruptions in flight against a
 * process-global attenuation: whichever pipeline finished first would restore the music underneath
 * the other one's announcement.
 *
 * Hence the rule below: the renderer wins whenever it is alive AND the local core has nothing left
 * to finish. A local core that is still mid-announcement keeps the next one too, so the handover
 * only ever happens on a settled pipeline.
 */
object AnnouncementRouting {

    /**
     * True while this pipeline still has work — or a debt to Spotify — outstanding.
     *
     * Deliberately wider than "the clip is playing": after the audio stops the reducer holds the
     * pause/duck for [ResumeGrace.MS] before releasing it, and the fade phases straddle both edges
     * of playback. Every one of those states means this core, and only this core, owes Spotify a
     * release.
     */
    fun busy(state: RendererState): Boolean {
        val settled = state.transport == RendererTransport.NO_MEDIA_PRESENT ||
            state.transport == RendererTransport.STOPPED
        return !settled ||
            state.spotifyInterruption != null ||
            state.resumePending ||
            state.fadePhase != FadePhase.NONE
    }

    /** [localState] is null when no local core has been built (the common case). */
    fun host(rendererAlive: Boolean, localState: RendererState?): AnnounceHost =
        if (rendererAlive && (localState == null || !busy(localState))) AnnounceHost.RENDERER
        else AnnounceHost.LOCAL

    /**
     * Whether the local core can be torn down. Only once the renderer is there to take over: with
     * no DLNA player running it is the only pipeline there is, and rebuilding it per announcement
     * would throw away a warm ExoPlayer for nothing.
     */
    fun shouldReleaseLocal(rendererAlive: Boolean, localState: RendererState?): Boolean =
        rendererAlive && localState != null && !busy(localState)
}
