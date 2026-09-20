package dev.rusty.app

/**
 * The pure half of `cameraShare` in the control API: what to report for a given set of facts,
 * and what a `POST /api/camera/share` command should do. [ControlService] gathers the facts
 * (prefs, [CameraShareStatus], the window, the grants) and carries out the decision; nothing in
 * here touches Android, so every branch is a JVM test.
 */
object ControlCameraShareModel {

    /** Everything the snapshot is a function of. [controlUrl] is the control server's own URL
     *  (empty while it has no routable address); the stream is reachable at its host. */
    data class Facts(
        val supported: Boolean,
        val enabled: Boolean,
        val state: CameraShareStatus.State,
        val controlUrl: String,
        val lens: CameraShareSettings.Lens,
        val lenses: Int,
        val appForeground: Boolean,
    )

    fun snapshot(f: Facts): ControlCameraShare {
        if (!f.supported) return ControlCameraShare.UNSUPPORTED.copy(lens = f.lens, lenses = f.lenses, appForeground = f.appForeground)
        val status = when {
            !f.enabled -> ControlCameraShareStatus.OFF
            f.state is CameraShareStatus.State.Ready -> ControlCameraShareStatus.READY
            f.state is CameraShareStatus.State.Streaming -> ControlCameraShareStatus.STREAMING
            f.state is CameraShareStatus.State.Unavailable || f.state is CameraShareStatus.State.Unsupported ->
                ControlCameraShareStatus.UNAVAILABLE
            // Switch on, service not up (yet): State.Off here means "on its way", not "off".
            else -> ControlCameraShareStatus.STARTING
        }
        val detail = when (val s = f.state) {
            is CameraShareStatus.State.Streaming ->
                if (s.fps > 0 && s.width > 0) "${s.width}×${s.height} at ${s.fps} fps, ${mbit(s.bps)} Mbit/s" else ""
            is CameraShareStatus.State.Unavailable -> s.reason
            is CameraShareStatus.State.Unsupported -> s.reason
            else -> ""
        }
        val live = status == ControlCameraShareStatus.READY || status == ControlCameraShareStatus.STREAMING
        val host = f.controlUrl.substringAfter("://", "").substringBefore('/').substringBefore(':')
        return ControlCameraShare(
            supported = true,
            enabled = f.enabled,
            status = status,
            viewers = (f.state as? CameraShareStatus.State.Streaming)?.viewers ?: 0,
            detail = if (f.enabled) detail else "",
            url = if (live && host.isNotEmpty()) CameraShareSettings.streamUrl(host) else null,
            lens = f.lens,
            lenses = f.lenses,
            appForeground = f.appForeground,
        )
    }

    private fun mbit(bps: Int): String {
        val m = bps / 1_000_000.0
        return if (m >= 10) m.toInt().toString() else String.format(java.util.Locale.US, "%.1f", m)
    }

    sealed class Decision {
        data class Refuse(val result: ControlCameraShareResult) : Decision()
        /** Carry out: write [on] and/or [lens] to prefs, sync the service, and — when
         *  [bringForward] — put Rusty's window in front BEFORE the service starts. */
        data class Apply(val on: Boolean?, val lens: CameraShareSettings.Lens?, val bringForward: Boolean) : Decision()
    }

    /**
     * Turning ON is the only half with preconditions: the CAMERA grant (a service cannot ask for
     * it) and Rusty's window on screen (Android refuses a camera foreground service from the
     * background), the latter satisfiable by bringing the window forward when the overlay grant
     * allows it. OFF and a lens change are preference writes and always apply.
     */
    fun decide(
        command: ControlCameraShareCommand,
        supported: Boolean,
        permissionGranted: Boolean,
        appForeground: Boolean,
        canBringForward: Boolean,
    ): Decision {
        if (!supported) return Decision.Refuse(ControlCameraShareResult.Unsupported)
        if (command.on != true) return Decision.Apply(command.on, command.lens, bringForward = false)
        if (!permissionGranted) return Decision.Refuse(ControlCameraShareResult.PermissionNeeded)
        if (appForeground) return Decision.Apply(true, command.lens, bringForward = false)
        if (!canBringForward) return Decision.Refuse(ControlCameraShareResult.NeedsForeground)
        return Decision.Apply(true, command.lens, bringForward = true)
    }
}
