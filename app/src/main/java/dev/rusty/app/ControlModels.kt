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
