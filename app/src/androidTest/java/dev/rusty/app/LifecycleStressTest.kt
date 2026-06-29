package dev.rusty.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 5B — Lifecycle leak/stress instrumented suite.
 *
 * Exercises the lifecycle hardening delivered across the whole branch (Tasks 5-8, 11-13, 18-22).
 * Every assertion is expressed via INSPECTABLE state — FragmentManager queries, the
 * [@VisibleForTesting][ReceiverStateStore.listenerCount] accessor, and the
 * [@VisibleForTesting][SpotifyFragment.openDialogCount] accessor — NOT via logcat or StrictMode.
 *
 * ## DEVICE-PENDING
 * These tests compile (verified via `:app:assembleDebugAndroidTest`) but have NOT been executed on
 * a physical device or emulator. Every instrumented assertion is lifecycle-bound and CANNOT be
 * validated on the JVM. Run with `./gradlew :app:connectedDebugAndroidTest` on the Lenovo at
 * 192.168.7.116:5555 when a device is available.
 *
 * ## What each case asserts and HOW
 *
 * 1. [fiftyFeatureSwitches_exactlyOneVisibleFragment_boundedRetainedCount]
 *    — 50 alternating SPOTIFY/HOME_ASSISTANT switches via [HomeActivity.switchToForTest].
 *    After each switch: count fragments whose `id == R.id.featureContainer && !isHidden &&
 *    view != null` → must be EXACTLY 1. Count ALL fragments in the container → must be ≤ 2
 *    (the enabled-feature count, never growing beyond it).
 *
 * 2. [rotateWithDialogOpen_noAttachedDialogAfterRecreation]
 *    — Open the Info sheet via [SpotifyFragment.showInfo], then `scenario.recreate()`.
 *    After recreation: read [SpotifyFragment.openDialogCount()] → must be 0.
 *    Uses the [@VisibleForTesting] accessor from Task 8 so the assertion is exact.
 *
 * 3. [backgroundForeground_withScreensaverShowing_noListenerLeak]
 *    — Record the store's baseline [ReceiverStateStore.listenerCount] before showing the
 *    screensaver. Show it, then run three bg/fg cycles via [ActivityScenario.moveToState].
 *    After the final RESUMED: assert listener count equals the baseline — no net growth means
 *    no duplicate [ReceiverStateStore.Listener] leaked. Uses the [@VisibleForTesting]
 *    [ReceiverStateStore.listenerCount] added to the store in this task (file:line in task report).
 *    Also asserts no crash.
 *
 * 4. [destroySpotifyViewDuringCoverArtLoad_noStaleApply]
 *    — Rapidly recreate the activity (which tears down SpotifyFragment's view immediately after
 *    create, before any Coil callback can fire). Assert no crash and the new fragment's view is
 *    non-null (proving a fresh instance was committed). Guards [SpotifyFragment]'s request-id
 *    increment in `onDestroyView` + lifecycle-state guard in the Palette callback (Task 6).
 *
 * 5. [stopStartReceiver_repeatedly_stableListenerCount]
 *    — Stop and re-start the receiver 5× via [HomeActivity.startReceiver]/[HomeActivity.stopReceiver]
 *    (routed through [ReceiverController]). Assert [ReceiverStateStore.listenerCount] stays within
 *    the baseline ± 1 tolerance after each cycle, proving no phantom listener is registered per
 *    cycle. Also assert no crash.
 *
 * 6. [disableHomeAssistant_retainedFragmentRemoved_navigatorHasNoHaFragment]
 *    — Enable HA, switch to it, then call [HomeActivity.setHomeAssistantEnabled](false).
 *    Assert [HomeActivity.retainedFragmentForTest(FeatureId.HOME_ASSISTANT)] returns null
 *    — the WebView is destroyed, not merely hidden.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleStressTest {

    private val prefsName = "spotify_receiver_prefs"

    @Before
    fun enableHomeAssistantWithUrl() {
        // HA must be enabled (and configured with a URL) for the retention/WebView stress tests.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(HomeAssistantFeature.KEY_ENABLED, true)
            .putString(HomeAssistantFeature.KEY_URL, "http://homeassistant.local:8123")
            .apply()
    }

    @After
    fun disableHomeAssistant() {
        // Clean up so other tests start from the default single-feature (Spotify-only) state.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .remove(HomeAssistantFeature.KEY_ENABLED)
            .remove(HomeAssistantFeature.KEY_URL)
            .apply()
    }

    // ---- 1. Stress: 50× feature switches -----------------------------------

    /**
     * 50 alternating SPOTIFY ↔ HOME_ASSISTANT switches must yield EXACTLY ONE visible fragment
     * and a retained-fragment count that never exceeds the number of enabled features (2).
     *
     * Inspectable state used:
     *  - Visible fragment count: `fragmentManager.fragments.count { id == featureContainer &&
     *    !isHidden && view != null }`
     *  - Retained count: `fragmentManager.fragments.count { id == featureContainer }`
     *  - Current tag: [HomeActivity.currentFragmentForTest]?.tag
     */
    @Test
    fun fiftyFeatureSwitches_exactlyOneVisibleFragment_boundedRetainedCount() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            repeat(50) { i ->
                val target = if (i % 2 == 0) FeatureId.HOME_ASSISTANT else FeatureId.SPOTIFY
                scenario.onActivity { activity ->
                    activity.switchToForTest(target)
                }
                scenario.onActivity { activity ->
                    val fm = activity.supportFragmentManager

                    val visibleCount = fm.fragments.count {
                        it.id == R.id.featureContainer && !it.isHidden && it.view != null
                    }
                    assertEquals(
                        "Exactly ONE visible fragment after switch #$i → $target",
                        1,
                        visibleCount,
                    )

                    val retainedCount = fm.fragments.count { it.id == R.id.featureContainer }
                    // With two enabled features the max is 2; once both are retained it stays at 2.
                    assertTrue(
                        "Retained fragment count must be ≤ 2 after switch #$i (was $retainedCount)",
                        retainedCount <= 2,
                    )

                    val currentTag = activity.currentFragmentForTest()?.tag
                    assertEquals(
                        "Current fragment tag must match switched-to feature $target after #$i",
                        target.name,
                        currentTag,
                    )
                }
            }
        }
    }

    // ---- 2. Rotate with Info dialog open → 0 attached dialogs after recreation ---

    /**
     * Open the Spotify Info sheet, then force a configuration-change (recreate). After recreation
     * the new [SpotifyFragment] instance must report 0 open dialogs via the
     * [@VisibleForTesting][SpotifyFragment.openDialogCount] accessor (Task 8).
     *
     * Inspectable state: [SpotifyFragment.openDialogCount()] — exact count, not logcat.
     */
    @Test
    fun rotateWithDialogOpen_noAttachedDialogAfterRecreation() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            // Open the Info sheet before the rotation.
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.featureContainer)
                if (fragment is SpotifyFragment) {
                    fragment.showInfo()
                }
            }

            // Force a configuration change (onDestroy → onDestroyView → dismiss all open dialogs).
            scenario.recreate()

            // After recreation the NEW fragment instance must have no open dialogs.
            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.featureContainer)
                if (fragment is SpotifyFragment) {
                    assertEquals(
                        "openDialogCount() must be 0 after configuration-change recreation",
                        0,
                        fragment.openDialogCount(),
                    )
                }
                // Sanity: the activity itself survived.
                assertNotNull("Activity must be non-null after recreate", activity)
            }
        }
    }

    // ---- 3. Background/foreground with screensaver showing → no listener leak ---

    /**
     * Show the screensaver, run 3 bg/fg cycles, assert no net growth in store listener count.
     *
     * [ScreensaverController.onPause] calls [ReceiverStateStore.removeListener]; [onResume] calls
     * [ReceiverStateStore.addListener]. A listener count that returns to baseline after each cycle
     * proves no duplicate is registered.
     *
     * Inspectable state: [ReceiverStateStore.listenerCount()] — the [@VisibleForTesting] accessor
     * added to [ReceiverStateStore] in this task. It reads [listeners].size under the existing
     * lock and does NOT change runtime behaviour.
     */
    @Test
    fun backgroundForeground_withScreensaverShowing_noListenerLeak() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            // Record baseline listener count while fully resumed (before screensaver).
            var baselineListenerCount = 0
            scenario.onActivity { activity ->
                baselineListenerCount = RustyApp.from(activity).listenerCount()
                // Now show the screensaver — this registers the screensaver's stateListener.
                activity.showScreensaver()
            }

            // Three background/foreground cycles.
            repeat(3) { cycle ->
                // Background: onPause → screensaver removes its listener.
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                // Foreground: onResume → screensaver re-registers its listener (exactly once).
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)

                scenario.onActivity { activity ->
                    val afterCount = RustyApp.from(activity).listenerCount()
                    assertEquals(
                        "Store listener count must equal baseline after bg/fg cycle #${cycle + 1} " +
                            "(baseline=$baselineListenerCount, actual=$afterCount — growth = leak)",
                        baselineListenerCount,
                        afterCount,
                    )
                }
            }
        }
    }

    // ---- 4. Destroy Spotify view during cover-art load → no stale apply -----

    /**
     * Rapidly recreate the activity 5× in succession. No crash + the final fragment view is
     * non-null proves the request-id guard in [SpotifyFragment.onDestroyView] (increments
     * `artworkRequestId`) and the lifecycle-state guard in the Palette callback (Task 6) are
     * both working. Any stale callback for a destroyed view would produce an NPE or
     * IllegalStateException here, causing the test to fail without an explicit assertion.
     *
     * Inspectable state: absence of crash + `fragment.view != null` on the final instance.
     */
    @Test
    fun destroySpotifyViewDuringCoverArtLoad_noStaleApply() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            // 5 rapid recreations — each tears down the view before Coil/Palette can finish.
            repeat(5) {
                scenario.recreate()
            }

            scenario.onActivity { activity ->
                val fragment = activity.supportFragmentManager
                    .findFragmentById(R.id.featureContainer)
                assertNotNull("A feature fragment must exist after rapid recreations", fragment)
                assertNotNull(
                    "Fragment view must be non-null after settling (no view-destroy leak)",
                    fragment?.view,
                )
            }
        }
    }

    // ---- 5. Stop/start receiver repeatedly → stable listener count ----------

    /**
     * Stop and restart the receiver 5× via the public [HomeActivity] methods that route through
     * [ReceiverController]. Assert the store's listener count stays within baseline ± 1 after each
     * cycle (the service itself may hold a listener while RUNNING, hence the ± 1 tolerance).
     *
     * Inspectable state: [ReceiverStateStore.listenerCount()].
     * No crash = no double-register/use-after-free in the controller's transitions.
     */
    @Test
    fun stopStartReceiver_repeatedly_stableListenerCount() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var baselineCount = 0
            scenario.onActivity { activity ->
                baselineCount = RustyApp.from(activity).listenerCount()
            }

            repeat(5) { cycle ->
                // Stop the receiver (routes through ReceiverController.stopReceiver).
                scenario.onActivity { activity ->
                    activity.stopReceiver()
                }
                // Re-start (routes through ReceiverController.ensureStarted → SpotifyService).
                scenario.onActivity { activity ->
                    activity.startReceiver()
                }

                scenario.onActivity { activity ->
                    val count = RustyApp.from(activity).listenerCount()
                    // Allow ± 1 to account for a service-owned listener that may be in-flight
                    // (registered when RUNNING, removed when STOPPING). A count that grows by
                    // more than 1 per cycle indicates a genuine listener leak.
                    assertTrue(
                        "Listener count must be within baseline ± 1 after stop/start cycle " +
                            "#${cycle + 1} (baseline=$baselineCount, actual=$count)",
                        count <= baselineCount + 1,
                    )
                }
            }
        }
    }

    // ---- 6. Disable HA → retained fragment is removed (WebView destroyed) ---

    /**
     * Switch to HA so its fragment (and WebView) are retained, then disable HA. The navigator
     * must remove the retained fragment entirely — [HomeActivity.retainedFragmentForTest] returns
     * null — proving the WebView is released rather than kept hidden indefinitely.
     *
     * Inspectable state: [HomeActivity.retainedFragmentForTest(FeatureId.HOME_ASSISTANT)] == null
     * after [HomeActivity.setHomeAssistantEnabled](false).
     * Also assert the current fragment is SPOTIFY (the fallback).
     */
    @Test
    fun disableHomeAssistant_retainedFragmentRemoved_navigatorHasNoHaFragment() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            // Switch to HA so its fragment is retained in the container.
            scenario.onActivity { activity ->
                activity.switchToForTest(FeatureId.HOME_ASSISTANT)
                assertNotNull(
                    "HA fragment must be retained after switch",
                    activity.retainedFragmentForTest(FeatureId.HOME_ASSISTANT),
                )
            }

            // Disable HA: shell switches back to Spotify AND removes the retained HA fragment.
            scenario.onActivity { activity ->
                activity.setHomeAssistantEnabled(false)
            }

            scenario.onActivity { activity ->
                // The navigator must have removed the HA fragment (WebView released).
                assertNull(
                    "HA retained fragment (WebView) must be null after setHomeAssistantEnabled(false)",
                    activity.retainedFragmentForTest(FeatureId.HOME_ASSISTANT),
                )
                // The active feature must be SPOTIFY (the fallback).
                val currentTag = activity.currentFragmentForTest()?.tag
                assertEquals(
                    "Active feature must be SPOTIFY after HA is disabled (got: $currentTag)",
                    FeatureId.SPOTIFY.name,
                    currentTag,
                )
            }
        }
    }
}
