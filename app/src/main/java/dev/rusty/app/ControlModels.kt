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

/**
 * One row of `GET /api/cameras`.
 *
 * [state] is the wire vocabulary the grid already draws with: the camera's [TileState] lowercased
 * ("ok" | "stale" | "unreachable" | "none"), plus "live" for the camera a live view is currently
 * showing. A camera whose feature screen isn't mounted has no snapshot loop running at all, so it
 * reports "none" — the same thing the grid shows for a camera that has never produced a frame.
 */
data class ControlCamera(val id: String, val name: String, val state: String)

/**
 * Outcome of `POST /api/camera/view` (the summon).
 *
 * [UnknownCamera] is a 400, not a 404: it is a client mistake about an id, matching how
 * `POST /api/panel` treats a panel name it does not know. [FeatureDisabled] IS a 404 — with the
 * Camera feature off, none of these routes exist on this device.
 */
sealed class ControlCameraViewResult {
    /** Accepted; [snapshot] is the PRE-summon state, like every other asynchronously applied
     *  command (see [ControlPanelResult.Ok]). */
    data class Ok(val snapshot: ControlSnapshot) : ControlCameraViewResult()
    object FeatureDisabled : ControlCameraViewResult()
    object UnknownCamera : ControlCameraViewResult()

    /**
     * Rusty is not in front and holds no "Display over other apps" grant, so the summon cannot put
     * the camera on screen. The same underlying check as [ControlForegroundResult.CannotBringForward];
     * reported with the machine-readable `overlay_permission_required` because a summoning
     * automation (Home Assistant, a doorbell trigger) has to branch on it, not display it.
     */
    object OverlayPermissionRequired : ControlCameraViewResult()
}

/** Outcome of `POST /api/camera/dismiss`. Both refusals are 409, like [ControlPanelResult]'s. */
sealed class ControlCameraDismissResult {
    data class Ok(val snapshot: ControlSnapshot) : ControlCameraDismissResult()
    object FeatureDisabled : ControlCameraDismissResult()

    /** Nothing to put back — never summoned, or the user already left with BACK. */
    object NotSummoned : ControlCameraDismissResult()

    /**
     * No app window is attached to take the restore. Refused rather than accepted for the same
     * reason [ControlPanelResult.NoWindow] is: with no host every step of the restore is a silent
     * no-op, so a 200 would report a restore that never happened AND spend the capture, leaving
     * the device stuck on the camera with nothing left to undo it.
     */
    object NoWindow : ControlCameraDismissResult()
}

/**
 * Outcome of `POST /api/camera/grid` — "show the camera grid".
 *
 * Distinct from [ControlCameraDismissResult] on purpose. Dismiss UNDOES a summon and therefore has
 * a "nothing to undo" refusal; this is a destination request, so it succeeds whether or not a
 * summon is in force and has no [ControlCameraDismissResult.NotSummoned] equivalent.
 */
sealed class ControlCameraGridResult {
    data class Ok(val snapshot: ControlSnapshot) : ControlCameraGridResult()
    object FeatureDisabled : ControlCameraGridResult()

    /** No app window is attached to take the panel switch — refused for the same reason
     *  [ControlCameraDismissResult.NoWindow] is: nothing would move, and the capture would still
     *  be spent. */
    object NoWindow : ControlCameraGridResult()
}

/** Outcome of `GET /api/camera/{id}/snapshot`. The 403-when-auth-is-off rule is the ROUTER's, not
 *  this type's — it is decided before the runtime is ever asked. */
sealed class ControlCameraSnapshotResult {
    data class Ok(val jpeg: ByteArray) : ControlCameraSnapshotResult() {
        override fun equals(other: Any?) = this === other ||
            (other is Ok && jpeg.contentEquals(other.jpeg))
        override fun hashCode() = jpeg.contentHashCode()
    }
    object FeatureDisabled : ControlCameraSnapshotResult()
    object UnknownCamera : ControlCameraSnapshotResult()
    /** The camera is known, but no frame has landed yet (grid never mounted, refresh off, or the
     *  first fetch still in flight). */
    object NoFrame : ControlCameraSnapshotResult()
}

/**
 * Outcome of `GET /api/camera/local/snapshot.jpg` — a still from THIS device's OWN shared camera,
 * fetched by another Rusty device to draw its camera-grid thumbnail (see the camera-share design
 * doc). Unlike [ControlCameraSnapshotResult] there is no per-id lookup (there is only ever the one
 * shared camera) and no password-required gate of its own — the route's protection is whatever
 * [ControlAuth] already enforces for the whole `/api/...` surface, Basic included.
 */
sealed class ControlLocalSnapshotResult {
    data class Ok(val jpeg: ByteArray) : ControlLocalSnapshotResult() {
        override fun equals(other: Any?) = this === other || (other is Ok && jpeg.contentEquals(other.jpeg))
        override fun hashCode() = jpeg.contentHashCode()
    }

    /** The `camera_share_enabled` switch is off (or Remote Control is, which forces sharing off
     *  too) — this device is not sharing a camera at all. A 404: the honest reading of "there is
     *  no such resource on this device right now". */
    object SharingOff : ControlLocalSnapshotResult()

    /** Sharing is switched on but no still could be produced right now — [reason] says why
     *  (unsupported hardware, the camera busy/gated, a grab already in flight, ...). A 503: the
     *  caller should retry rather than treat this as a permanent absence.
     *
     *  [reason] is for the local settings row, the foreground notification, and logs ONLY — it can
     *  carry a raw exception message or reveal the camera/mic privacy-switch state, so
     *  [ControlProtocol]'s HTTP handler must never forward it verbatim; see that handler's
     *  `Unavailable` branch for the generic text it answers with instead. */
    data class Unavailable(val reason: String) : ControlLocalSnapshotResult()

    /** Sharing is on and the camera is otherwise fine, but no frame has landed yet (a cold grab
     *  is still in flight with nothing to fall back to). Also a 503. */
    object NoFrame : ControlLocalSnapshotResult()
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
