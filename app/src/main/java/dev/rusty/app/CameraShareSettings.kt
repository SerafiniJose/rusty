package dev.rusty.app

import android.content.SharedPreferences

/** Prefs and fixed wire values for sharing this device's camera (spec: camera-share design). */
object CameraShareSettings {
    const val KEY_ENABLED = "camera_share_enabled"
    const val KEY_LENS = "camera_share_lens"
    /** The pref key keeps its 2.6 name: installs that already chose a resolution keep it. */
    const val KEY_RESOLUTION = "camera_share_quality"
    const val KEY_TIER = "camera_share_tier"
    const val KEY_FPS = "camera_share_fps"
    const val RTSP_PORT = 8554
    const val STREAM_PATH = "/live"
    const val SNAPSHOT_PATH = "/api/camera/local/snapshot.jpg"

    enum class Lens(val pref: String) {
        FRONT("front"), BACK("back");
        companion object {
            fun fromPref(value: String?): Lens = entries.firstOrNull { it.pref == value } ?: FRONT
        }
    }

    /**
     * How big a picture the share sends. A CEILING, not a promise: a camera that does not list the
     * size gets the largest one it does list at or below this (CameraCapturePipeline.pickSize).
     */
    enum class Resolution(val pref: String, val label: String, val width: Int, val height: Int) {
        LOW("low", "480p", 640, 480),
        MEDIUM("medium", "720p", 1280, 720),
        HIGH("high", "1080p", 1920, 1080);

        companion object {
            fun fromPref(value: String?): Resolution = entries.firstOrNull { it.pref == value } ?: MEDIUM
        }
    }

    /** The user-facing word for the bitrate. The number behind it depends on the resolution. */
    enum class Tier(val pref: String, val label: String) {
        BASIC("basic", "Basic"), GOOD("good", "Good"), BEST("best", "Best");

        companion object {
            fun fromPref(value: String?): Tier = entries.firstOrNull { it.pref == value } ?: GOOD
        }
    }

    enum class FrameRate(val pref: String, val fps: Int) {
        FPS_10("10", 10), FPS_15("15", 15), FPS_30("30", 30);

        companion object {
            fun fromPref(value: String?): FrameRate = entries.firstOrNull { it.pref == value } ?: FPS_15
        }
    }

    /**
     * Everything the encoder is configured from, and the wire bitrate budgeted for it.
     *
     * [bitrate] is a TARGET for what leaves the socket: MediaCodec sizes each frame from the frame
     * rate it was configured with, so a camera that delivers more frames than asked overshoots in
     * proportion, and [BitrateGovernor] trims that back at runtime. The table is per viewer at
     * 15 fps; 10 fps costs about three quarters of it and 30 fps about 1.6 times, because bitrate
     * does not grow linearly with frame rate.
     */
    data class Encoding(val resolution: Resolution, val tier: Tier, val frameRate: FrameRate) {
        val bitrate: Int
            get() {
                val at15 = when (resolution) {
                    Resolution.LOW -> when (tier) { Tier.BASIC -> 400_000; Tier.GOOD -> 800_000; Tier.BEST -> 1_200_000 }
                    Resolution.MEDIUM -> when (tier) { Tier.BASIC -> 800_000; Tier.GOOD -> 1_500_000; Tier.BEST -> 2_500_000 }
                    Resolution.HIGH -> when (tier) { Tier.BASIC -> 1_500_000; Tier.GOOD -> 3_000_000; Tier.BEST -> 5_000_000 }
                }
                val factor = when (frameRate) { FrameRate.FPS_10 -> 0.75; FrameRate.FPS_15 -> 1.0; FrameRate.FPS_30 -> 1.6 }
                return Math.round(at15 * factor).toInt()
            }
    }

    fun isEnabled(prefs: SharedPreferences) = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(prefs: SharedPreferences, enabled: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    fun lens(prefs: SharedPreferences): Lens = Lens.fromPref(prefs.getString(KEY_LENS, null))
    fun setLens(prefs: SharedPreferences, lens: Lens) = prefs.edit().putString(KEY_LENS, lens.pref).apply()
    fun resolution(prefs: SharedPreferences): Resolution = Resolution.fromPref(prefs.getString(KEY_RESOLUTION, null))
    fun setResolution(prefs: SharedPreferences, r: Resolution) = prefs.edit().putString(KEY_RESOLUTION, r.pref).apply()
    fun tier(prefs: SharedPreferences): Tier = Tier.fromPref(prefs.getString(KEY_TIER, null))
    fun setTier(prefs: SharedPreferences, t: Tier) = prefs.edit().putString(KEY_TIER, t.pref).apply()
    fun frameRate(prefs: SharedPreferences): FrameRate = FrameRate.fromPref(prefs.getString(KEY_FPS, null))
    fun setFrameRate(prefs: SharedPreferences, f: FrameRate) = prefs.edit().putString(KEY_FPS, f.pref).apply()
    fun encoding(prefs: SharedPreferences) = Encoding(resolution(prefs), tier(prefs), frameRate(prefs))

    fun streamUrl(host: String) = "rtsp://$host:$RTSP_PORT$STREAM_PATH"
    /** [apiPort] is the remote device's control-API port as resolved from mDNS; this device's own is [ControlSettings.PORT]. */
    fun snapshotUrl(host: String, apiPort: Int = ControlSettings.PORT) = "http://$host:$apiPort$SNAPSHOT_PATH"
}
