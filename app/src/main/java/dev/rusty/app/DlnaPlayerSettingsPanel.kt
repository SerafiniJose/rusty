package dev.rusty.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.speech.tts.TextToSpeech
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

        // -- announcement voice ---------------------------------------------------------------

        val voiceValue = panel.findViewById<TextView>(R.id.tvTtsVoiceValue)
        val changeVoice = panel.findViewById<MaterialButton>(R.id.btnChangeTtsVoice)

        fun voiceLabel(): String = when (
            val sel = prefs.getString(TtsVoices.PREF_KEY, null)?.let { TtsVoices.parse(it) }
        ) {
            null, VoiceSelector.SystemDefault -> "System default"
            is VoiceSelector.System -> sel.voiceName
            is VoiceSelector.Piper ->
                // The catalog's human label when the id is curated (loadCatalog is cached, so
                // this is not an asset read per bind); the raw id only for an unknown one.
                PiperVoiceStore.loadCatalog(activity).firstOrNull { it.id == sel.voiceId }?.label
                    ?: sel.voiceId
        }
        voiceValue.text = voiceLabel()

        // The engine spun up for enumeration; alive only from a SUCCESSFUL init to picker
        // dismissal (or panel teardown, whichever comes first — the cleanup lambda below covers
        // a dialog outliving the tab). While init is pending the button itself is disabled, so
        // that state needs no extra flag.
        var voiceEngine: TextToSpeech? = null
        fun shutdownVoiceEngine() {
            voiceEngine?.let { runCatching { it.shutdown() } }
            voiceEngine = null
        }

        changeVoice.setOnClickListener {
            if (voiceEngine != null) return@setOnClickListener   // picker already up
            changeVoice.isEnabled = false

            // Some engines deliver onInit SYNCHRONOUSLY from the constructor (notably the
            // immediate ERROR when no engine is installed) — before `engine` below is assigned.
            // The handoff runs the shared handler either from the callback (async init, engine
            // already set) or right after the constructor (sync init, status parked in
            // pendingStatus); `handled` makes a double arrival harmless.
            var engine: TextToSpeech? = null
            var pendingStatus: Int? = null
            var handled = false

            fun handleInit(status: Int) {
                if (handled) return
                handled = true
                changeVoice.isEnabled = true
                val e = engine
                if (status != TextToSpeech.SUCCESS || e == null) {
                    e?.let { runCatching { it.shutdown() } }
                    showFeedback(
                        feedback,
                        "Text-to-speech isn't available on this device.",
                        HaFeedbackKind.ERROR,
                    )
                    return
                }
                // A late async init can outlive the settings dialog; showing a Dialog over a
                // finishing Activity is a BadTokenException.
                if (activity.isFinishing || activity.isDestroyed) {
                    runCatching { e.shutdown() }
                    return
                }
                voiceEngine = e
                // Same composition as GET /api/tts/voices: default, then downloaded Piper
                // voices, then the system engine's own.
                val piperStore = PiperVoiceStore(activity.applicationContext)
                val voices = listOf(SystemTtsVoices.defaultRow()) +
                    piperStore.installedRows(PiperVoiceStore.loadCatalog(activity)) +
                    SystemTtsVoices.list(e)
                val selected = prefs.getString(TtsVoices.PREF_KEY, null)
                    ?.takeIf { TtsVoices.parse(it) != null } ?: TtsVoices.SYSTEM_DEFAULT
                TtsVoicePickerDialog(activity, voices, selected) { choice ->
                    prefs.edit().putString(TtsVoices.PREF_KEY, choice.id).apply()
                    voiceValue.text = voiceLabel()
                }.show(onDismissed = ::shutdownVoiceEngine)
            }

            engine = TextToSpeech(activity) { status ->
                // Init callbacks can arrive on a binder thread on some engines.
                activity.runOnUiThread {
                    if (engine == null) pendingStatus = status else handleInit(status)
                }
            }
            pendingStatus?.let { handleInit(it) }
        }

        return {
            RendererStatusPublisher.removeListener(statusListener)
            shutdownVoiceEngine()
        }
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
