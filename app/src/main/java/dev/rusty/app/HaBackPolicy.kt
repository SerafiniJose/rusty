package dev.rusty.app

/** What a BACK press on the Home Assistant page should do. */
enum class HaBackAction {
    /** Step back through Home Assistant's own page history. */
    WEB_HISTORY,

    /** Hand focus to the shell's bottom chrome so a remote user stays inside Rusty. */
    FOCUS_CHROME,

    /** Let the press fall through to the activity, which leaves Rusty. */
    EXIT,
}

/**
 * The BACK decision for the Home Assistant page.
 *
 * The page embeds a WebView that takes and keeps D-pad focus, so on a remote the arrow keys go
 * into Home Assistant's own content and never reach the shell's chip bar or settings/info/launcher
 * cluster. BACK used to be no escape either: it unwound HA's history and then finished the
 * activity, dropping the user out of Rusty entirely. With no touchscreen that left the page a
 * one-way trip.
 *
 * The fix is keyed to the input device rather than a setting. In touch mode Android gives no view
 * focus at all, so focusing the chrome would be invisible and BACK would read as dead — worse than
 * exiting. [inTouchMode] is therefore the discriminator, the same signal the rest of the shell
 * already uses, and it flips at runtime as the user switches between the remote and the screen.
 *
 * Pure so it can be tested without Android stubs; the WebView and touch-mode lookups happen at the
 * call site.
 */
object HaBackPolicy {

    /**
     * @param webViewShowing whether the dashboard (not the sign-in form) is on screen.
     * @param canGoBack whether the WebView has history left to unwind.
     * @param inTouchMode whether the user is driving by touch rather than a D-pad.
     */
    fun decide(webViewShowing: Boolean, canGoBack: Boolean, inTouchMode: Boolean): HaBackAction =
        when {
            webViewShowing && canGoBack -> HaBackAction.WEB_HISTORY
            inTouchMode -> HaBackAction.EXIT
            else -> HaBackAction.FOCUS_CHROME
        }

    /**
     * What to do when [HaBackAction.FOCUS_CHROME] was chosen but the chrome refused focus — it can
     * be hidden behind the screensaver, or mid-teardown. Exiting is the safe answer: a consumed
     * press with nothing to show for it would make BACK look broken.
     */
    fun whenChromeRefusedFocus(): HaBackAction = HaBackAction.EXIT
}
