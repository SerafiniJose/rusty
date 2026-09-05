package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the camera summon: what a remote view/dismiss decides, with no Android and no
 * shell anywhere near it. Everything that actually moves a window ([ControlService]'s executor)
 * only ever runs the [SummonCmd]s this plan produces, so the whole "what does dismiss restore"
 * question is settled here.
 */
class CameraSummonTest {

    private val spotify = SummonCapture(panelId = "spotify", screensaverWasActive = false)

    /**
     * Summoned from a device with the screensaver up over Home Assistant. [SummonCapture.panelId]
     * is the panel to RESELECT — the feature beneath the saver, never "lockscreen" — and
     * [SummonCapture.screensaverWasActive] is the separate instruction to put the saver back on
     * top afterwards. Capturing "lockscreen" here would re-show the saver over the summoned
     * CAMERA, and dismissing it a moment later would drop the user on the camera grid.
     */
    private val saverOverHa = SummonCapture(panelId = "home_assistant", screensaverWasActive = true)

    @Test fun saverCapture_namesTheFeatureBeneathIt_andSurvivesRoundTrip() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", saverOverHa)

        val restore = plan.onDismiss().single() as SummonCmd.Restore
        assertEquals("home_assistant", restore.capture.panelId)
        assertTrue(restore.capture.screensaverWasActive)
    }

    @Test fun firstView_capturesAndShowsLive() {
        val plan = CameraSummonPlan()
        assertFalse(plan.active)

        val cmds = plan.onView("cam_1", spotify)

        assertEquals(listOf(SummonCmd.ShowLive("cam_1")), cmds)
        assertTrue(plan.active)
    }

    @Test fun secondView_whileActive_switchesOnly_noReCapture() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", spotify)

        // The "current" state handed in now is the SUMMONED one (camera on screen). Capturing it
        // would make dismiss restore the camera view — i.e. never restore at all.
        val cmds = plan.onView("cam_2", SummonCapture("camera", screensaverWasActive = false))

        assertEquals(listOf(SummonCmd.ShowLive("cam_2")), cmds)
        assertEquals(listOf(SummonCmd.Restore(spotify)), plan.onDismiss())
    }

    /**
     * `POST /api/camera/grid` is "show the camera grid", NOT "undo the summon": it abandons the
     * capture and produces a ShowGrid, never a Restore. Abandoning matters — the executor calls
     * CameraFragment.showGridNow(), whose showGrid() fires the external-exit hook back into
     * [onExternalExit]; a capture still held at that moment would turn a request for the grid into
     * a restore of the pre-summon panel, which is the exact opposite of what was asked.
     */
    @Test fun grid_abandonsTheCapture_andNeverRestores() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", spotify)

        assertEquals(listOf(SummonCmd.ShowGrid), plan.onGrid())

        assertFalse(plan.active)
        // The external exit that showGridNow() triggers must now find nothing to put back.
        assertEquals(emptyList<SummonCmd>(), plan.onExternalExit())
    }

    /** No summon in force (the Camera panel was selected directly): still a perfectly good request
     *  for the grid, and still not a restore of anything. */
    @Test fun grid_withNoSummon_stillShowsTheGrid() {
        val plan = CameraSummonPlan()

        assertEquals(listOf(SummonCmd.ShowGrid), plan.onGrid())
        assertFalse(plan.active)
    }

    @Test fun dismiss_restoresOriginalCaptureExactlyOnce() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", saverOverHa)

        assertEquals(listOf(SummonCmd.Restore(saverOverHa)), plan.onDismiss())
        assertFalse(plan.active)
        // Idempotent: a second dismiss (a double-tap on the page, or a dismiss racing the BACK
        // key that already exited) must not restore a second time.
        assertEquals(emptyList<SummonCmd>(), plan.onDismiss())
    }

    @Test fun dismiss_whileInactive_isEmpty() {
        val plan = CameraSummonPlan()
        assertEquals(emptyList<SummonCmd>(), plan.onDismiss())
        assertFalse(plan.active)
    }

    @Test fun externalExit_isDismiss() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", spotify)

        assertEquals(listOf(SummonCmd.Restore(spotify)), plan.onExternalExit())
        assertFalse(plan.active)
        assertEquals(emptyList<SummonCmd>(), plan.onExternalExit())
        // ...and a dismiss arriving after the BACK key already restored is a no-op too.
        assertEquals(emptyList<SummonCmd>(), plan.onDismiss())
    }

    @Test fun viewSwitchDismiss_restoresThePreFirstViewCapture() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", spotify)
        plan.onView("cam_2", SummonCapture("camera", screensaverWasActive = false))
        plan.onView("cam_3", SummonCapture("camera", screensaverWasActive = false))

        assertEquals(listOf(SummonCmd.Restore(spotify)), plan.onDismiss())
    }

    @Test fun aFreshSummonAfterDismiss_capturesAgain() {
        val plan = CameraSummonPlan()
        plan.onView("cam_1", spotify)
        plan.onDismiss()

        plan.onView("cam_9", saverOverHa)
        assertTrue(plan.active)
        assertEquals(listOf(SummonCmd.Restore(saverOverHa)), plan.onDismiss())
    }

    @Test fun backgroundCapture_isDistinctFromAnyPanel() {
        // Summoned from a backgrounded Rusty: there is no panel to go back to, and the restore
        // must be able to say so rather than pick some default panel.
        val plan = CameraSummonPlan()
        val background = SummonCapture(SummonCapture.BACKGROUND, screensaverWasActive = false)
        plan.onView("cam_1", background)
        assertEquals(listOf(SummonCmd.Restore(background)), plan.onDismiss())
        assertEquals(null, ControlPanelId.fromWire(SummonCapture.BACKGROUND))
    }
}
