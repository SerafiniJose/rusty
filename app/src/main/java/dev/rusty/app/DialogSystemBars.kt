package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.view.Window
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Makes a popup card's window honour the host's immersive-fullscreen setting.
 *
 * Immersive is a per-WINDOW state. [HomeActivity] hides the system bars on its own window, but every
 * [Dialog] is a separate window with default (bars-visible) attributes, and the moment it takes focus
 * Android applies THOSE — so the status and navigation bars slid back in for exactly as long as
 * Settings or Info was open, then vanished again on dismiss ([HomeActivity.reassertImmersiveIfEnabled]).
 * Hiding the bars on the dialog's OWN window before `show()` lands the request in its attributes (the
 * not-yet-attached decor hands out a pending controller that replays it on attach), so the bars never
 * come back while a card is up.
 *
 * Call after `setContentView`, before `show()` — alongside [followDisplaySize] at every card site.
 * [HomeActivity.setFullscreen] also re-applies it to cards already open, so flipping the switch inside
 * Settings takes effect on the sheet itself, not only after it closes.
 */
fun Dialog.matchHostSystemBars(activity: Activity) {
    val home = activity as? HomeActivity ?: return
    window?.applySystemBarsHidden(home.isFullscreenEnabled)
}

/** Hides or shows the system bars on [this] window, with the same swipe-to-peek behaviour as the shell. */
fun Window.applySystemBarsHidden(hidden: Boolean) {
    val controller = WindowCompat.getInsetsController(this, decorView)
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    if (hidden) controller.hide(WindowInsetsCompat.Type.systemBars())
    else controller.show(WindowInsetsCompat.Type.systemBars())
}
