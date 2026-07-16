package dev.rusty.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.widget.RadioGroup
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
        val mixGroup = panel.findViewById<RadioGroup>(R.id.rgDlnaMixMode)
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

        mixGroup.check(
            if (RendererPrefs.mixMode(store) == SpotifyInterruption.DUCK) R.id.rbMixDuck else R.id.rbMixPause
        )
        mixGroup.setOnCheckedChangeListener { _, checkedId ->
            RendererPrefs.setMixMode(
                store,
                if (checkedId == R.id.rbMixDuck) SpotifyInterruption.DUCK else SpotifyInterruption.PAUSE,
            )
        }

        val fadeGroup = panel.findViewById<RadioGroup>(R.id.rgDlnaFade)
        fadeGroup.check(
            when (RendererPrefs.fadeMs(store)) {
                0L -> R.id.rbFadeOff
                250L -> R.id.rbFadeShort
                1000L -> R.id.rbFadeLong
                else -> R.id.rbFadeMedium
            }
        )
        fadeGroup.setOnCheckedChangeListener { _, checkedId ->
            RendererPrefs.setFadeMs(
                store,
                when (checkedId) {
                    R.id.rbFadeOff -> 0L
                    R.id.rbFadeShort -> 250L
                    R.id.rbFadeLong -> 1000L
                    else -> RendererPrefs.DEFAULT_FADE_MS
                },
            )
        }

        return { RendererStatusPublisher.removeListener(statusListener) }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
