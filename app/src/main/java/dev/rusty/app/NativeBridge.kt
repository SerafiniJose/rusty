package dev.rusty.app

import android.util.Log
import android.content.Context

object NativeBridge {
    init {
        try {
            // The core NEEDs only liblog/libdl/libm/libc (no libc++, no libaaudio) — see docs/build.md.
            System.loadLibrary("spotify_receiver_core")
            Log.d("NativeBridge", "Library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeBridge", "Failed to load library: ${e.message}")
        }
    }

    //map functions in rust core lib
    external fun initAndroidContext(context: Context, cacheDir: String)
    external fun initLogger()
    external fun startDevice(
        deviceName: String,
        deviceId: String,
        bitrateKbps: Int,
        startupVolumePercent: Int,
    )
    external fun stopDevice()

    // Transport controls — dispatched to the active session's Spirc handle. These
    // are safe no-ops natively when no Spotify controller is connected.
    external fun play()
    external fun pause()
    external fun nextTrack()
    external fun previousTrack()

    /**
     * Seeks the current track to [positionMs]. Routed through Spirc, so the controlling Spotify
     * app follows and the receiver confirms by republishing the position (a PLAYING/PAUSED event
     * with a new [PlaybackAnchor.generation]). Spirc silently ignores a target past the track's
     * end — clamp to the duration you know before calling. Safe no-op without a session.
     */
    external fun seek(positionMs: Int)

    /**
     * Fades the audible Spotify volume to [factor] (1.0 = full, 0.0 = silence) over [fadeMs].
     * The Connect volume slider never sees the attenuation. Safe no-op without a session.
     */
    external fun setSpotifyAttenuation(factor: Float, fadeMs: Int)

    /**
     * Duck depth for "Lower volume" announcements, as an amplitude ratio: 0.2 ~= -14 dB —
     * quieter but unmistakably still playing (a user who wants silence picks "Pause Spotify").
     * Applied by the native mixer in the MAPPED domain, so the duck is the same -14 dB at
     * every Connect volume position (see rust/src/duck.rs for the curve math).
     */
    const val DUCK_FACTOR = 0.2f

    // Renames the running receiver in place (re-advertises mDNS under the new name)
    // without restarting the foreground service or runtime.
    external fun renameDevice(deviceName: String)

    /**
     * Sets the volume (0..=100) a NEW Spotify Connect session starts at. Takes effect on the
     * next controller that connects; a session already playing keeps its current volume, so
     * moving the settings slider never jolts the room. Safe no-op before the receiver starts —
     * the native side keeps the value in a process-wide slot, not in the session.
     */
    external fun setStartupVolume(percent: Int)

    /**
     * Sets the crossfade between tracks (0..=12 whole seconds, 0 = gapless). Unlike the startup
     * volume this applies to the session playing right now — the native player honours it at the
     * next track boundary or manual skip — and to every session after it. Safe no-op for the
     * live part before a session exists; the value is still remembered.
     */
    external fun setCrossfadeSeconds(seconds: Int)

    // Asynchronously mints a Spotify access token (delivered via SpotifyService.onNativeAccessToken).
    external fun requestAccessToken()
}