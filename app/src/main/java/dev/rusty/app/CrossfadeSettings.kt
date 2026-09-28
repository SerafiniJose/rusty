package dev.rusty.app

import android.content.SharedPreferences

/**
 * The Spotify "Crossfade" setting: how long one track overlaps the next, in whole seconds.
 *
 * 0 keeps librespot's gapless playback exactly as before. Anything above it makes the native
 * player fade the ending track out under the incoming one — at the natural end of a track and on
 * a manual skip — the way the official Spotify client does, with the same 12 s cap.
 *
 * Unlike the startup volume this applies LIVE: the native side pushes the new value onto the
 * session that is playing, so it takes effect at the next track boundary. Stored in the same
 * prefs file as the startup volume so every start path (Activity, service, boot) reads one value.
 */
object CrossfadeSettings {
    const val KEY_CROSSFADE_SECONDS = "crossfade_seconds"

    /** On at 4 s out of the box, so the feature is heard rather than found. The pref is only
     *  written when the user moves the slider, so an explicit Off (0) is kept across updates. */
    const val DEFAULT_SECONDS = 4

    const val MIN_SECONDS = 0

    /** Spotify's own ceiling; librespot-playback caps at the same value (upstream PR #1756). */
    const val MAX_SECONDS = 12

    fun seconds(prefs: SharedPreferences): Int =
        clamp(prefs.getInt(KEY_CROSSFADE_SECONDS, DEFAULT_SECONDS))

    fun setSeconds(prefs: SharedPreferences, seconds: Int) {
        prefs.edit().putInt(KEY_CROSSFADE_SECONDS, clamp(seconds)).apply()
    }

    /** Into range, so a stale or hand-edited pref stays usable and never exceeds the player's cap. */
    fun clamp(seconds: Int): Int = seconds.coerceIn(MIN_SECONDS, MAX_SECONDS)

    fun label(seconds: Int): String = when (val clamped = clamp(seconds)) {
        0 -> "Off · gapless"
        1 -> "1 second"
        else -> "$clamped seconds"
    }
}
