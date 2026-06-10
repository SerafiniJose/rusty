package dev.rusty.app

import android.os.SystemClock

/**
 * Process-wide cache of the most recent merged [ReceiverDashboardState].
 *
 * Service → UI updates are delivered as fire-and-forget broadcasts, which only
 * reach an Activity while it is started. An Activity that is stopped — behind the
 * Now Playing screen, or during the Echo's screensaver — misses those updates and
 * would otherwise show a stale track/cover when it returns.
 *
 * The foreground Activity (every render, including the 1 Hz elapsed tick) and the
 * [SpotifyService] (every status/playback publish) both keep this current, and each
 * Activity seeds itself from it in `onStart`, so the dashboard always reflects the
 * latest playback regardless of which screen received the update.
 */
object DashboardStateHolder {
    @Volatile
    var current: ReceiverDashboardState = ReceiverDashboardState.waiting("Android Speaker")

    // ---- Playback position anchor ----
    //
    // `current.elapsedMs` only advances while NowPlayingActivity's 1 Hz tick is running,
    // i.e. while that screen is in the foreground. When LyricsActivity (or the screensaver)
    // is on top, NowPlaying is stopped and `current.elapsedMs` freezes — so a screen that
    // seeds its playback clock from it gets a stale position and its synced lyrics drift
    // behind the song on every re-open.
    //
    // The anchor below is set only from ground-truth native playback events
    // (SpotifyService.publishPlayback) and carries the realtime instant it was captured, so
    // `liveElapsedMs()` can extrapolate the true current position at any time, on any screen,
    // independent of which Activity happens to be foreground.
    @Volatile private var anchorElapsedMs: Long = 0L
    @Volatile private var anchorRealtime: Long = 0L

    @Volatile
    var isPlayingAnchor: Boolean = false
        private set

    /** Records the latest ground-truth playback position from a native event. */
    fun anchorPlayback(elapsedMs: Long, playing: Boolean) {
        anchorElapsedMs = elapsedMs.coerceAtLeast(0L)
        anchorRealtime = SystemClock.elapsedRealtime()
        isPlayingAnchor = playing
    }

    /** The true current playback position, extrapolated from the last anchor while playing. */
    fun liveElapsedMs(): Long =
        if (isPlayingAnchor) anchorElapsedMs + (SystemClock.elapsedRealtime() - anchorRealtime)
        else anchorElapsedMs
}
