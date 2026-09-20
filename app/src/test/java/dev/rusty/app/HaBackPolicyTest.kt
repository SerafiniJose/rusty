package dev.rusty.app

import org.junit.Assert.assertEquals
import org.junit.Test

class HaBackPolicyTest {

    // ---- Web history wins first, on either input device -------------------------------------

    @Test fun unwindsWebHistoryBeforeAnythingElse() {
        assertEquals(
            HaBackAction.WEB_HISTORY,
            HaBackPolicy.decide(webViewShowing = true, canGoBack = true, inTouchMode = false),
        )
        assertEquals(
            HaBackAction.WEB_HISTORY,
            HaBackPolicy.decide(webViewShowing = true, canGoBack = true, inTouchMode = true),
        )
    }

    // ---- Remote: the press that used to leave Rusty now lands on the chrome ------------------

    @Test fun focusesChromeWhenHistoryIsSpentOnARemote() {
        assertEquals(
            HaBackAction.FOCUS_CHROME,
            HaBackPolicy.decide(webViewShowing = true, canGoBack = false, inTouchMode = false),
        )
    }

    /** The sign-in form, not the dashboard: there is no web history to unwind. */
    @Test fun focusesChromeFromTheSignInFormOnARemote() {
        assertEquals(
            HaBackAction.FOCUS_CHROME,
            HaBackPolicy.decide(webViewShowing = false, canGoBack = true, inTouchMode = false),
        )
    }

    // ---- Touch is untouched: focus is invisible in touch mode, so BACK must still exit -------

    @Test fun exitsOnTouchOnceHistoryIsSpent() {
        assertEquals(
            HaBackAction.EXIT,
            HaBackPolicy.decide(webViewShowing = true, canGoBack = false, inTouchMode = true),
        )
        assertEquals(
            HaBackAction.EXIT,
            HaBackPolicy.decide(webViewShowing = false, canGoBack = true, inTouchMode = true),
        )
    }

    // ---- The fallback: BACK must never become a dead key ------------------------------------

    /**
     * The chrome can refuse focus (hidden behind the screensaver, or mid-teardown). The caller
     * asks for the action again with [HaBackPolicy.whenChromeRefusedFocus], which must always
     * leave the user somewhere — never consuming the press.
     */
    @Test fun fallsBackToExitWhenTheChromeWillNotTakeFocus() {
        assertEquals(HaBackAction.EXIT, HaBackPolicy.whenChromeRefusedFocus())
    }
}
