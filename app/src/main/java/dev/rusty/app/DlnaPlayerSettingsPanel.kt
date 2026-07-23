package dev.rusty.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.RadioButton
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import dev.rusty.app.renderer.MediaRendererController
import dev.rusty.app.renderer.RenameResult
import dev.rusty.app.renderer.RendererPrefs
import dev.rusty.app.renderer.RendererStatus
import dev.rusty.app.renderer.RendererStatusPublisher
import dev.rusty.app.renderer.RendererStatusSnapshot
import dev.rusty.app.renderer.SharedPrefsRendererStore
import dev.rusty.app.renderer.SpotifyInterruption

/**
 * Binder for the app-wide DLNA Player settings tab. Not a [Feature] — the renderer has no
 * fragment; this panel is the whole UI surface. Status rendering follows the spec's matrix:
 *
 *   STOPPED        "Stopped"               [Start]   address hidden
 *   STARTING       "Starting…"             [Stop]    address hidden
 *   RUNNING + url  "Running"               [Stop]    address visible
 *   RUNNING + null "Running — no network"  [Stop]    address hidden
 *   FAILED         "Couldn't start"        [Start]   address hidden
 *
 * The status listener is registered in [bind] and removed by the returned cleanup lambda, which
 * [SettingsSheet] invokes on BOTH tab-switch AND dialog dismiss.
 */
class DlnaPlayerSettingsPanel(private val ctx: SettingsPanelContext) : SettingsPanelProvider {

    override val layoutRes: Int = R.layout.settings_panel_dlna_player

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val store = SharedPrefsRendererStore(prefs)

        val status = panel.findViewById<TextView>(R.id.tvDlnaStatusValue)
        val toggle = panel.findViewById<MaterialButton>(R.id.btnToggleDlna)
        val nameValue = panel.findViewById<TextView>(R.id.tvDlnaNameValue)
        val changeName = panel.findViewById<MaterialButton>(R.id.btnChangeDlnaName)
        val nameEditRow = panel.findViewById<View>(R.id.rowDlnaNameEdit)
        val nameInput = panel.findViewById<TextInputEditText>(R.id.etDlnaName)
        val saveName = panel.findViewById<MaterialButton>(R.id.btnSaveDlnaName)
        val addressRow = panel.findViewById<View>(R.id.rowDlnaAddress)
        val addressValue = panel.findViewById<TextView>(R.id.tvDlnaAddressValue)
        val copyAddress = panel.findViewById<MaterialButton>(R.id.btnCopyDlnaAddress)
        val feedback = panel.findViewById<TextView>(R.id.tvDlnaFeedback)

        fun showName() {
            val name = RendererPrefs.name(store)
            nameValue.text = name
            nameInput.setText(name)
        }
        showName()

        // Replays the current snapshot on registration — the service usually started long ago.
        val statusListener: (RendererStatusSnapshot) -> Unit = { snap ->
            val url = snap.descriptionUrl
            status.text = when {
                snap.status == RendererStatus.RUNNING && url == null -> "Running — no network"
                snap.status == RendererStatus.RUNNING -> "Running"
                snap.status == RendererStatus.STARTING -> "Starting…"
                snap.status == RendererStatus.FAILED -> "Couldn't start"
                else -> "Stopped"
            }
            toggle.text = when (snap.status) {
                RendererStatus.RUNNING, RendererStatus.STARTING -> "Stop"
                RendererStatus.STOPPED, RendererStatus.FAILED -> "Start"
            }
            addressRow.visibility =
                if (snap.status == RendererStatus.RUNNING && url != null) View.VISIBLE else View.GONE
            if (url != null) addressValue.text = url
        }
        RendererStatusPublisher.addListener(statusListener)

        toggle.setOnClickListener {
            val stopStates = setOf(RendererStatus.RUNNING, RendererStatus.STARTING)
            if (RendererStatusPublisher.current().status in stopStates) {
                MediaRendererController.setEnabled(activity, false)
            } else {
                activity.startDlnaPlayer()   // routes through the POST_NOTIFICATIONS gate
            }
        }

        changeName.setOnClickListener {
            nameEditRow.visibility = View.VISIBLE
            nameInput.requestFocus()
        }

        saveName.setOnClickListener {
            when (val result = MediaRendererController.rename(activity, nameInput.text?.toString().orEmpty())) {
                is RenameResult.Blank ->
                    showFeedback(feedback, "Enter a name for the DLNA player.", HaFeedbackKind.ERROR)
                is RenameResult.Unchanged -> nameEditRow.visibility = View.GONE
                is RenameResult.Renamed -> {
                    showName()
                    nameEditRow.visibility = View.GONE
                    showFeedback(
                        feedback,
                        "Renamed to ${result.name}. Re-add the device in Home Assistant to see the new name.",
                        HaFeedbackKind.SUCCESS,
                    )
                }
            }
        }

        copyAddress.setOnClickListener {
            val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Rusty DLNA player address", addressValue.text))
            showFeedback(feedback, "Address copied.", HaFeedbackKind.SUCCESS)
        }

        // The mix-mode and fade choices used to be RadioGroups, but they now reflow inside a
        // ConstraintLayout Flow (so a narrow card wraps them instead of clipping them). Flow's
        // radios are not a RadioGroup's direct children, so exclusivity is enforced here — the same
        // idiom SettingsSheet.bindScreensaver uses for the theme picker.
        bindRadioChoice(
            options = listOf(
                panel.findViewById<RadioButton>(R.id.rbMixPause) to SpotifyInterruption.PAUSE,
                panel.findViewById<RadioButton>(R.id.rbMixDuck) to SpotifyInterruption.DUCK,
            ),
            selected = RendererPrefs.mixMode(store),
            onSelect = { RendererPrefs.setMixMode(store, it) },
        )

        // 0.5s (DEFAULT_FADE_MS) is the "medium" choice and also the fallback for any stored value
        // that doesn't match a preset, mirroring the previous RadioGroup mapping exactly.
        bindRadioChoice(
            options = listOf(
                panel.findViewById<RadioButton>(R.id.rbFadeOff) to 0L,
                panel.findViewById<RadioButton>(R.id.rbFadeShort) to 250L,
                panel.findViewById<RadioButton>(R.id.rbFadeMedium) to RendererPrefs.DEFAULT_FADE_MS,
                panel.findViewById<RadioButton>(R.id.rbFadeLong) to 1000L,
            ),
            selected = when (RendererPrefs.fadeMs(store)) {
                0L -> 0L
                250L -> 250L
                1000L -> 1000L
                else -> RendererPrefs.DEFAULT_FADE_MS
            },
            onSelect = { RendererPrefs.setFadeMs(store, it) },
        )

        return { RendererStatusPublisher.removeListener(statusListener) }
    }

    /**
     * Wires a set of standalone [RadioButton]s as one mutually-exclusive choice. They are not in a
     * RadioGroup (they are positioned by a Flow, so they are not its direct children), so this
     * checks the option whose value equals [selected] and, on any user check, unchecks the siblings
     * and reports the new value through [onSelect]. [suppress] stops the programmatic sibling
     * unchecks from re-entering [onSelect].
     */
    private fun <T> bindRadioChoice(
        options: List<Pair<RadioButton, T>>,
        selected: T,
        onSelect: (T) -> Unit,
    ) {
        options.forEach { (radio, value) -> radio.isChecked = value == selected }
        var suppress = false
        options.forEach { (radio, value) ->
            radio.setOnCheckedChangeListener { _, isChecked ->
                if (!isChecked || suppress) return@setOnCheckedChangeListener
                suppress = true
                options.forEach { (other, _) -> if (other !== radio) other.isChecked = false }
                suppress = false
                onSelect(value)
            }
        }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
