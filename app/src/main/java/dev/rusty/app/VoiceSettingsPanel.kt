package dev.rusty.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.view.View
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

/**
 * Binder for the Voice settings tab — what the device speaks with. Split out of the Remote
 * Control tab so voices have a section of their own; it sits right after that tab and, like it,
 * is present exactly while the control API toggle in General is on, because announcements only
 * reach the device through the control API. Not a [Feature]: the tab is threaded through
 * `settingsTabsFor` explicitly.
 *
 * One section today:
 *  - ANNOUNCEMENTS: whether the announcement card shows the words on screen
 *    ([ControlSettings.isAnnouncementCardEnabled], on by default), then the announcement voice.
 *    The voice's Manage card holds the voice quality (as filter chips) and the downloadable
 *    Piper catalog.
 */
class VoiceSettingsPanel(private val ctx: SettingsPanelContext) : SettingsPanelProvider {

    override val layoutRes: Int = R.layout.settings_panel_voice

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity

        // Read by each activity's AnnouncementCard on every render, so there is nothing to push.
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        panel.findViewById<SwitchMaterial>(R.id.switchAnnouncementCard).apply {
            isChecked = ControlSettings.isAnnouncementCardEnabled(prefs)
            setOnCheckedChangeListener { _, isChecked ->
                ControlSettings.setAnnouncementCardEnabled(prefs, isChecked)
            }
        }

        // The one voices row: quality lives INSIDE the picker card as filter chips over the
        // catalog, so the panel no longer owns a tier row or dialog of its own.

        val voiceValue = panel.findViewById<TextView>(R.id.tvTtsVoiceValue)
        val changeVoice = panel.findViewById<MaterialButton>(R.id.btnChangeTtsVoice)
        // Labelling and persistence live in the model, shared with the control routes.
        fun repaintVoice(model: TtsVoicePickerModel) { voiceValue.text = model.rowValue() }
        repaintVoice(TtsVoicePickerModel(activity, engine = null))

        // No "start the DLNA player to hear these" hint any more: the control service voices
        // announcements through a pipeline of its own when the media renderer is stopped, so the
        // only thing that can silence them is having no voice, which the picker says itself.

        // The engine spun up for enumeration; alive only from a SUCCESSFUL init to picker
        // dismissal (or panel teardown, whichever comes first — the cleanup lambda below covers
        // a dialog outliving the tab). While init is pending, repeat presses are ignored by
        // [engineStarting] rather than by disabling the button: a disabled button drops D-pad
        // focus, which then lands on the first settings tab and stays there after the picker
        // closes (Google's engine takes seconds to init).
        var voiceEngine: TextToSpeech? = null
        fun shutdownVoiceEngine() {
            voiceEngine?.let { runCatching { it.shutdown() } }
            voiceEngine = null
        }
        var pickerOpen = false
        var engineStarting = false

        changeVoice.setOnClickListener {
            if (pickerOpen || engineStarting) return@setOnClickListener   // up, or on its way
            engineStarting = true

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
                engineStarting = false
                // A failed init is NOT a dead end: plenty of devices ship no TTS engine at all,
                // and neither the default row nor a downloaded Piper voice needs one. The picker
                // opens with whatever this device really has — system voices only when an engine
                // answered — and the card itself says why the system list is missing.
                val e = engine?.takeIf { status == TextToSpeech.SUCCESS }
                if (e == null) engine?.let { runCatching { it.shutdown() } }
                // A late async init can outlive the settings dialog; showing a Dialog over a
                // finishing Activity is a BadTokenException.
                if (activity.isFinishing || activity.isDestroyed) {
                    e?.let { runCatching { it.shutdown() } }
                    return
                }
                voiceEngine = e
                pickerOpen = true
                val model = TtsVoicePickerModel(activity, e)
                TtsVoicePickerDialog(activity, model) { repaintVoice(model) }
                    .show(onDismissed = {
                        pickerOpen = false
                        shutdownVoiceEngine()
                    })
            }

            engine = TextToSpeech(activity) { status ->
                // Init callbacks can arrive on a binder thread on some engines.
                activity.runOnUiThread {
                    if (engine == null) pendingStatus = status else handleInit(status)
                }
            }
            pendingStatus?.let { handleInit(it) }
        }

        return { shutdownVoiceEngine() }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
