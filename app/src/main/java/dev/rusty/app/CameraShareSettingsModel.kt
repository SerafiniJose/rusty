package dev.rusty.app

/**
 * Everything the "Share this camera" settings section SAYS, decided here rather than in
 * [CameraSettingsPanel] — the panel only moves the strings onto views.
 *
 * The section has four inputs that are not the share's own state ([CameraShareStatus]): whether
 * Remote Control is on (a prerequisite — see [CameraShareService.shouldRun]), whether this
 * hardware can serve a share at all, whether the API password gate is armed, and the control
 * server's current address. Composing them in an Activity-bound binder would make the precedence
 * between them ("blocked" beats "off" beats "streaming") untestable, and it is precedence — not
 * any single line — that this section gets wrong most easily.
 */
object CameraShareSettingsModel {

    /** Sharing needs [ControlService]: mDNS advertising and the snapshot endpoint live there. */
    const val CONTROL_OFF_HINT = "Turn on Remote Control in General settings first"

    /** Said after the runtime grant was refused, so the switch does not just silently bounce back. */
    const val PERMISSION_HINT = "Camera permission needed"

    /**
     * The RTSP server and the snapshot endpoint are protected by the SAME password as the rest of
     * the control API ([ControlSettings.requiredPassword]), and that gate defaults to OFF. Leaving
     * it off is a supported posture — it is what every other endpoint already does on this LAN —
     * but a camera is not a volume slider, so the section says plainly what "off" means here
     * instead of quietly streaming the room to anyone who scans port 8554.
     *
     * The CONSEQUENCE only. The paragraph above this line already says the share is "protected by
     * the Remote Control password when one is set", so repeating that here pushed the one thing
     * the reader does not already know to the end of a second sentence.
     */
    const val NO_PASSWORD_WARNING = "Anyone on your network can watch this camera."

    /** One rendered section: what the switch, the status line, the address and the warning show. */
    data class Row(
        val switchChecked: Boolean,
        val switchEnabled: Boolean,
        val status: String,
        /** Empty hides the address row entirely. */
        val url: String,
        /** Null hides the warning line. */
        val warning: String?,
        val summary: SectionSummary,
    )

    private fun mbit(bps: Int) = String.format(java.util.Locale.US, "%.1f", bps / 1_000_000.0)

    fun statusText(enabled: Boolean, state: CameraShareStatus.State): String = when {
        !enabled -> "Off"
        // Enabled but nothing published yet: the service is on its way up (or was just stopped by
        // a re-sync that is about to start it again).
        state is CameraShareStatus.State.Off -> "Starting"
        state is CameraShareStatus.State.Ready -> "Ready, no viewers"
        state is CameraShareStatus.State.Streaming -> {
            val viewers = if (state.viewers == 1) "1 viewer" else "${state.viewers} viewers"
            // The measured picture (width/height/fps/bps) is 0 until the first frame lands — see
            // [CameraShareStatus.State.Streaming] — so an unmeasured stream falls back to the
            // plain "to N viewers" line rather than claiming a 0×0 picture.
            if (state.fps > 0 && state.width > 0) {
                "Streaming ${state.width}×${state.height} at ${state.fps} fps, ${mbit(state.bps)} Mbit/s to $viewers"
            } else "Streaming to $viewers"
        }
        state is CameraShareStatus.State.Unavailable -> "Camera unavailable — ${state.reason}"
        // The service's Unsupported reasons are hardware facts this panel probes for itself (see
        // [unsupportedReason]) and reports in the caller's own words, so this branch is only a
        // fallback for a state published before the panel was bound.
        state is CameraShareStatus.State.Unsupported -> "Encoder unsupported on this device"
        else -> "Off"
    }

    fun urlText(enabled: Boolean, controlUrl: String): String {
        if (!enabled) return ""
        val host = controlUrl.substringAfter("://", "").substringBefore('/').substringBefore(':')
        // The control server publishes an empty URL while it is bound but has no routable LAN
        // address (see [ControlServerStatus.State.Running]); there is no host to hand a viewer yet.
        return if (host.isEmpty()) "Waiting for network" else CameraShareSettings.streamUrl(host)
    }

    fun showLensPicker(lensCount: Int): Boolean = lensCount >= 2

    /** What this device's Wi-Fi link looks like right now, as read by [WifiLinkProbe]. */
    data class WifiLink(val txMbps: Int, val is5GHz: Boolean, val rssiDbm: Int)

    enum class AdviceLevel { GOOD, WARN, BAD }

    data class Advice(val text: String, val level: AdviceLevel)

    /** Always shown under the advice: the check is one-sided, and the sentence says so. */
    const val ADVICE_DETAIL = "Checks this device's Wi-Fi only. A viewer on weak Wi-Fi still stutters."

    /** Usable share of the negotiated transmit rate. 2.4 GHz shares air with everything else in
     *  the house; both are deliberately pessimistic. */
    private const val HEADROOM_5GHZ = 0.40
    private const val HEADROOM_2GHZ = 0.30
    private const val RSSI_FAIR_DBM = -70
    private const val RSSI_WEAK_DBM = -78
    private const val COMFORTABLE_VIEWERS = 3

    private fun words(e: CameraShareSettings.Encoding) = "${e.tier.label} at ${e.resolution.label}"

    /** The words the user picked, then the honest number behind them, per viewer. */
    fun encodingHint(e: CameraShareSettings.Encoding): String =
        "${words(e)}, ${e.frameRate.fps} fps. About ${mbit(e.bitrate)} Mbit/s for each viewer."

    /**
     * What this device's own Wi-Fi can carry at [e]. Null when not on Wi-Fi: Ethernet has nothing
     * useful to say. Measured on the SHARER only — see [ADVICE_DETAIL].
     */
    fun networkAdvice(link: WifiLink?, e: CameraShareSettings.Encoding): Advice? {
        if (link == null) return null
        if (link.rssiDbm < RSSI_WEAK_DBM) {
            return Advice("Weak Wi-Fi signal (−${-link.rssiDbm} dBm). Expect freezes at any quality until this device is closer to the router.", AdviceLevel.BAD)
        }
        val band = if (link.is5GHz) "5 GHz" else "2.4 GHz"
        val headroomMbit = link.txMbps * (if (link.is5GHz) HEADROOM_5GHZ else HEADROOM_2GHZ)
        val viewers = (headroomMbit * 1_000_000 / e.bitrate).toInt()
        val lead = "$band Wi-Fi, ${link.txMbps} Mbit/s link."
        return when {
            viewers <= 0 -> Advice("$lead ${words(e)} does not fit. Try Basic at 480p and 10 fps.", AdviceLevel.BAD)
            viewers < COMFORTABLE_VIEWERS -> {
                val safer = when (e.tier) {
                    CameraShareSettings.Tier.BEST -> "Good is safer."
                    CameraShareSettings.Tier.GOOD -> "Basic is safer."
                    CameraShareSettings.Tier.BASIC -> "Lower the resolution before adding viewers."
                }
                val plural = if (viewers == 1) "viewer" else "viewers"
                val signal = if (link.rssiDbm < RSSI_FAIR_DBM) " Signal is fair (−${-link.rssiDbm} dBm)." else ""
                Advice("$lead ${words(e)}, ${e.frameRate.fps} fps may stutter with more than $viewers $plural. $safer$signal", AdviceLevel.WARN)
            }
            else -> Advice("$lead Room for about $viewers viewers at this quality.", AdviceLevel.GOOD)
        }
    }

    /**
     * Why this device can never share, or null when it can. Same two checks, in the same order, as
     * [CameraShareService.onStartCommand] — but phrased for a settings row, and answered here
     * without starting a service, so the switch can be disabled before the user taps it.
     */
    fun unsupportedReason(hasEncoder: Boolean, lensCount: Int): String? = when {
        !hasEncoder -> "No H.264 encoder on this device"
        lensCount == 0 -> "This device has no camera"
        else -> null
    }

    /**
     * [enabled] is the persisted switch, [state] what the share is actually doing, [controlOn] the
     * Remote Control switch, [unsupportedReason] the answer from [unsupportedReason],
     * [passwordSet] whether `/api` (and so the RTSP stream) is actually password-gated,
     * [controlUrl] the control server's address and [permissionDenied] whether the runtime CAMERA
     * grant was just refused.
     */
    fun row(
        enabled: Boolean,
        state: CameraShareStatus.State,
        controlOn: Boolean,
        unsupportedReason: String?,
        passwordSet: Boolean,
        controlUrl: String,
        permissionDenied: Boolean = false,
    ): Row {
        // Can this share run at all? Not "is it on" — a pref left on while Remote Control is off
        // runs nothing (shouldRun stops the service), so the row must not read as a live share.
        val runnable = controlOn && unsupportedReason == null
        val status = when {
            // Hardware first: it is the only one the user cannot do anything about.
            unsupportedReason != null -> unsupportedReason
            !controlOn -> CONTROL_OFF_HINT
            // The switch reverted itself; say why rather than leave a bare "Off".
            permissionDenied && !enabled -> PERMISSION_HINT
            else -> statusText(enabled, state)
        }
        val live = runnable && enabled &&
            (state is CameraShareStatus.State.Ready || state is CameraShareStatus.State.Streaming)
        return Row(
            switchChecked = enabled,
            switchEnabled = runnable,
            status = status,
            url = urlText(enabled && runnable, controlUrl),
            warning = if (enabled && runnable && !passwordSet) NO_PASSWORD_WARNING else null,
            // Accent only while the share is genuinely up: a collapsed section must not colour
            // "Off", a hint or a failure as if something were configured and working.
            summary = SectionSummary(status, active = live),
        )
    }
}
