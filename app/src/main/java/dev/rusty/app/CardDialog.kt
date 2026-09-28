package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/** Pure half of [CardDialog]'s tap-outside rule: does a screen touch at ([x], [y]) miss the
 *  rectangle [left]..[right] × [top]..[bottom] (right/bottom exclusive)? */
object KeyboardDismiss {
    fun tapLandsOutside(left: Int, top: Int, right: Int, bottom: Int, x: Float, y: Float): Boolean =
        x < left || x >= right || y < top || y >= bottom
}

/**
 * A popup card with text fields that behaves on a touch appliance.
 *
 * Two things a plain [Dialog] gets wrong here: its window opens with `SOFT_INPUT_STATE_UNSPECIFIED`,
 * so the field the card focuses on open summons the keyboard before the user asked for it; and once
 * the card runs immersive ([matchHostSystemBars]) there is no navigation bar, hence no ▼ button, so
 * the keyboard could only be dismissed by leaving the card. So: the keyboard stays hidden until a
 * field is tapped (`STATE_HIDDEN`), the card shrinks rather than being covered while it is up
 * (`ADJUST_RESIZE`, the Immich picker's idiom), and a touch anywhere outside the focused field hides
 * it again — the field keeps its focus and cursor, a second tap on it brings the keyboard back.
 *
 * That touch is CONSUMED, down to its last event, when it lands outside the card itself: a Dialog
 * closes on an outside tap by default (on the finger-UP), so the tap meant to put the keyboard
 * away also threw away a half-filled form. While a field has focus, only BACK or the card's own
 * buttons close it.
 */
open class CardDialog(activity: Activity) : Dialog(activity) {
    init {
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    /** True from a swallowed outside DOWN to the end of that gesture. */
    private var swallowingGesture = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (swallowingGesture) {
            val action = ev.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) swallowingGesture = false
            if (action != MotionEvent.ACTION_DOWN) return true
            swallowingGesture = false
        }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val focused = currentFocus as? EditText
            if (focused != null) {
                val origin = IntArray(2)
                focused.getLocationOnScreen(origin)
                val outside = KeyboardDismiss.tapLandsOutside(
                    origin[0], origin[1], origin[0] + focused.width, origin[1] + focused.height,
                    ev.rawX, ev.rawY,
                )
                if (outside) {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.hideSoftInputFromWindow(focused.windowToken, 0)
                    if (outsideCard(ev)) {
                        swallowingGesture = true
                        return true
                    }
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Whether [ev] misses the card's window, with the same slop Dialog uses to decide an
     *  outside-tap cancel. */
    private fun outsideCard(ev: MotionEvent): Boolean {
        val decor = window?.decorView ?: return false
        val slop = ViewConfiguration.get(context).scaledWindowTouchSlop
        return ev.x < -slop || ev.y < -slop || ev.x > decor.width + slop || ev.y > decor.height + slop
    }
}
