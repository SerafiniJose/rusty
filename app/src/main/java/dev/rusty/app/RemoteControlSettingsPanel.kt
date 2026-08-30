package dev.rusty.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton
import dev.rusty.app.renderer.RendererStatus
import dev.rusty.app.renderer.RendererStatusPublisher
import dev.rusty.app.renderer.RendererStatusSnapshot

/**
 * Binder for the Remote Control settings tab — the control API's own surface, present exactly
 * while the API toggle in General is on. Not a [Feature]: like the Slideshow tab, it has no
 * launcher entry; the tab is threaded through `settingsTabsFor` explicitly.
 *
 * Two sections:
 *  - ACCESS: the API password. The switch and the secret are separate — the password survives
 *    turning the requirement off — but the switch can never be left on without a stored password
 *    ([ControlSettings.requiredPassword] would enforce nothing, so the UI refuses the state
 *    rather than pretend): enabling with no password detours through the set-password dialog,
 *    and cancelling that reverts the switch.
 *  - ANNOUNCEMENTS: the voice quality and the announcement voice (moved here from the DLNA tab
 *    — announcements are a control-page capability; the DLNA player is merely the pipeline that
 *    speaks them). Quality comes first because it decides which voices there are to pick from.
 *    The renderer hint keeps that dependency honest without re-gating anything on it.
 */
class RemoteControlSettingsPanel(private val ctx: SettingsPanelContext) : SettingsPanelProvider {

    override val layoutRes: Int = R.layout.settings_panel_remote_control

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secrets = SecretStore.of(activity)

        // -- access ---------------------------------------------------------------------------

        val authSwitch = panel.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchControlAuth)
        val authStatus = panel.findViewById<TextView>(R.id.tvControlAuthStatus)
        val passwordValue = panel.findViewById<TextView>(R.id.tvControlPasswordValue)
        val changePassword = panel.findViewById<MaterialButton>(R.id.btnChangeControlPassword)
        val feedback = panel.findViewById<TextView>(R.id.tvRemoteControlFeedback)

        fun hasPassword() = !secrets.get(ControlSettings.SECRET_PASSWORD).isNullOrBlank()

        fun repaintAccess() {
            passwordValue.text = if (hasPassword()) "Set" else "Not set"
            authStatus.text = if (ControlSettings.isAuthRequired(prefs)) {
                "On — the control page and Home Assistant must send it"
            } else {
                "Off — anyone on your network can control this device"
            }
        }
        authSwitch.isChecked = ControlSettings.isAuthRequired(prefs)
        repaintAccess()

        // Guards the programmatic revert (cancelled enable) from re-entering the listener.
        var suppressSwitch = false

        /**
         * Masked-input card. [onSaved] runs only after a non-blank password is stored — the
         * enable flow uses it to complete the switch; a plain "Change" passes nothing.
         * The password is trimmed to match [ControlAuth.bearerToken]'s outer-whitespace trim:
         * a value that can't survive the header round-trip must not be storable.
         */
        fun openPasswordDialog(onSaved: (() -> Unit)? = null, onCancelled: (() -> Unit)? = null) {
            val root = activity.layoutInflater.inflate(R.layout.dialog_control_password, null)
            val card = Dialog(activity)
            card.requestWindowFeature(Window.FEATURE_NO_TITLE)
            card.setContentView(root)
            card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            card.followDisplaySize(activity)

            val input = root.findViewById<EditText>(R.id.etControlPassword)
            val error = root.findViewById<TextView>(R.id.tvControlPasswordError)
            var saved = false

            root.findViewById<MaterialButton>(R.id.btnControlPasswordCancel).setOnClickListener { card.dismiss() }
            root.findViewById<MaterialButton>(R.id.btnControlPasswordSave).setOnClickListener {
                val password = input.text?.toString()?.trim().orEmpty()
                if (password.isEmpty()) {
                    error.text = "Enter a password."
                    error.visibility = View.VISIBLE
                    return@setOnClickListener
                }
                secrets.put(ControlSettings.SECRET_PASSWORD, password)
                saved = true
                card.dismiss()
                repaintAccess()
                showFeedback(feedback, "Password saved.", HaFeedbackKind.SUCCESS)
                onSaved?.invoke()
            }
            card.setOnDismissListener { if (!saved) onCancelled?.invoke() }
            card.show()
            input.requestFocus()
        }

        authSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressSwitch) return@setOnCheckedChangeListener
            when {
                !isChecked -> {
                    // The password stays stored for the next enable; only the requirement lifts.
                    ControlSettings.setAuthRequired(prefs, false)
                    repaintAccess()
                }
                hasPassword() -> {
                    ControlSettings.setAuthRequired(prefs, true)
                    repaintAccess()
                }
                else -> openPasswordDialog(
                    onSaved = {
                        ControlSettings.setAuthRequired(prefs, true)
                        repaintAccess()
                    },
                    onCancelled = {
                        suppressSwitch = true
                        authSwitch.isChecked = false
                        suppressSwitch = false
                    },
                )
            }
        }

        changePassword.setOnClickListener { openPasswordDialog() }

        // -- announcement quality ---------------------------------------------------------------

        // Reads and writes the tier only; the same model the picker uses, so both surfaces
        // agree without the panel touching prefs itself.
        val qualityModel = TtsVoicePickerModel(activity, engine = null)
        val qualityValue = panel.findViewById<TextView>(R.id.tvTtsQualityValue)
        val changeQuality = panel.findViewById<MaterialButton>(R.id.btnChangeTtsQuality)

        fun repaintQuality() {
            val quality = qualityModel.quality()
            qualityValue.text = "${quality.label} \u2014 ${quality.hint}"
        }
        repaintQuality()

        /** Four rows built from [VoiceQuality] itself, so the vocabulary has one home. A pick
         *  IS the commit, the voice picker's posture exactly. */
        fun openQualityDialog() {
            val root = activity.layoutInflater.inflate(R.layout.dialog_voice_quality, null)
            val card = Dialog(activity)
            card.requestWindowFeature(Window.FEATURE_NO_TITLE)
            card.setContentView(root)
            card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            card.followDisplaySize(activity)

            val rows = root.findViewById<LinearLayout>(R.id.llQualityRows)
            val selected = qualityModel.quality()
            val font = ResourcesCompat.getFont(activity, R.font.hanken_regular)
            val accent = ColorStateList.valueOf(
                ContextCompat.getColor(activity, R.color.accent_fallback),
            )
            val density = activity.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()

            var checkedRow: RadioButton? = null
            VoiceQuality.entries.forEach { quality ->
                val radio = RadioButton(activity).apply {
                    text = "${quality.label} \u2014 ${quality.hint}"
                    isChecked = quality == selected
                    typeface = font
                    textSize = 14f
                    setTextColor(ContextCompat.getColor(activity, R.color.ink))
                    buttonTintList = accent
                    foreground = ContextCompat.getDrawable(activity, R.drawable.bg_tv_focus_switch)
                    setPadding(dp(6), dp(10), dp(6), dp(10))
                    setOnClickListener {
                        qualityModel.selectQuality(quality)
                        repaintQuality()
                        card.dismiss()
                    }
                }
                if (radio.isChecked) checkedRow = radio
                rows.addView(
                    radio,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            card.show()
            // The remote lands on the current choice, not on the first row.
            checkedRow?.requestFocus()
        }

        changeQuality.setOnClickListener { openQualityDialog() }

        // -- announcement voice ---------------------------------------------------------------

        val voiceValue = panel.findViewById<TextView>(R.id.tvTtsVoiceValue)
        val changeVoice = panel.findViewById<MaterialButton>(R.id.btnChangeTtsVoice)
        val rendererHint = panel.findViewById<TextView>(R.id.tvAnnounceRendererHint)
        // Labelling and persistence live in the model, shared with the control routes.
        fun repaintVoice(model: TtsVoicePickerModel) { voiceValue.text = model.selectionLabel() }
        repaintVoice(TtsVoicePickerModel(activity, engine = null))

        // Announcements only make sound through the running DLNA player; the row still works
        // without it (the choice is a preference), so this is a hint, not a gate. Replays the
        // current snapshot on registration, like the DLNA panel's status line.
        val rendererListener: (RendererStatusSnapshot) -> Unit = { snap ->
            val alive = snap.status == RendererStatus.RUNNING || snap.status == RendererStatus.STARTING
            rendererHint.visibility = if (alive) View.GONE else View.VISIBLE
        }
        RendererStatusPublisher.addListener(rendererListener)

        // The engine spun up for enumeration; alive only from a SUCCESSFUL init to picker
        // dismissal (or panel teardown, whichever comes first — the cleanup lambda below covers
        // a dialog outliving the tab). While init is pending the button itself is disabled, so
        // that state needs no extra flag.
        var voiceEngine: TextToSpeech? = null
        fun shutdownVoiceEngine() {
            voiceEngine?.let { runCatching { it.shutdown() } }
            voiceEngine = null
        }
        var pickerOpen = false

        changeVoice.setOnClickListener {
            if (pickerOpen) return@setOnClickListener   // picker already up
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

        return {
            RendererStatusPublisher.removeListener(rendererListener)
            shutdownVoiceEngine()
        }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
