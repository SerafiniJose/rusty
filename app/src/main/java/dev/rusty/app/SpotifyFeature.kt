package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText

/** Spotify Connect: always enabled, always configured. */
object SpotifyFeature : Feature {
    override val id = FeatureId.SPOTIFY
    override val title = "Spotify"
    override val iconRes = R.drawable.ic_music_note
    override fun isEnabled(prefs: SharedPreferences) = true
    override fun createFragment(): Fragment = SpotifyFragment()
    override val settingsTab = SettingsTabKey.SPOTIFY

    override fun settingsPanel(ctx: SettingsPanelContext): SettingsPanelProvider =
        SpotifySettingsPanel(ctx)
}

/**
 * Feature-owned settings panel for Spotify Connect.
 *
 * Owns: device name change, bitrate slider, service start/stop toggle.
 * Moved verbatim from [SettingsSheet.bindSpotify]; no behavior changes.
 */
private class SpotifySettingsPanel(
    private val ctx: SettingsPanelContext,
) : SettingsPanelProvider {

    override val layoutRes = R.layout.settings_panel_spotify

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val host = ctx.host
        val state = ctx.state

        val nameValue = panel.findViewById<TextView>(R.id.tvReceiverNameValue)
        val changeButton = panel.findViewById<MaterialButton>(R.id.btnChangeName)
        val editRow = panel.findViewById<View>(R.id.rowReceiverNameEdit)
        val nameInput = panel.findViewById<TextInputEditText>(R.id.etReceiverName)
        val saveButton = panel.findViewById<MaterialButton>(R.id.btnSaveName)
        val bitrateSlider = panel.findViewById<Slider>(R.id.sliderBitrate)
        val bitrateValue = panel.findViewById<TextView>(R.id.tvBitrateValue)
        val feedback = panel.findViewById<TextView>(R.id.tvSettingsFeedback)
        val serviceStatusValue = panel.findViewById<TextView>(R.id.tvReceiverStatusValue)
        val toggleServiceButton = panel.findViewById<MaterialButton>(R.id.btnToggleService)

        nameValue.text = host.currentDeviceName
        nameInput.setText(host.currentDeviceName)
        bitrateSlider.value = bitrateToIndex(host.currentBitrateKbps)
        bitrateValue.text = bitrateLabel(host.currentBitrateKbps)

        fun renderServiceToggle() {
            val isOff = state().status == "Off"
            toggleServiceButton.text = if (isOff) "Start" else "Stop"
            serviceStatusValue.text =
                if (isOff) "Off" else "Running · listening for Spotify"
        }
        renderServiceToggle()

        fun hideNameKeyboard() {
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
            nameInput.clearFocus()
        }

        // Reveal the editor AND immediately drop the user into typing: focus the field,
        // place the caret at the end, and pop the soft keyboard. So tapping "Change" goes
        // straight to input — then Done/Enter or Save commits, no extra tap to open the IME.
        fun showNameKeyboard() {
            nameInput.requestFocus()
            nameInput.setSelection(nameInput.text?.length ?: 0)
            val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
        }

        changeButton.setOnClickListener {
            val reveal = editRow.visibility != View.VISIBLE
            editRow.visibility = if (reveal) View.VISIBLE else View.GONE
            if (reveal) {
                nameInput.setText(host.currentDeviceName)
                // Post so the row is laid out/visible before we request focus + IME.
                nameInput.post { showNameKeyboard() }
            } else {
                hideNameKeyboard()
            }
        }

        // Commit the typed name. Shared by the Save button and the keyboard's Done/Enter
        // action so either path renames AND dismisses the keyboard. The empty case keeps
        // the keyboard up so the user can type a name; the others close the editor.
        fun commitName() {
            val newName = nameInput.text?.toString()?.trim().orEmpty()
            when {
                newName.isEmpty() ->
                    showFeedback(feedback, "Enter a receiver name first.", FEEDBACK_NEUTRAL)
                newName == host.currentDeviceName -> {
                    hideNameKeyboard()
                    showFeedback(feedback, "No change — already “${host.currentDeviceName}”.", FEEDBACK_NEUTRAL)
                }
                else -> {
                    // The shell performs the rename (reconnect-under-new-name if a session is
                    // live, in-place mDNS re-advertise otherwise); the feedback copy mirrors
                    // which path it took, decided the same way the shell does.
                    val sessionActive = state().sessionUser != null
                    host.applyReceiverName(newName)
                    if (sessionActive) {
                        showFeedback(feedback, "✓ Renamed to “$newName” — reconnecting…", FEEDBACK_SUCCESS)
                    } else {
                        showFeedback(feedback, "✓ Renamed to “$newName” — no restart.", FEEDBACK_SUCCESS)
                    }
                    nameValue.text = newName
                    editRow.visibility = View.GONE
                    hideNameKeyboard()
                }
            }
        }

        saveButton.setOnClickListener { commitName() }
        toggleServiceButton.setOnClickListener {
            if (state().status == "Off") {
                // Re-start: reuses the permission-gated start path. Bypasses the
                // once-per-process auto-start guard, which is intentional — the user
                // explicitly asked to start it again.
                host.startReceiver()
            } else {
                // The shell stops the service (clean teardown via onDestroy, publishes OFF)
                // and renders OFF immediately via the shared snapshot so the sheet/header
                // update without waiting for the broadcast round-trip.
                host.stopReceiver()
            }
            renderServiceToggle()
        }
        // Commit on the IME "Done" action (soft keyboard) and on a hardware/Bluetooth
        // Enter key, which arrives as a KEYCODE_ENTER event with an unspecified action id
        // rather than IME_ACTION_DONE. Gate on ACTION_DOWN so it fires once per press.
        nameInput.setOnEditorActionListener { _, actionId, event ->
            val enterKeyDown = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enterKeyDown) {
                commitName()
                true
            } else {
                false
            }
        }

        bitrateSlider.addOnChangeListener { _, value, _ ->
            bitrateValue.text = bitrateLabel(indexToBitrate(value))
        }
        bitrateSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val selected = indexToBitrate(slider.value)
                if (selected != host.currentBitrateKbps) {
                    host.applyBitrate(selected)
                    showFeedback(feedback, "✓ Switching to ${bitrateLabel(selected)}…", FEEDBACK_SUCCESS)
                }
            }
        })

        val canvasSwitch = panel.findViewById<SwitchMaterial>(R.id.switchCanvas)
        canvasSwitch.isChecked = activity.isCanvasEnabled
        canvasSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.setCanvasEnabled(isChecked)
        }

        // No subscriptions to release.
        return {}
    }

    private fun showFeedback(view: TextView, message: String, color: Int) {
        view.text = message
        view.setTextColor(color)
        view.visibility = View.VISIBLE
    }

    private fun bitrateLabel(value: Int): String = when (value) {
        96 -> "96 kbps · fastest"
        160 -> "160 kbps · balanced"
        320 -> "320 kbps · highest"
        else -> "$value kbps"
    }

    private fun bitrateToIndex(kbps: Int): Float = when (kbps) {
        96 -> 0f
        320 -> 2f
        else -> 1f
    }

    private fun indexToBitrate(index: Float): Int = when (index.toInt()) {
        0 -> 96
        2 -> 320
        else -> 160
    }

    private val FEEDBACK_SUCCESS = 0xFF38EF7D.toInt()
    private val FEEDBACK_NEUTRAL = 0xFF8B949E.toInt()
}
