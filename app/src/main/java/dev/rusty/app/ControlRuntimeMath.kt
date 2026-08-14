package dev.rusty.app

import kotlin.math.roundToInt

/**
 * The `/api/volume` arithmetic, as pure functions of what [android.media.AudioManager] reports.
 *
 * Extracted from `ControlServiceRuntime` — a private class inside a [ControlService] — purely so
 * that it can be unit-tested off-device, the same reason
 * [dev.rusty.app.renderer.MediaRendererController.shouldRun] is a standalone one-liner. These three
 * decisions are named verbatim in the design doc ("the response reports the *actual* resulting
 * percentage (quantized), plus `fixed` from `isVolumeFixed`. `max <= 0` and exceptions →
 * `volume.fixed: true`"), so they are pinned by tests rather than by inspection.
 */
object ControlVolumeMath {

    /**
     * Whether the music stream cannot be changed. `max <= 0` is folded in on purpose: a device with
     * no controllable music stream (fixed HDMI/dock output) must be reported as *fixed*, never as
     * "0%" — the latter reads as "muted, turn it up", which is a control the caller does not have.
     */
    fun isFixed(max: Int, systemReportsFixed: Boolean): Boolean = max <= 0 || systemReportsFixed

    /** The percentage a stream at [value] of [max] actually sits at. 0 when there is no stream to
     *  divide by, which pairs with [isFixed] reporting that stream as fixed. */
    fun percent(value: Int, max: Int): Int =
        if (max <= 0) 0 else (value * 100f / max).roundToInt()

    /**
     * The stream step a requested [percent] maps to. Rounded (not truncated) so a request lands on
     * the nearest real step, and clamped into `0..max`: the router already validates 0..100, but
     * `setStreamVolume` throws on an out-of-range index and this is the last line before it.
     */
    fun step(percent: Int, max: Int): Int =
        if (max <= 0) 0 else (percent / 100f * max).roundToInt().coerceIn(0, max)
}

/**
 * Assembly of the address set [ControlProtocol]'s DNS-rebinding guard matches the Host header
 * against. Pure for the same reason as [ControlVolumeMath]: the guard is a security control, so
 * "loopback is always allowed" and "a zone suffix never reaches the comparison" should be facts a
 * test states, not ones a reader has to re-derive.
 */
object ControlHosts {

    /**
     * [addresses] are the device's own interface addresses as reported by
     * [java.net.InetAddress.getHostAddress]. Loopback is seeded unconditionally so `adb forward`
     * debugging works even with no network; IPv6 zone suffixes (`fe80::1%wlan0`) are stripped
     * because a Host header never carries one, and blanks/duplicates collapse.
     */
    fun localHosts(addresses: List<String>): Set<String> {
        val hosts = LinkedHashSet<String>()
        hosts += "localhost"
        hosts += "127.0.0.1"
        for (address in addresses) {
            val bare = address.substringBefore('%').trim()
            if (bare.isNotEmpty()) hosts += bare
        }
        return hosts
    }
}
