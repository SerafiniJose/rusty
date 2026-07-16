package dev.rusty.app.renderer

import dev.rusty.app.ReceiverSnapshot

/**
 * Tracks the Spotify receiver from ReceiverStateStore snapshots and provides the
 * arbitration inputs the spec requires: a session GENERATION (bumped whenever the
 * session user changes, including null→user and user→null) and the live playing flag.
 * Emits SpotifyStartedPlaying into [rendererStore] on a paused/idle→playing transition.
 * Pure JVM; register as a ReceiverStateStore.Listener from MediaRendererService.
 */
class RendererSpotifyBridge(private val rendererStore: RendererStore) {
    @Volatile var sessionGeneration: Long = 0; private set
    @Volatile var playing: Boolean = false; private set

    @Volatile private var lastUser: String? = null
    @Volatile private var seenFirst: Boolean = false

    fun snapshot(): Pair<Boolean, Long> = synchronized(this) { playing to sessionGeneration }

    fun onSnapshot(snapshot: ReceiverSnapshot) {
        synchronized(this) {
            val user = snapshot.state.sessionUser
            val nowPlaying = snapshot.anchor.playing
            val wasPlaying = playing

            if (!seenFirst || user != lastUser) {
                sessionGeneration += 1
            }
            lastUser = user
            playing = nowPlaying
            seenFirst = true

            // Intentional: a FIRST snapshot with playing=true also counts as a false→true
            // transition (the pre-state defaults to not-playing). ReceiverStateStore.addListener
            // delivers the current snapshot immediately, so a renderer service (re)starting while
            // Spotify is already playing still enforces "Spotify wins" — arbitration must hold at
            // registration time, not just from the next toggle.
            if (!wasPlaying && nowPlaying) {
                rendererStore.dispatch(RendererEvent.SpotifyStartedPlaying(sessionGeneration))
            }
        }
    }
}
