package dev.rusty.app

/** Which face the now-playing screen shows. */
enum class VisualState { IDLE, ACTIVE }

/**
 * ACTIVE only while a track is actually loaded/playing/paused — that's the now-playing face.
 * IDLE otherwise (waiting/starting/connected-but-idle/stopped/unavailable/error). Driven by
 * playback status rather than session presence, so the clock returns once playback finishes
 * even though Spotify keeps the account session logged in afterwards.
 */
fun ReceiverDashboardState.visualState(): VisualState =
    if (status == "Playing" || status == "Paused" || status == "Loading") {
        VisualState.ACTIVE
    } else {
        VisualState.IDLE
    }

/**
 * Calm, accurate label + status-dot color id for the IDLE face. Replaces the alarming
 * always-"Offline" header with reassuring copy once the receiver is actually up.
 * Returns (label, colorResId) — caller resolves the color resource.
 */
fun ReceiverDashboardState.idleStatus(): Pair<String, Int> = when (status) {
    // Peeking the clock during playback (or a paused-timeout) still shows what's on — the
    // now-playing track (title — artist) with a musical note, rather than the cold "Ready for Spotify".
    "Playing", "Paused" -> "♪ $trackTitle — $trackArtist" to R.color.dot_green
    "Starting", "Restarting", "Loading" -> "Starting…" to R.color.dot_grey
    "Permission needed" -> "Notifications off — tap settings" to R.color.dot_amber
    "Error", "Unavailable" -> "Offline" to R.color.dot_red
    // Receiver deliberately stopped by the user → neutral grey, with a hint to restart.
    "Off" -> "Receiver off — open settings to start" to R.color.dot_grey
    // "Stopped" = playback finished but the receiver is still listening → stay calm/ready.
    else -> "Ready for Spotify" to R.color.dot_green
}
