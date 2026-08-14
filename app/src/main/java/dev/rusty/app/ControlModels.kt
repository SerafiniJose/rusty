package dev.rusty.app

import org.json.JSONObject

/**
 * Immutable snapshot of screen state (brightness, display mode, control state).
 */
data class ControlScreen(
    val on: Boolean,
    val brightness: Int,
    val mode: String,
    val writable: Boolean,
    val available: Boolean
)

/**
 * Immutable snapshot of volume state (current level, fixed/variable).
 */
data class ControlVolume(
    val value: Int,
    val fixed: Boolean
)

/**
 * Immutable snapshot of playback source state (which player is active).
 */
data class ControlPlaying(
    val spotify: Boolean,
    val dlna: Boolean
)

/**
 * Immutable snapshot of the entire device control state.
 * Serializes to a nested JSON structure for the HTTP API.
 */
data class ControlSnapshot(
    val deviceId: String,
    val deviceName: String,
    val version: String,
    val screen: ControlScreen,
    val volume: ControlVolume,
    val playing: ControlPlaying,
    val slideshowEnabled: Boolean,
) {
    /**
     * Encodes this snapshot as JSON, matching the `GET /api/state` contract.
     * Returns a compact string with nested objects for device, screen, volume, playing, slideshow.
     */
    fun toJson(): String {
        val root = JSONObject()

        // Device info
        val device = JSONObject()
        device.put("id", deviceId)
        device.put("name", deviceName)
        device.put("version", version)
        root.put("device", device)

        // Screen state
        val screenObj = JSONObject()
        screenObj.put("on", screen.on)
        screenObj.put("brightness", screen.brightness)
        screenObj.put("mode", screen.mode)
        screenObj.put("writable", screen.writable)
        screenObj.put("available", screen.available)
        root.put("screen", screenObj)

        // Volume state
        val volumeObj = JSONObject()
        volumeObj.put("value", volume.value)
        volumeObj.put("fixed", volume.fixed)
        root.put("volume", volumeObj)

        // Playing state
        val playingObj = JSONObject()
        playingObj.put("spotify", playing.spotify)
        playingObj.put("dlna", playing.dlna)
        root.put("playing", playingObj)

        // Slideshow state
        val slideshowObj = JSONObject()
        slideshowObj.put("enabled", slideshowEnabled)
        root.put("slideshow", slideshowObj)

        return root.toString()
    }
}

/** Router-level outcome of a remote install request; maps 1:1 to an HTTP status. */
enum class ControlInstallStart { STARTED, NO_UPDATE, BUSY, NO_APK }

/** The newest published release, as the control page needs it. [hasApk]: whether the
 *  release ships an installable APK asset (without one the Update button is pointless). */
data class ControlUpdateLatest(
    val version: String,
    val notes: String,
    val url: String,
    val hasApk: Boolean,
)

/**
 * Combined answer for `GET /api/update`: the (cached) GitHub release check plus the live
 * installer state, so one endpoint serves both the initial render and install polling.
 * [status] is the wire string: "up_to_date" | "update_available" | "error".
 */
data class ControlUpdateCheck(
    val current: String,
    val status: String,
    val latest: ControlUpdateLatest?,
    val install: InstallSnapshot,
) {
    /** Encodes as JSON for the `GET /api/update` contract. `latest` is omitted (not null)
     *  when absent; `install.progress`/`install.error` likewise. */
    fun toJson(): String {
        val root = JSONObject()
        root.put("current", current)
        root.put("status", status)

        latest?.let {
            val latestObj = JSONObject()
            latestObj.put("version", it.version)
            latestObj.put("notes", it.notes)
            latestObj.put("url", it.url)
            latestObj.put("hasApk", it.hasApk)
            root.put("latest", latestObj)
        }

        val installObj = JSONObject()
        installObj.put("phase", install.phase.name.lowercase())
        install.progress?.let { installObj.put("progress", it) }
        install.error?.let { installObj.put("error", it) }
        root.put("install", installObj)

        return root.toString()
    }
}
