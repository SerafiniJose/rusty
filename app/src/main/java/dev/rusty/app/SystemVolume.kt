package dev.rusty.app

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * The device's media stream, used as the receiver's one and only volume.
 *
 * Rusty hands the Connect volume to Android: the native mixer reports what Spotify asked for (so
 * the apps' slider stays truthful) but does not attenuate the samples — this stream does. The
 * hardware volume keys, the remote-control page and Spotify's own slider therefore all describe
 * the same level, and it survives casts because Android remembers the stream volume.
 *
 * The index/percent mapping is pure so the rounding is unit-testable without an AudioManager.
 */
object SystemVolume {
    /** [AudioManager.STREAM_MUSIC] — the stream the native AudioTrack writes to. */
    const val STREAM = AudioManager.STREAM_MUSIC

    /** Current media volume as 0..100, or null when the platform reports a fixed stream. */
    fun percent(context: Context): Int? = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        if (audio.isVolumeFixed) return null
        toPercent(audio.getStreamVolume(STREAM), audio.getStreamMaxVolume(STREAM))
    }.getOrNull()

    /** Sets the media volume to the nearest step for [percent]. False when unavailable or fixed. */
    fun setPercent(context: Context, percent: Int): Boolean = runCatching {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val max = audio.getStreamMaxVolume(STREAM)
        if (audio.isVolumeFixed || max <= 0) return false
        audio.setStreamVolume(STREAM, toIndex(percent, max), 0)
        true
    }.getOrDefault(false)

    /** Stream index -> 0..100, or null for a bogus range. */
    fun toPercent(index: Int, max: Int): Int? {
        if (max <= 0) return null
        return (index.toDouble() / max * 100.0).roundToInt().coerceIn(0, 100)
    }

    /** 0..100 -> the nearest stream index in 0..[max]. */
    fun toIndex(percent: Int, max: Int): Int {
        if (max <= 0) return 0
        return (percent.coerceIn(0, 100) / 100.0 * max).roundToInt().coerceIn(0, max)
    }
}
