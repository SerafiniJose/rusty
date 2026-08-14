package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.view.View
import android.view.WindowManager

/** Fraction of the display width every popup card spans. */
const val CARD_DIALOG_WIDTH_FRACTION = 0.72f

/** Fraction of the display height the Services & status card's content may grow to before it scrolls. */
const val INFO_CARD_MAX_HEIGHT_FRACTION = 0.82f

/**
 * The pixel ceiling for a card's scrolling content, or 0 for "no ceiling".
 *
 * This is deliberately NOT [cardDialogSizePx]'s `heightFraction`: that pins the WINDOW to a fixed
 * height, so a short card on a tall TV would keep a large transparent dead band inside its own window
 * — and since no card sets `setCanceledOnTouchOutside`, a tap in that band lands inside the window and
 * does not dismiss. A cap applied to the scroll container instead lets a short page wrap and a long one
 * scroll, which is what "cap content height at roughly 82% of the display" actually asks for.
 */
fun maxCardContentHeightPx(displayHeightPx: Int, fraction: Float): Int =
    if (fraction <= 0f || displayHeightPx <= 0) 0 else (displayHeightPx * fraction).toInt()

/**
 * The window size a popup card should have on a display of [displayWidthPx] x [displayHeightPx].
 *
 * Height is [WindowManager.LayoutParams.WRAP_CONTENT] unless [heightFraction] is given (the Immich
 * picker pins its own height so its long list scrolls inside a fixed card).
 */
fun cardDialogSizePx(
    displayWidthPx: Int,
    displayHeightPx: Int,
    heightFraction: Float? = null,
): Pair<Int, Int> =
    (displayWidthPx * CARD_DIALOG_WIDTH_FRACTION).toInt() to
        (heightFraction?.let { (displayHeightPx * it).toInt() } ?: WindowManager.LayoutParams.WRAP_CONTENT)

/** Sizes this dialog's window against the display metrics as they are right now. */
fun Dialog.applyCardSize(activity: Activity, heightFraction: Float? = null) {
    val metrics = activity.resources.displayMetrics
    val (width, height) = cardDialogSizePx(metrics.widthPixels, metrics.heightPixels, heightFraction)
    window?.setLayout(width, height)
}

/**
 * Sizes the card now AND re-sizes it on every rotation for as long as it is showing.
 *
 * A dialog window's size is a pixel count, fixed at the moment [setLayout][android.view.Window.setLayout]
 * is called. [HomeActivity] absorbs configuration changes itself (`android:configChanges`) so nothing
 * is re-created on rotation — a card opened in landscape therefore kept its landscape pixel width
 * after the device turned to portrait and overflowed the screen. Re-applying on the activity's
 * configuration change is the fix.
 *
 * The follow is scoped to the decor view's attachment, which spans exactly show..dismiss, so it needs
 * no cooperation from (and cannot clash with) the caller's own dismiss listener.
 */
fun Dialog.followDisplaySize(activity: Activity, heightFraction: Float? = null) {
    applyCardSize(activity, heightFraction)
    val home = activity as? HomeActivity ?: return
    val decor = window?.decorView ?: return
    val resize: () -> Unit = { applyCardSize(activity, heightFraction) }
    decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = home.addConfigurationChangeListener(resize)
        override fun onViewDetachedFromWindow(v: View) = home.removeConfigurationChangeListener(resize)
    })
}
