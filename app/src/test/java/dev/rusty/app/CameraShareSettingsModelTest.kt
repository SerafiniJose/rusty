package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraShareSettingsModelTest {

    private val running = CameraShareStatus.State.Streaming(1)

    @Test fun `status text covers every state`() {
        assertEquals("Off", CameraShareSettingsModel.statusText(false, CameraShareStatus.State.Off))
        assertEquals("Starting", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Off))
        assertEquals("Ready, no viewers", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Ready))
        assertEquals("Streaming to 1 viewer", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Streaming(1)))
        assertEquals("Streaming to 3 viewers", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Streaming(3)))
        assertEquals("Camera unavailable — camera gated", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Unavailable("camera gated")))
        assertEquals("Encoder unsupported on this device", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Unsupported("x")))
    }

    @Test fun `url text derives the host from the control url`() {
        assertEquals("rtsp://192.168.7.116:8554/live", CameraShareSettingsModel.urlText(true, "http://192.168.7.116:8765"))
        assertEquals("Waiting for network", CameraShareSettingsModel.urlText(true, ""))
        assertEquals("", CameraShareSettingsModel.urlText(false, "http://192.168.7.116:8765"))
    }

    @Test fun `lens picker only with two or more cameras`() {
        assertFalse(CameraShareSettingsModel.showLensPicker(1)); assertTrue(CameraShareSettingsModel.showLensPicker(2))
    }

    // -- what this device can share at all -------------------------------------------------------

    @Test fun `hardware that cannot serve the share names the reason`() {
        assertEquals("No H.264 encoder on this device", CameraShareSettingsModel.unsupportedReason(hasEncoder = false, lensCount = 2))
        assertEquals("This device has no camera", CameraShareSettingsModel.unsupportedReason(hasEncoder = true, lensCount = 0))
        assertNull(CameraShareSettingsModel.unsupportedReason(hasEncoder = true, lensCount = 1))
    }

    @Test fun `the encoder check wins when both hardware checks would fail`() {
        assertEquals("No H.264 encoder on this device", CameraShareSettingsModel.unsupportedReason(hasEncoder = false, lensCount = 0))
    }

    // -- the composed row ------------------------------------------------------------------------

    private fun row(
        enabled: Boolean = true,
        state: CameraShareStatus.State = running,
        controlOn: Boolean = true,
        unsupportedReason: String? = null,
        passwordSet: Boolean = true,
        controlUrl: String = "http://192.168.7.116:8765",
        permissionDenied: Boolean = false,
    ) = CameraShareSettingsModel.row(
        enabled = enabled,
        state = state,
        controlOn = controlOn,
        unsupportedReason = unsupportedReason,
        passwordSet = passwordSet,
        controlUrl = controlUrl,
        permissionDenied = permissionDenied,
    )

    @Test fun `Remote Control off disables the switch and says where to turn it on`() {
        val r = row(enabled = false, state = CameraShareStatus.State.Off, controlOn = false)
        assertFalse(r.switchEnabled)
        assertEquals("Turn on Remote Control in General settings first", r.status)
        assertEquals("", r.url)
    }

    @Test fun `a share pref left on while Remote Control is off still reads as blocked`() {
        // shouldRun() stops the service in this state, so the row must not claim to be sharing.
        val r = row(enabled = true, state = CameraShareStatus.State.Off, controlOn = false)
        assertTrue(r.switchChecked)
        assertFalse(r.switchEnabled)
        assertEquals("Turn on Remote Control in General settings first", r.status)
        assertFalse(r.summary.active)
        assertNull(r.warning)
    }

    @Test fun `hardware that cannot share wins over every other line`() {
        val r = row(enabled = false, state = CameraShareStatus.State.Off, controlOn = false, unsupportedReason = "This device has no camera")
        assertFalse(r.switchEnabled)
        assertEquals("This device has no camera", r.status)
    }

    @Test fun `an open share with no password warns`() {
        val r = row(passwordSet = false)
        assertEquals(CameraShareSettingsModel.NO_PASSWORD_WARNING, r.warning)
        // States the CONSEQUENCE only: the paragraph above the warning already says the share is
        // protected by the Remote Control password when one is set.
        assertTrue(r.warning!!.contains("watch this camera"))
    }

    @Test fun `nothing to warn about while the share is off, blocked or protected`() {
        assertNull(row(passwordSet = true).warning)
        assertNull(row(enabled = false, state = CameraShareStatus.State.Off, passwordSet = false).warning)
        assertNull(row(controlOn = false, passwordSet = false).warning)
        assertNull(row(unsupportedReason = "No H.264 encoder on this device", passwordSet = false).warning)
    }

    @Test fun `the address shows only while the share can actually run`() {
        assertEquals("rtsp://192.168.7.116:8554/live", row().url)
        assertEquals("", row(enabled = false, state = CameraShareStatus.State.Off).url)
        assertEquals("", row(controlOn = false).url)
        assertEquals("", row(unsupportedReason = "This device has no camera").url)
        assertEquals("Waiting for network", row(controlUrl = "").url)
    }

    @Test fun `a denied permission explains why the switch bounced back`() {
        val r = row(enabled = false, state = CameraShareStatus.State.Off, permissionDenied = true)
        assertFalse(r.switchChecked)
        assertTrue(r.switchEnabled)
        assertEquals("Camera permission needed", r.status)
    }

    @Test fun `the header summary is the status line, accented only while the share is up`() {
        assertEquals("Streaming to 1 viewer", row().summary.text)
        assertTrue(row().summary.active)
        assertTrue(row(state = CameraShareStatus.State.Ready).summary.active)
        assertFalse(row(state = CameraShareStatus.State.Off).summary.active)
        assertFalse(row(state = CameraShareStatus.State.Unavailable("port 8554 busy")).summary.active)
        assertEquals("Camera unavailable — port 8554 busy", row(state = CameraShareStatus.State.Unavailable("port 8554 busy")).summary.text)
        assertFalse(row(enabled = false, state = CameraShareStatus.State.Off).summary.active)
    }
    private fun enc(
        r: CameraShareSettings.Resolution = CameraShareSettings.Resolution.MEDIUM,
        t: CameraShareSettings.Tier = CameraShareSettings.Tier.GOOD,
        f: CameraShareSettings.FrameRate = CameraShareSettings.FrameRate.FPS_15,
    ) = CameraShareSettings.Encoding(r, t, f)

    @Test fun `streaming status names the measured picture once there is one`() {
        assertEquals("Streaming to 1 viewer", CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Streaming(1, 1280, 720)))
        assertEquals(
            "Streaming 1280×720 at 30 fps, 1.4 Mbit/s to 1 viewer",
            CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Streaming(1, 1280, 720, 30, 1_400_000)),
        )
        assertEquals(
            "Streaming 640×480 at 10 fps, 0.4 Mbit/s to 3 viewers",
            CameraShareSettingsModel.statusText(true, CameraShareStatus.State.Streaming(3, 640, 480, 10, 400_000)),
        )
    }

    @Test fun `the hint names the words chosen and the honest number`() {
        assertEquals("Good at 720p, 15 fps. About 1.5 Mbit/s for each viewer.", CameraShareSettingsModel.encodingHint(enc()))
        assertEquals(
            "Best at 1080p, 30 fps. About 8.0 Mbit/s for each viewer.",
            CameraShareSettingsModel.encodingHint(enc(CameraShareSettings.Resolution.HIGH, CameraShareSettings.Tier.BEST, CameraShareSettings.FrameRate.FPS_30)),
        )
    }

    @Test fun `no wifi link means no advice`() {
        assertNull(CameraShareSettingsModel.networkAdvice(null, enc()))
    }

    @Test fun `a strong 5 GHz link is green with a viewer estimate`() {
        val a = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(433, true, -52), enc())!!
        assertEquals(CameraShareSettingsModel.AdviceLevel.GOOD, a.level)
        // 433 × 0.4 = 173.2 Mbit/s headroom ÷ 1.5 = 115 viewers
        assertEquals("5 GHz Wi-Fi, 433 Mbit/s link. Room for about 115 viewers at this quality.", a.text)
    }

    @Test fun `exactly one viewer of headroom uses the singular wording`() {
        // 8 × 0.3 = 2.4 ÷ 1.5 = 1 viewer
        val a = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(8, false, -60), enc())!!
        assertEquals(CameraShareSettingsModel.AdviceLevel.WARN, a.level)
        assertEquals("2.4 GHz Wi-Fi, 8 Mbit/s link. Good at 720p, 15 fps may stutter with more than 1 viewer. Basic is safer.", a.text)
    }

    @Test fun `a 2_4 GHz link that fits fewer than three viewers is amber and names the safer word`() {
        val best = enc(CameraShareSettings.Resolution.HIGH, CameraShareSettings.Tier.BEST, CameraShareSettings.FrameRate.FPS_30) // 8 Mbit/s
        val a = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(72, false, -63), best)!!
        assertEquals(CameraShareSettingsModel.AdviceLevel.WARN, a.level)
        // 72 × 0.3 = 21.6 ÷ 8 = 2 viewers
        assertEquals("2.4 GHz Wi-Fi, 72 Mbit/s link. Best at 1080p, 30 fps may stutter with more than 2 viewers. Good is safer.", a.text)
        // 20 × 0.3 = 6 ÷ 1.5 = 4 viewers: comfortable, so green.
        val good = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(20, false, -60), enc())!!
        assertEquals(CameraShareSettingsModel.AdviceLevel.GOOD, good.level)
        // 12 × 0.3 = 3.6 ÷ 1.5 = 2 viewers on Good: Basic is the safer word.
        val basicSafer = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(12, false, -60), enc())!!
        assertEquals("2.4 GHz Wi-Fi, 12 Mbit/s link. Good at 720p, 15 fps may stutter with more than 2 viewers. Basic is safer.", basicSafer.text)
        // 6 × 0.3 = 1.8 ÷ 0.8 = 2 viewers on Basic: nothing below Basic, so the resolution is the lever.
        val lowerRes = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(6, false, -60), enc(t = CameraShareSettings.Tier.BASIC))!!
        assertEquals("2.4 GHz Wi-Fi, 6 Mbit/s link. Basic at 720p, 15 fps may stutter with more than 2 viewers. Lower the resolution before adding viewers.", lowerRes.text)
    }

    @Test fun `a fair signal is mentioned on an amber line`() {
        val best = enc(CameraShareSettings.Resolution.HIGH, CameraShareSettings.Tier.BEST, CameraShareSettings.FrameRate.FPS_30)
        val a = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(72, false, -72), best)!!
        assertEquals("2.4 GHz Wi-Fi, 72 Mbit/s link. Best at 1080p, 30 fps may stutter with more than 2 viewers. Good is safer. Signal is fair (−72 dBm).", a.text)
    }

    @Test fun `nothing fitting is red with the smallest encoding as the way out`() {
        val best = enc(CameraShareSettings.Resolution.HIGH, CameraShareSettings.Tier.BEST, CameraShareSettings.FrameRate.FPS_30)
        val a = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(20, false, -60), best)!! // 6 ÷ 8 = 0
        assertEquals(CameraShareSettingsModel.AdviceLevel.BAD, a.level)
        assertEquals("2.4 GHz Wi-Fi, 20 Mbit/s link. Best at 1080p does not fit. Try Basic at 480p and 10 fps.", a.text)
    }

    @Test fun `a weak signal is red whatever the link speed says`() {
        val a = CameraShareSettingsModel.networkAdvice(CameraShareSettingsModel.WifiLink(433, true, -79), enc())!!
        assertEquals(CameraShareSettingsModel.AdviceLevel.BAD, a.level)
        assertEquals("Weak Wi-Fi signal (−79 dBm). Expect freezes at any quality until this device is closer to the router.", a.text)
    }

    @Test fun `the advice detail is the one-line honesty note`() {
        assertEquals("Checks this device's Wi-Fi only. A viewer on weak Wi-Fi still stutters.", CameraShareSettingsModel.ADVICE_DETAIL)
    }
}
