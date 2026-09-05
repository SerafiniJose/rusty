package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.view.MotionEvent
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
 */
open class CardDialog(activity: Activity) : Dialog(activity) {
    init {
        window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
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
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }
}
