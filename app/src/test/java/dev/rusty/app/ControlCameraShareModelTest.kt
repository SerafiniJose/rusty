package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ControlCameraShareModelTest {

    private fun facts(
        supported: Boolean = true,
        enabled: Boolean = true,
        state: CameraShareStatus.State = CameraShareStatus.State.Off,
        controlUrl: String = "http://192.168.7.251:8765",
        lens: CameraShareSettings.Lens = CameraShareSettings.Lens.FRONT,
        lenses: Int = 2,
        appForeground: Boolean = true,
    ) = ControlCameraShareModel.Facts(supported, enabled, state, controlUrl, lens, lenses, appForeground)

    // ---- snapshot -----------------------------------------------------------

    @Test fun unsupported_reportsTheHardwareAnswerAndNothingElse() {
        val s = ControlCameraShareModel.snapshot(facts(supported = false, enabled = true, lenses = 0))
        assertEquals(false, s.supported)
        assertEquals(ControlCameraShareStatus.OFF, s.status)
        assertNull(s.url)
    }

    @Test fun disabled_isOffWithNoUrl() {
        val s = ControlCameraShareModel.snapshot(facts(enabled = false, state = CameraShareStatus.State.Off))
        assertEquals(false, s.enabled)
        assertEquals(ControlCameraShareStatus.OFF, s.status)
        assertEquals("", s.detail)
        assertNull(s.url)
    }

    /** Switch on but the service not up yet: the page shows a pending line, not "off". */
    @Test fun enabledButNotYetRunning_isStarting() {
        val s = ControlCameraShareModel.snapshot(facts(enabled = true, state = CameraShareStatus.State.Off))
        assertEquals(ControlCameraShareStatus.STARTING, s.status)
        assertNull(s.url)
    }

    @Test fun ready_carriesTheStreamUrlBuiltFromTheControlHost() {
        val s = ControlCameraShareModel.snapshot(facts(state = CameraShareStatus.State.Ready))
        assertEquals(ControlCameraShareStatus.READY, s.status)
        assertEquals("rtsp://192.168.7.251:8554/live", s.url)
        assertEquals(0, s.viewers)
    }

    @Test fun streaming_reportsViewersAndTheHonestNumbers() {
        val s = ControlCameraShareModel.snapshot(
            facts(state = CameraShareStatus.State.Streaming(viewers = 2, width = 1280, height = 720, fps = 15, bps = 1_500_000))
        )
        assertEquals(ControlCameraShareStatus.STREAMING, s.status)
        assertEquals(2, s.viewers)
        assertEquals("1280×720 at 15 fps, 1.5 Mbit/s", s.detail)
        assertEquals("rtsp://192.168.7.251:8554/live", s.url)
    }

    @Test fun streamingBeforeTheFirstFrame_hasNoNumbersInTheDetail() {
        val s = ControlCameraShareModel.snapshot(facts(state = CameraShareStatus.State.Streaming(viewers = 1)))
        assertEquals("", s.detail)
    }

    @Test fun unavailable_carriesTheReason() {
        val s = ControlCameraShareModel.snapshot(facts(state = CameraShareStatus.State.Unavailable("camera permission needed")))
        assertEquals(ControlCameraShareStatus.UNAVAILABLE, s.status)
        assertEquals("camera permission needed", s.detail)
        assertNull(s.url)
    }

    /** The share is only reachable through the control server's host; no host, no URL. */
    @Test fun readyWithoutARoutableAddress_hasNoUrl() {
        val s = ControlCameraShareModel.snapshot(facts(state = CameraShareStatus.State.Ready, controlUrl = ""))
        assertEquals(ControlCameraShareStatus.READY, s.status)
        assertNull(s.url)
    }

    @Test fun lensAndWindowPassThrough() {
        val s = ControlCameraShareModel.snapshot(facts(lens = CameraShareSettings.Lens.BACK, lenses = 2, appForeground = false))
        assertEquals(CameraShareSettings.Lens.BACK, s.lens)
        assertEquals(2, s.lenses)
        assertEquals(false, s.appForeground)
    }

    // ---- decide ---------------------------------------------------------------

    private fun decide(
        command: ControlCameraShareCommand,
        supported: Boolean = true,
        permissionGranted: Boolean = true,
        appForeground: Boolean = true,
        canBringForward: Boolean = true,
    ) = ControlCameraShareModel.decide(command, supported, permissionGranted, appForeground, canBringForward)

    private val on = ControlCameraShareCommand(on = true, lens = null)
    private val off = ControlCameraShareCommand(on = false, lens = null)
    private val back = ControlCameraShareCommand(on = null, lens = CameraShareSettings.Lens.BACK)

    @Test fun unsupportedDevice_refusesEverything() {
        assertEquals(ControlCameraShareModel.Decision.Refuse(ControlCameraShareResult.Unsupported), decide(on, supported = false))
        assertEquals(ControlCameraShareModel.Decision.Refuse(ControlCameraShareResult.Unsupported), decide(back, supported = false))
    }

    @Test fun onWithRustyOnScreen_appliesWithoutBringingForward() {
        assertEquals(ControlCameraShareModel.Decision.Apply(on = true, lens = null, bringForward = false), decide(on))
    }

    @Test fun onWithRustyHidden_bringsItForwardFirst() {
        assertEquals(
            ControlCameraShareModel.Decision.Apply(on = true, lens = null, bringForward = true),
            decide(on, appForeground = false),
        )
    }

    @Test fun onWithRustyHiddenAndNoOverlayGrant_needsForeground() {
        assertEquals(
            ControlCameraShareModel.Decision.Refuse(ControlCameraShareResult.NeedsForeground),
            decide(on, appForeground = false, canBringForward = false),
        )
    }

    @Test fun onWithoutTheCameraGrant_needsPermission() {
        assertEquals(
            ControlCameraShareModel.Decision.Refuse(ControlCameraShareResult.PermissionNeeded),
            decide(on, permissionGranted = false),
        )
    }

    /** The permission answer comes before the window answer: bringing Rusty forward for a
     *  start that cannot happen would be a pointless disruption. */
    @Test fun permissionIsCheckedBeforeTheWindow() {
        assertEquals(
            ControlCameraShareModel.Decision.Refuse(ControlCameraShareResult.PermissionNeeded),
            decide(on, permissionGranted = false, appForeground = false, canBringForward = false),
        )
    }

    /** Off never needs the window or the grant: it only stops a service. */
    @Test fun off_appliesRegardlessOfWindowAndGrant() {
        assertEquals(
            ControlCameraShareModel.Decision.Apply(on = false, lens = null, bringForward = false),
            decide(off, permissionGranted = false, appForeground = false, canBringForward = false),
        )
    }

    /** A lens change is a preference write; the running service restarts the camera itself. */
    @Test fun lensAlone_appliesRegardlessOfWindowAndGrant() {
        assertEquals(
            ControlCameraShareModel.Decision.Apply(on = null, lens = CameraShareSettings.Lens.BACK, bringForward = false),
            decide(back, permissionGranted = false, appForeground = false, canBringForward = false),
        )
    }

    @Test fun onAndLensTogether_applyBoth() {
        assertEquals(
            ControlCameraShareModel.Decision.Apply(on = true, lens = CameraShareSettings.Lens.BACK, bringForward = true),
            decide(ControlCameraShareCommand(on = true, lens = CameraShareSettings.Lens.BACK), appForeground = false),
        )
    }
}
