package dev.rusty.app

/**
 * Pure (Android-free) decision logic for how the lyrics screen should react to a new
 * [ReceiverSnapshot] from the store. Extracted from the old `LyricsActivity.onPlayback`
 * broadcast handler so the exact close / re-anchor / track-reload / play-pause rules are
 * unit-testable on the JVM, independent of Android, Coil, or the activity lifecycle.
 *
 * The activity owns all side effects (finishing, re-anchoring its clock, reloading lyrics +
 * artwork, restarting its tick); this object only decides WHAT should happen from the
 * snapshot delta — exactly reproducing the previous behaviour:
 *
 *  - A `Stopped` (old wire "STOPPED") or `Off` display closes the screen. `Off` is what the
 *    store reduces a teardown to, so it must close too — and because [decide] checks close
 *    first, OFF wins even when the snapshot still carries the old track id.
 *  - Otherwise the playback clock is re-anchored from the snapshot's live position + playing
 *    flag (the activity reads these from `store.liveElapsedMs()` / `snapshot.anchor.playing`).
 *  - A changed, non-blank track id triggers a full track reload (lyrics + artwork + title).
 *  - With no track change it is just a play/pause update (keep ticking if playing + synced).
 */
object LyricsPlaybackReaction {

    /** Display-status strings (from the reducer) that mean "leave the lyrics screen". */
    private const val STATUS_STOPPED = "Stopped"
    private const val STATUS_OFF = "Off"
    private const val STATUS_PLAYING = "Playing"

    /**
     * The decision derived from a snapshot delta.
     *
     * @param close finish the activity (stopped/off) — when true, all other flags are irrelevant.
     * @param reanchor re-stamp the playback clock from the snapshot's live position + [playing].
     * @param reloadTrack the track id changed → reload lyrics, artwork, and the track header.
     * @param playing whether playback is currently playing (drives the synced-lyrics tick).
     */
    data class Decision(
        val close: Boolean,
        val reanchor: Boolean,
        val reloadTrack: Boolean,
        val playing: Boolean,
    )

    /**
     * Maps the [snapshot] (against the lyrics screen's current track id) to a [Decision].
     *
     * @param snapshot the freshly delivered store snapshot.
     * @param currentTrackId the track id the lyrics screen is presently showing.
     */
    fun decide(snapshot: ReceiverSnapshot, currentTrackId: String?): Decision {
        val status = snapshot.state.status
        if (status == STATUS_STOPPED || status == STATUS_OFF) {
            // OFF/stopped wins during teardown even if a stale track id is still attached.
            return Decision(close = true, reanchor = false, reloadTrack = false, playing = false)
        }
        val playing = status == STATUS_PLAYING
        val newTrackId = snapshot.state.trackId
        val reloadTrack = !newTrackId.isNullOrBlank() && newTrackId != currentTrackId
        return Decision(close = false, reanchor = true, reloadTrack = reloadTrack, playing = playing)
    }
}
