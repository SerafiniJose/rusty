package dev.rusty.app

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ScreenControlModelTest {

    @Before fun reset() {
        ScreenControlModel.resetForTest()
    }

    @Test fun defaultsAreOnAndFullBrightness() {
        val d = ScreenControlModel.desired()
        assertTrue(d.on)
        assertEquals(100, d.brightness)
    }

    @Test fun offThenOnWithNullRestoresPriorBrightness() {
        ScreenControlModel.set(true, 42)
        ScreenControlModel.set(false, null)
        val back = ScreenControlModel.set(true, null)
        assertTrue(back.on)
        assertEquals(42, back.brightness)
    }

    @Test fun aBrightnessCommandedWhileOffIsWhatTheNextWakeRestores() {
        // Reachable from the shipped control page: the user turns the screen off, then moves the
        // brightness slider (the page sends {on:false, brightness:N}, which the router accepts —
        // 1..100 is valid regardless of `on`). The 200 response reports the new brightness, so
        // silently reverting to the pre-off value on the next wake would make the API a liar.
        // Per the design's "the last non-zero ON brightness is remembered and restored on wake":
        // the brightness the user last COMMANDED is their on-brightness.
        ScreenControlModel.set(true, 30)
        val whileOff = ScreenControlModel.set(false, 80)
        assertEquals(80, whileOff.brightness)
        val back = ScreenControlModel.set(true, null)
        assertTrue(back.on)
        assertEquals(80, back.brightness)
    }

    @Test fun turningOffWithoutABrightnessStillRestoresThePriorOnBrightness() {
        // The other half of the rule above: an `off` that carries NO brightness must not disturb
        // what the next wake restores.
        ScreenControlModel.set(true, 30)
        ScreenControlModel.set(false, null)
        ScreenControlModel.set(false, null)
        assertEquals(30, ScreenControlModel.set(true, null).brightness)
    }

    @Test fun offThenOnWithNullRestoresDefaultBrightnessWhenNeverSetOn() {
        // Never explicitly set an "on brightness" before turning off; default on-brightness (100)
        // is what gets restored.
        ScreenControlModel.set(false, null)
        val back = ScreenControlModel.set(true, null)
        assertTrue(back.on)
        assertEquals(100, back.brightness)
    }

    @Test fun brightnessClampsBelowRangeToOne() {
        val d = ScreenControlModel.set(true, 0)
        assertEquals(1, d.brightness)
    }

    @Test fun brightnessClampsAboveRangeToHundred() {
        val d = ScreenControlModel.set(true, 101)
        assertEquals(100, d.brightness)
    }

    @Test fun brightnessClampsFarBelowRangeToOne() {
        val d = ScreenControlModel.set(true, -50)
        assertEquals(1, d.brightness)
    }

    @Test fun brightnessClampsFarAboveRangeToHundred() {
        val d = ScreenControlModel.set(true, 500)
        assertEquals(100, d.brightness)
    }

    @Test fun nullBrightnessWhileOnKeepsCurrentBrightness() {
        ScreenControlModel.set(true, 55)
        val d = ScreenControlModel.set(true, null)
        assertEquals(55, d.brightness)
    }

    @Test fun settingOffDoesNotChangeReportedBrightnessField() {
        ScreenControlModel.set(true, 30)
        val off = ScreenControlModel.set(false, null)
        assertFalse(off.on)
        assertEquals(30, off.brightness)
    }

    @Test fun setReturnsResultingStateNotEchoOfRequest() {
        // Passing an out-of-range brightness must come back clamped, not echoed.
        val d = ScreenControlModel.set(true, 999)
        assertNotEquals(999, d.brightness)
        assertEquals(100, d.brightness)
    }

    @Test fun attachRendererFiresImmediatelyWithCurrentState() {
        val seen = mutableListOf<ScreenDesired>()
        ScreenControlModel.set(true, 77)
        ScreenControlModel.attachRenderer { seen.add(it) }
        assertEquals(1, seen.size)
        assertEquals(77, seen[0].brightness)
        ScreenControlModel.detachRenderer { }
    }

    @Test fun attachedRendererFiresOnEverySet() {
        val seen = mutableListOf<ScreenDesired>()
        val renderer: (ScreenDesired) -> Unit = { seen.add(it) }
        ScreenControlModel.attachRenderer(renderer)
        seen.clear() // drop the immediate initial call
        ScreenControlModel.set(true, 60)
        ScreenControlModel.set(false, null)
        assertEquals(2, seen.size)
        assertEquals(60, seen[0].brightness)
        assertFalse(seen[1].on)
        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun detachedRendererStopsReceivingUpdates() {
        val seen = mutableListOf<ScreenDesired>()
        val renderer: (ScreenDesired) -> Unit = { seen.add(it) }
        ScreenControlModel.attachRenderer(renderer)
        ScreenControlModel.detachRenderer(renderer)
        seen.clear()
        ScreenControlModel.set(true, 33)
        assertTrue(seen.isEmpty())
    }

    @Test fun multipleRenderersAllReceiveUpdates() {
        val a = mutableListOf<ScreenDesired>()
        val b = mutableListOf<ScreenDesired>()
        val ra: (ScreenDesired) -> Unit = { a.add(it) }
        val rb: (ScreenDesired) -> Unit = { b.add(it) }
        ScreenControlModel.attachRenderer(ra)
        ScreenControlModel.attachRenderer(rb)
        a.clear(); b.clear()
        ScreenControlModel.set(true, 20)
        assertEquals(1, a.size)
        assertEquals(1, b.size)
        ScreenControlModel.detachRenderer(ra)
        ScreenControlModel.detachRenderer(rb)
    }

    @Test fun rendererAttachedReflectsAttachDetach() {
        assertFalse(ScreenControlModel.rendererAttached())
        val renderer: (ScreenDesired) -> Unit = { }
        ScreenControlModel.attachRenderer(renderer)
        assertTrue(ScreenControlModel.rendererAttached())
        ScreenControlModel.detachRenderer(renderer)
        assertFalse(ScreenControlModel.rendererAttached())
    }

    // ---- attached vs. available: the backgrounded fake-off dead end ------------------------
    //
    // HomeActivity attaches at onCreate/onDestroy (so an `off` written while the user is in
    // another app still survives and re-applies) but reports visibility from onStart/onStop.
    // These pin that the two facts are genuinely separate, because conflating them is what made
    // the API report `available: true` for a panel that had really gone to sleep behind the
    // overlay and could no longer be relit remotely.

    @Test fun availableRequiresBothAnAttachedRendererAndAVisibleWindow() {
        val renderer: (ScreenDesired) -> Unit = { }
        assertFalse(ScreenControlModel.screenControlAvailable())

        ScreenControlModel.attachRenderer(renderer)
        assertTrue(ScreenControlModel.rendererAttached())
        // Attached but never started: nothing is holding the panel awake yet.
        assertFalse(ScreenControlModel.screenControlAvailable())

        ScreenControlModel.setRendererVisible(true)
        assertTrue(ScreenControlModel.screenControlAvailable())

        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun leavingTheAppKeepsTheRendererButDropsAvailability() {
        val renderer: (ScreenDesired) -> Unit = { }
        ScreenControlModel.attachRenderer(renderer)
        ScreenControlModel.setRendererVisible(true)

        // onStop: the user pressed HOME. The desired state is still owned and will still be
        // applied — but a command cannot take effect until the window is visible again.
        ScreenControlModel.setRendererVisible(false)
        assertTrue("the renderer must stay attached", ScreenControlModel.rendererAttached())
        assertFalse("but the API must not claim the screen is controllable", ScreenControlModel.screenControlAvailable())

        ScreenControlModel.setRendererVisible(true)
        assertTrue(ScreenControlModel.screenControlAvailable())
        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun aDestroyedRendererIsUnavailableEvenIfVisibilityWasNeverCleared() {
        // Defensive: Android always calls onStop before onDestroy, but a stale `visible = true`
        // must not be able to outlive the renderer it described.
        val renderer: (ScreenDesired) -> Unit = { }
        ScreenControlModel.attachRenderer(renderer)
        ScreenControlModel.setRendererVisible(true)
        ScreenControlModel.detachRenderer(renderer)
        assertFalse(ScreenControlModel.screenControlAvailable())
    }

    @Test fun commandsWrittenWhileUnavailableAreStillStoredAndApplied() {
        // The other half of the deliberate onCreate/onDestroy attachment: unavailable does NOT
        // mean "rejected". The state is recorded and delivered to the renderer, exactly as the
        // control page's "commands are saved and will apply as soon as the app is active" notice
        // promises.
        val seen = mutableListOf<ScreenDesired>()
        val renderer: (ScreenDesired) -> Unit = { seen.add(it) }
        ScreenControlModel.attachRenderer(renderer)
        ScreenControlModel.setRendererVisible(false)

        val result = ScreenControlModel.set(on = false, brightness = 40)
        assertFalse(ScreenControlModel.screenControlAvailable())
        assertFalse(result.on)
        assertEquals(40, result.brightness)
        assertEquals(ScreenDesired(on = false, brightness = 40), seen.last())
        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun rendererAttachedTrueWithAtLeastOneOfMultiple() {
        val r1: (ScreenDesired) -> Unit = { }
        val r2: (ScreenDesired) -> Unit = { }
        ScreenControlModel.attachRenderer(r1)
        ScreenControlModel.attachRenderer(r2)
        ScreenControlModel.detachRenderer(r1)
        assertTrue(ScreenControlModel.rendererAttached())
        ScreenControlModel.detachRenderer(r2)
        assertFalse(ScreenControlModel.rendererAttached())
    }

    @Test fun resetForTestRestoresDefaultsAndClearsRenderers() {
        val seen = mutableListOf<ScreenDesired>()
        val renderer: (ScreenDesired) -> Unit = { seen.add(it) }
        ScreenControlModel.set(false, 5)
        ScreenControlModel.attachRenderer(renderer)

        ScreenControlModel.resetForTest()

        assertFalse(ScreenControlModel.rendererAttached())
        val d = ScreenControlModel.desired()
        assertTrue(d.on)
        assertEquals(100, d.brightness)
        // The renderer that was attached before reset must not fire on subsequent sets.
        seen.clear()
        ScreenControlModel.set(true, 10)
        assertTrue(seen.isEmpty())
    }

    @Test fun attachRendererCallbackNotInvokedWhileHoldingLock() {
        // If invoke() happened while the lock is held, a renderer that calls back into the model
        // (e.g. desired()) from a different thread would deadlock. A renderer calling back into
        // the model from within its own callback, on the SAME thread, must not hang either way
        // (synchronized is reentrant per-thread) -- but we specifically assert the cross-thread
        // case here, which only succeeds if invocation happens outside the lock. This probes the
        // attachRenderer() invocation site specifically (the immediate delivery on attach).
        val renderer: (ScreenDesired) -> Unit = {
            val t = Thread { ScreenControlModel.desired() }
            t.start()
            t.join(2000)
            assertFalse("callback appears to run while holding the lock (cross-thread desired() blocked)", t.isAlive)
        }
        ScreenControlModel.attachRenderer(renderer)
        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun setCallbackNotInvokedWhileHoldingLock() {
        // Same probe as attachRendererCallbackNotInvokedWhileHoldingLock, but targeting set()'s
        // invocation site specifically: attach() delivers once immediately (consumed, unprobed
        // below), then a subsequent set() triggers the probed delivery. A regression that moved
        // the delivery loop for a `set()`-triggered notification back inside the lock would hang
        // the cross-thread desired() call and fail this test, even though the attach-only probe
        // above still passes -- set() is the production-hot path (every mutation goes through it),
        // so it needs its own dedicated coverage rather than relying on the attach probe alone.
        var probed = false
        val renderer: (ScreenDesired) -> Unit = {
            if (probed) {
                val t = Thread { ScreenControlModel.desired() }
                t.start()
                t.join(2000)
                assertFalse(
                    "callback invoked via set() appears to run while holding the lock (cross-thread desired() blocked)",
                    t.isAlive
                )
            }
            probed = true // first call is attachRenderer's own immediate delivery; skip it
        }
        ScreenControlModel.attachRenderer(renderer) // call #1: immediate delivery, not probed
        ScreenControlModel.set(true, 50)            // call #2: set()-triggered delivery, probed
        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun concurrentSetsNeverDeliverAStaleValueAfterTheFinalOne() {
        // Reproduces the exact hazard from review: thread A commits state1 and is about to
        // deliver it to renderers, but before it can, thread B commits the genuinely newer
        // state2 and delivers it to completion; a naive "compute+release+invoke" implementation
        // then lets thread A resume and deliver the now-stale state1 LAST, leaving the renderer's
        // last-applied value behind what desired() reports.
        //
        // The interleaving is driven deterministically from inside the renderer callback (not a
        // sleep-based race): on the FIRST delivery (state1, brightness 55), the renderer itself
        // spawns thread B, which calls set(brightness 77), and BLOCKS (join) until B's entire
        // set() call -- including, in a buggy same-thread-delivers implementation, B's own nested
        // delivery -- has completed, before recording its own delivery. This forces exactly the
        // "B's delivery, if any, happens-before A's own delivery is recorded" ordering needed to
        // expose the bug:
        //  - BUGGY (pre-fix): B's set() delivers state2 synchronously, nested, on B's own thread,
        //    completing (and being recorded) BEFORE A's callback frame resumes and records
        //    state1 -- final recorded order is [77, 55], i.e. stale-after-final.
        //  - FIXED: B's set() call, made while A is mid-delivery, only enqueues (another delivery
        //    is already in flight) and returns immediately without delivering anything itself;
        //    state2's delivery instead happens back on A's own thread, in the SAME drain loop,
        //    strictly after state1 -- final recorded order is [55, 77].
        val order = mutableListOf<Int>()
        var triggered = false
        val renderer: (ScreenDesired) -> Unit = { state ->
            if (state.brightness == 55 && !triggered) {
                triggered = true
                val t = Thread { ScreenControlModel.set(true, 77) }
                t.start()
                t.join(2000)
            }
            order.add(state.brightness)
        }
        ScreenControlModel.attachRenderer(renderer)
        order.clear()
        triggered = false

        ScreenControlModel.set(true, 55)

        assertEquals(listOf(55, 77), order)
        assertEquals(ScreenControlModel.desired().brightness, order.last())
        ScreenControlModel.detachRenderer(renderer)
    }

    @Test fun throwingRendererDoesNotWedgeDrainingOrStarveOtherRenderers() {
        // Reproduces the review's concrete failure: without per-renderer isolation, an exception
        // escaping mid-drain skips the `draining = false` reset entirely (it only runs on the
        // next loop iteration's empty-queue check), wedging `draining` stuck `true` forever --
        // every later set()/attachRenderer would then silently enqueue with nothing left to ever
        // drain it, and NO renderer (not just the throwing one) would be notified again.
        val healthySeen = mutableListOf<ScreenDesired>()
        val throwing: (ScreenDesired) -> Unit = { throw IllegalStateException("boom") }
        val healthy: (ScreenDesired) -> Unit = { healthySeen.add(it) }

        // Attaching the throwing renderer must not itself crash the caller.
        ScreenControlModel.attachRenderer(throwing)
        ScreenControlModel.attachRenderer(healthy)
        healthySeen.clear()

        // set() must still deliver to the healthy renderer even though the throwing one blows up
        // on every single delivery it receives.
        ScreenControlModel.set(true, 66)
        assertEquals(1, healthySeen.size)
        assertEquals(66, healthySeen[0].brightness)
        assertEquals(0, ScreenControlModel.pendingCount()) // nothing left stuck in the queue

        // A SECOND set() must still deliver -- proves `draining` was not left wedged `true` by
        // the first delivery's exception (a wedged flag would make this call silently enqueue
        // forever with nobody left to drain it, and healthySeen would never grow again).
        ScreenControlModel.set(false, null)
        assertEquals(2, healthySeen.size)
        assertFalse(healthySeen[1].on)
        assertEquals(0, ScreenControlModel.pendingCount())

        ScreenControlModel.detachRenderer(throwing)
        ScreenControlModel.detachRenderer(healthy)
    }

    // ---- ScreenRenderPlan: the pure half of the Activity renderer ------------
    //
    // `brightnessCommanded = true` in most cases below: these fix the mapping of a brightness the
    // user actually asked for. The never-commanded case has its own section further down.

    @Test fun planForOffRaisesTheOverlayAndDimsTheWindowWithoutTouchingSystemBrightness() {
        val plan = ScreenRenderPlan.of(
            ScreenDesired(on = false, brightness = 80), canWriteSystem = true, brightnessCommanded = true,
        )
        assertTrue(plan.overlayVisible)
        assertEquals(ScreenRenderPlan.OFF_WINDOW_BRIGHTNESS, plan.windowBrightness, 0f)
        // Even holding WRITE_SETTINGS, going dark writes nothing device-wide: the black overlay is
        // what hides the panel, so the device keeps its own brightness (and adaptive mode) while
        // off. What the WAKE writes is a separate decision — see the on-leg tests below.
        assertNull(plan.systemLevel)
    }

    @Test fun planForOnWithoutWritePermissionUsesWindowBrightnessOnly() {
        val plan = ScreenRenderPlan.of(
            ScreenDesired(on = true, brightness = 50), canWriteSystem = false, brightnessCommanded = true,
        )
        assertFalse(plan.overlayVisible)
        assertEquals(0.5f, plan.windowBrightness, 0.0001f)
        assertNull(plan.systemLevel)
    }

    @Test fun planForOnWithWritePermissionHandsTheWindowBackToTheSystem() {
        val plan = ScreenRenderPlan.of(
            ScreenDesired(on = true, brightness = 50), canWriteSystem = true, brightnessCommanded = true,
        )
        assertFalse(plan.overlayVisible)
        // A window override would keep winning over the system value we are about to write.
        assertEquals(ScreenRenderPlan.BRIGHTNESS_OVERRIDE_NONE, plan.windowBrightness, 0f)
        assertEquals(127, plan.systemLevel) // 50 * 255 / 100, integer division
    }

    @Test fun systemLevelAtOnePercentIsTheDimmestVisibleStepNotZero() {
        // 1 * 255 / 100 == 2. A 0 here would black a panel the API is reporting as ON.
        assertEquals(2, ScreenRenderPlan.of(ScreenDesired(true, 1), true, true).systemLevel)
    }

    @Test fun systemLevelAtHundredPercentIsFullScale() {
        assertEquals(255, ScreenRenderPlan.of(ScreenDesired(true, 100), true, true).systemLevel)
    }

    @Test fun systemLevelNeverLeavesTheOneToTwoFiftyFiveRange() {
        // Defence in depth: the model already clamps to 1..100, but a level of 0 (or >255) would be
        // a screen the user cannot get back from a device without WRITE_SETTINGS revoked.
        for (percent in 1..100) {
            val level = ScreenRenderPlan.of(ScreenDesired(true, percent), true, true).systemLevel
            assertNotNull(level)
            assertTrue(level!! in 1..255)
        }
    }

    @Test fun windowBrightnessForOnIsTheDesiredFraction() {
        assertEquals(0.01f, ScreenRenderPlan.of(ScreenDesired(true, 1), false, true).windowBrightness, 0.0001f)
        assertEquals(1.0f, ScreenRenderPlan.of(ScreenDesired(true, 100), false, true).windowBrightness, 0.0001f)
    }

    // ---- A default brightness is not a command ------------------------------
    //
    // The renderer re-applies the desired state on every Activity create (attachRenderer replays
    // it). Before anyone has commanded a brightness the model reports the DEFAULT 100, which is
    // indistinguishable from a real "100 %" — so acting on it would slam a WRITE_SETTINGS-granted
    // wall tablet to full brightness, and out of adaptive brightness, just for opening the app.

    @Test fun planForNeverCommandedBrightnessWritesNothingEvenWithPermission() {
        val plan = ScreenRenderPlan.of(
            ScreenDesired(on = true, brightness = 100), canWriteSystem = true, brightnessCommanded = false,
        )
        assertFalse(plan.overlayVisible)
        assertNull(plan.systemLevel)
        // And no window override either: hands off means the panel goes back to doing its own thing.
        assertEquals(ScreenRenderPlan.BRIGHTNESS_OVERRIDE_NONE, plan.windowBrightness, 0f)
    }

    @Test fun planForNeverCommandedBrightnessTakesNoWindowOverrideWithoutPermission() {
        val plan = ScreenRenderPlan.of(
            ScreenDesired(on = true, brightness = 100), canWriteSystem = false, brightnessCommanded = false,
        )
        assertNull(plan.systemLevel)
        assertEquals(ScreenRenderPlan.BRIGHTNESS_OVERRIDE_NONE, plan.windowBrightness, 0f)
    }

    @Test fun offStillBlacksTheScreenWhenNoBrightnessWasEverCommanded() {
        // Fake-off must work on a device nobody has ever set a brightness on.
        val plan = ScreenRenderPlan.of(
            ScreenDesired(on = false, brightness = 100), canWriteSystem = true, brightnessCommanded = false,
        )
        assertTrue(plan.overlayVisible)
        assertEquals(ScreenRenderPlan.OFF_WINDOW_BRIGHTNESS, plan.windowBrightness, 0f)
        assertNull(plan.systemLevel)
    }

    @Test fun brightnessIsNotCommandedUntilOneIsActuallySet() {
        assertFalse(ScreenControlModel.brightnessEverCommanded())
        ScreenControlModel.set(on = false, brightness = null)          // an on/off command alone…
        ScreenControlModel.set(on = true, brightness = null)           // …never counts as one
        assertFalse(ScreenControlModel.brightnessEverCommanded())
        ScreenControlModel.set(on = true, brightness = 40)
        assertTrue(ScreenControlModel.brightnessEverCommanded())
    }

    @Test fun aCommandedBrightnessStaysCommandedAcrossAnOffOnRoundTrip() {
        // Restoring lastOnBrightness is still the user's own choice, so the system write is legitimate.
        ScreenControlModel.set(on = true, brightness = 40)
        ScreenControlModel.set(on = false, brightness = null)
        val back = ScreenControlModel.set(on = true, brightness = null)
        assertEquals(40, back.brightness)
        assertTrue(ScreenControlModel.brightnessEverCommanded())
    }

    // ---- Reporting a system write that did not land -------------------------

    @Test fun systemBrightnessIsUsableWhilePermittedAndNothingHasFailed() {
        assertTrue(ScreenControlModel.systemBrightnessUsable(canWriteSystem = true))
        assertFalse(ScreenControlModel.systemBrightnessUsable(canWriteSystem = false))
    }

    @Test fun aRefusedSystemWriteMakesTheSnapshotReportWindowMode() {
        // canWrite() said yes, putInt refused (OEM/policy): the renderer degraded to window-local
        // brightness, so the API must stop claiming the DEVICE brightness moved.
        ScreenControlModel.noteSystemBrightnessWrite(succeeded = false)
        assertFalse(ScreenControlModel.systemBrightnessUsable(canWriteSystem = true))
    }

    @Test fun aLaterSuccessfulWriteClearsTheDegradedReport() {
        // The flag is a report, never an input to the plan — so the next command still tries the
        // system write, and a success must put the reported mode back.
        ScreenControlModel.noteSystemBrightnessWrite(succeeded = false)
        ScreenControlModel.noteSystemBrightnessWrite(succeeded = true)
        assertTrue(ScreenControlModel.systemBrightnessUsable(canWriteSystem = true))
    }

    @Test fun aDegradedWriteNeverReportsSystemModeWithoutThePermission() {
        ScreenControlModel.noteSystemBrightnessWrite(succeeded = true)
        assertFalse(ScreenControlModel.systemBrightnessUsable(canWriteSystem = false))
    }
}
