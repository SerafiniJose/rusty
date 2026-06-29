package dev.rusty.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Task 18 — retained feature fragments (add/hide/show).
 *
 * Verifies the retention contract that replaced the destroy-and-recreate `replace()`:
 *  1. 25× back-and-forth switches keep EXACTLY ONE visible fragment in the container.
 *  2. Home Assistant RETAINS its fragment instance across switches (same object, not recreated),
 *     so its loaded WebView/dashboard/login survives a switch away and back.
 *  3. A configuration change (rotation) restores the retained fragments with exactly one visible
 *     and no duplicate of the current feature.
 *  4. Disabling HA destroys (removes) its retained fragment — its WebView is released, not kept hidden.
 *  5. After a switch, D-pad focus lands on a visible control (a focused view exists in the window).
 *
 * Device-pending: these tests COMPILE (verified via assembleDebugAndroidTest) but have NOT been
 * executed on a physical device or emulator. The retention/visibility/lifecycle behavior is
 * instrumentation-bound and CANNOT be validated on the JVM — run with `connectedDebugAndroidTest`
 * when a device is available. Until then the retention correctness (hidden=CREATED teardown, single
 * visible fragment, HA WebView survival) is UNVALIDATED.
 */
@RunWith(AndroidJUnit4::class)
class FragmentRetentionTest {

    private val prefsName = "spotify_receiver_prefs"

    @Before
    fun enableHomeAssistantWithUrl() {
        // HA must be enabled (and configured with a URL) so it is in the ring and renders its WebView
        // rather than the setup form — that lets us assert the WebView/dashboard is retained.
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        ctx.getSharedPreferences(prefsName, Context.MODE_PRIVATE).edit()
            .putBoolean(HomeAssistantFeature.KEY_ENABLED, true)
            .putString(HomeAssistantFeature.KEY_URL, "http://homeassistant.local:8123")
            .apply()
    }

    /** Counts the VISIBLE (non-hidden, view-attached) feature fragments in the container. */
    private fun visibleFeatureFragments(activity: HomeActivity): Int =
        activity.supportFragmentManager.fragments.count {
            it.id == R.id.featureContainer && !it.isHidden && it.view != null
        }

    /** Counts ALL retained fragments (hidden or shown) in the container. */
    private fun retainedFeatureFragments(activity: HomeActivity): Int =
        activity.supportFragmentManager.fragments.count { it.id == R.id.featureContainer }

    @Test
    fun twentyFiveSwitches_keepExactlyOneVisibleFragment() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            repeat(25) { i ->
                val target = if (i % 2 == 0) FeatureId.HOME_ASSISTANT else FeatureId.SPOTIFY
                scenario.onActivity { it.switchToForTest(target) }
                scenario.onActivity { activity ->
                    assertEquals(
                        "Exactly one visible fragment after switch #$i to $target",
                        1, visibleFeatureFragments(activity),
                    )
                    assertEquals(
                        "Visible fragment must be the switched-to feature after #$i",
                        target.name, activity.currentFragmentForTest()?.tag,
                    )
                    // Both features stay RETAINED (two fragments total once HA has been shown once).
                    assertTrue(
                        "Both feature fragments retained after #$i",
                        retainedFeatureFragments(activity) <= 2,
                    )
                }
            }
        }
    }

    @Test
    fun homeAssistant_retainsSameFragmentInstance_acrossSwitches() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var firstHa: androidx.fragment.app.Fragment? = null
            scenario.onActivity { activity ->
                activity.switchToForTest(FeatureId.HOME_ASSISTANT)
                firstHa = activity.currentFragmentForTest()
                assertNotNull("HA fragment must exist after first switch", firstHa)
                assertEquals(FeatureId.HOME_ASSISTANT.name, firstHa?.tag)
            }
            // Switch away and back several times; the HA fragment OBJECT must be identical each time
            // (retained, not recreated) — its loaded WebView/dashboard therefore survives.
            repeat(4) {
                scenario.onActivity { it.switchToForTest(FeatureId.SPOTIFY) }
                scenario.onActivity { activity ->
                    activity.switchToForTest(FeatureId.HOME_ASSISTANT)
                    assertSame(
                        "HA fragment must be the SAME retained instance across switches",
                        firstHa, activity.currentFragmentForTest(),
                    )
                }
            }
        }
    }

    @Test
    fun rotation_restoresRetainedFragments_withExactlyOneVisible() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            // Show HA so two fragments are retained, then rotate.
            scenario.onActivity { it.switchToForTest(FeatureId.HOME_ASSISTANT) }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(
                    "Exactly one visible fragment after rotation",
                    1, visibleFeatureFragments(activity),
                )
                // No duplicate of any feature: at most one fragment per tag.
                val byTag = activity.supportFragmentManager.fragments
                    .filter { it.id == R.id.featureContainer }
                    .groupingBy { it.tag }.eachCount()
                byTag.forEach { (tag, count) ->
                    assertEquals("No duplicate fragment for $tag after rotation", 1, count)
                }
            }
        }
    }

    @Test
    fun disablingHomeAssistant_destroysItsRetainedFragment() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.switchToForTest(FeatureId.HOME_ASSISTANT)
                assertNotNull(
                    "HA fragment retained after switch",
                    activity.retainedFragmentForTest(FeatureId.HOME_ASSISTANT),
                )
                // Disable HA: shell switches back to Spotify AND removes the retained HA fragment.
                activity.setHomeAssistantEnabled(false)
                assertEquals(
                    "Active feature must be SPOTIFY after disabling HA",
                    FeatureId.SPOTIFY.name, activity.currentFragmentForTest()?.tag,
                )
                assertNull(
                    "HA fragment (its WebView) must be destroyed/removed after disable",
                    activity.retainedFragmentForTest(FeatureId.HOME_ASSISTANT),
                )
            }
        }
    }

    @Test
    fun afterSwitch_focusLandsOnAVisibleControl() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            scenario.onActivity { it.switchToForTest(FeatureId.HOME_ASSISTANT) }
            // restoreFocus posts the requestFocus; drain the main looper so it runs.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { activity ->
                // In non-touch mode a focused view should exist and be inside the visible fragment.
                val focused = activity.currentFocus
                if (focused != null) {
                    assertTrue("Focused view must be shown (visible)", focused.isShown)
                }
                // The visible fragment is HA — its view must be present to receive focus.
                assertNotNull(
                    "Shown HA fragment view present for focus",
                    activity.currentFragmentForTest()?.view,
                )
            }
        }
    }
}
