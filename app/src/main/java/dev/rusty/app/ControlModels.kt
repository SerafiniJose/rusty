package dev.rusty.app

import org.json.JSONArray
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
 * Immutable snapshot of the lockscreen (screensaver): which theme it will mount, and which themes
 * the remote is allowed to pick. [themes] is not simply every [ScreensaverThemeId] — see
 * [ControlLockscreenThemes.selectable].
 */
data class ControlLockscreen(
    val theme: ScreensaverThemeId,
    val themes: List<ScreensaverThemeId>,
)

/**
 * Immutable snapshot of what the device's screen is showing and what it could show.
 *
 * [active] is null when no app window is attached to take a switch — the service runs on boot
 * without an Activity, and after `onPause` the shell can no longer commit a fragment transaction.
 * It is the same "can this take effect right now?" question as `screen.available`, and the control
 * page reads it the same way: null means the lamps are inert until Rusty is back on screen.
 *
 * [available] lists what the remote may switch to, in ring order: the ENABLED features (a feature
 * switched off in settings is not a place you can go) plus [ControlPanelId.LOCKSCREEN], which is
 * always reachable because the screensaver is not a feature and cannot be disabled.
 */
data class ControlPanel(
    val active: ControlPanelId?,
    val available: List<ControlPanelId>,
    val lockscreen: ControlLockscreen,
)

/**
 * Immutable snapshot of Rusty's own window: whether it is the thing on screen, and whether the
 * remote is able to put it there.
 *
 * [foreground] is the same fact as `panel.active != null` and is derived from the same source
 * ([PanelControlRelay]) so the two can never disagree — it is reported separately because a
 * two-state switch binds to a boolean, and making a control page infer it from a null panel id
 * is the kind of subtlety that produces a switch stuck in the wrong position.
 *
 * [canBringForward] is the genuinely new fact: whether the "Display over other apps" grant is
 * held, without which Android silently drops a background activity start. It gates BOTH
 * directions, not just the obvious one — see [ControlForegroundResult].
 */
data class ControlApp(
    val foreground: Boolean,
    val canBringForward: Boolean,
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
    val panel: ControlPanel,
    val app: ControlApp,
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

        // Panel state. `active` is written as an explicit JSON null rather than omitted (the
        // treatment `latest` gets in ControlUpdateCheck) because a client MUST distinguish "no app
        // window, nothing is switchable" from "this build is too old to report a panel" — an
        // always-present key makes that a value check rather than a version guess.
        val panelObj = JSONObject()
        panelObj.put("active", panel.active?.wire ?: JSONObject.NULL)
        panelObj.put("available", JSONArray(panel.available.map { it.wire }))
        val lockscreenObj = JSONObject()
        lockscreenObj.put("theme", ControlLockscreenThemes.wire(panel.lockscreen.theme))
        lockscreenObj.put("themes", JSONArray(panel.lockscreen.themes.map { ControlLockscreenThemes.wire(it) }))
        panelObj.put("lockscreen", lockscreenObj)
        root.put("panel", panelObj)

        // App window state
        val appObj = JSONObject()
        appObj.put("foreground", app.foreground)
        appObj.put("canBringForward", app.canBringForward)
        root.put("app", appObj)

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
