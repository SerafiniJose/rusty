package dev.rusty.app

import android.util.Log
import android.content.Context

object NativeBridge {
    init {
        try {
            System.loadLibrary("c++_shared")
            System.loadLibrary("spotify_receiver_core")
            Log.d("NativeBridge", "Library loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("NativeBridge", "Failed to load library: ${e.message}")
        }
    }

    //map functions in rust core lib
    external fun initAndroidContext(context: Context, cacheDir: String)
    external fun initLogger()
    external fun startDevice(deviceName: String, deviceId: String, bitrateKbps: Int)
    external fun stopDevice()

    // Transport controls — dispatched to the active session's Spirc handle. These
    // are safe no-ops natively when no Spotify controller is connected.
    external fun play()
    external fun pause()
    external fun nextTrack()
    external fun previousTrack()

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

    // Asynchronously mints a Spotify access token (delivered via SpotifyService.onNativeAccessToken).
    external fun requestAccessToken()
}