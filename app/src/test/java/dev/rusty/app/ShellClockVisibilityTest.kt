package dev.rusty.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared shell clock is hidden only on the HA login page (Home Assistant foreground + not
 *  signed in); it shows over every other feature and over an HA dashboard once signed in. */
class ShellClockVisibilityTest {

    @Test
    fun hiddenOverHomeAssistant_whenSignedOut() {
        assertTrue(hideShellClock(FeatureId.HOME_ASSISTANT, signedIn = false))
    }

    @Test
    fun shownOverHomeAssistant_whenSignedIn() {
        assertFalse(hideShellClock(FeatureId.HOME_ASSISTANT, signedIn = true))
    }

    @Test
    fun shownOverSpotify_regardlessOfSignIn() {
        assertFalse(hideShellClock(FeatureId.SPOTIFY, signedIn = false))
        assertFalse(hideShellClock(FeatureId.SPOTIFY, signedIn = true))
    }
}
