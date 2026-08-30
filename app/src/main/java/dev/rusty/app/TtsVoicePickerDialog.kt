package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat

/**
 * Single-choice card picker for the announcement voice, the on-device twin of the control page's
 * Voice row. Pure UI: the caller supplies the (already enumerated) [voices] and persists the
 * selection in [onSelect]; this class never touches prefs or the TTS engine.
 *
 * Rows are [RadioButton]s built in code rather than a RecyclerView: the list is bounded (an
 * engine ships tens of voices, not thousands), radios are natively D-pad focusable, and a tap IS
 * the commit — pick and the card closes, the lockscreen-chip interaction one level deeper.
 */
class TtsVoicePickerDialog(
    private val activity: Activity,
    private val voices: List<VoiceInfo>,
    private val selectedId: String,
    private val onSelect: (VoiceInfo) -> Unit,
) {

    fun show(onDismissed: () -> Unit = {}) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_tts_voice_picker, null)
        val d = Dialog(activity)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(root)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Re-sizes on rotation too, like the Immich picker card.
        d.followDisplaySize(activity, heightFraction = 0.80f)

        val rows = root.findViewById<LinearLayout>(R.id.llVoiceRows)
        val font = ResourcesCompat.getFont(activity, R.font.hanken_regular)
        val accent = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent_fallback))
        var focusTarget: RadioButton? = null

        voices.forEach { v ->
            val radio = RadioButton(activity).apply {
                text = rowLabel(v)
                isChecked = v.id == selectedId
                typeface = font
                textSize = 14f
                setTextColor(ContextCompat.getColor(activity, R.color.ink))
                buttonTintList = accent
                foreground = ContextCompat.getDrawable(activity, R.drawable.bg_tv_focus_switch)
                setPadding(dp(6), dp(10), dp(6), dp(10))
                setOnClickListener {
                    onSelect(v)
                    d.dismiss()
                }
            }
            if (radio.isChecked) focusTarget = radio
            rows.addView(
                radio,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        root.findViewById<View>(R.id.btnVoiceClose).setOnClickListener { d.dismiss() }
        d.setOnDismissListener { onDismissed() }
        d.show()
        // D-pad entry lands on the current choice, so OK-without-moving is a no-op re-commit.
        (focusTarget ?: rows.getChildAt(0))?.requestFocus()
    }

    /** Mirrors the control page's option labels so both pickers describe a voice identically. */
    private fun rowLabel(v: VoiceInfo): String = buildString {
        if (v.language.isNotEmpty()) append(v.language).append(" · ")
        append(v.label)
        if (v.quality == "high" || v.quality == "very_high") append(" ★")
        if (!v.installed) append(" (needs download)")
        else if (v.requiresNetwork) append(" (online)")
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
