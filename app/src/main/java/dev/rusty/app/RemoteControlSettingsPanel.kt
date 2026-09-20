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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton

/**
 * Binder for the Remote Control settings tab — the control API's own surface, present exactly
 * while the API toggle in General is on. Not a [Feature]: like the Slideshow tab, it has no
 * launcher entry; the tab is threaded through `settingsTabsFor` explicitly.
 *
 * Three sections:
 *  - CONTROL PAGE: the server's address and a Show QR button, so a phone can reach the page by
 *    scanning instead of typing. Follows [ControlServerStatus] live; the code encodes the plain
 *    URL only, never the password (the page asks for that itself).
 *  - ACCESS: the API password. The switch and the secret are separate — the password survives
 *    turning the requirement off — but the switch can never be left on without a stored password
 *    ([ControlSettings.requiredPassword] would enforce nothing, so the UI refuses the state
 *    rather than pretend): enabling with no password detours through the set-password dialog,
 *    and cancelling that reverts the switch.
 *  - ANNOUNCEMENTS: the voice quality and the announcement voice (moved here from the DLNA tab
 *    — announcements are a control-page capability, and no longer need the DLNA player running
 *    at all). Quality comes first because it decides which voices there are to pick from.
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

        // -- control page: address + QR ----------------------------------------------------------

        val pageAddress = panel.findViewById<TextView>(R.id.tvControlPageAddress)
        val showQr = panel.findViewById<MaterialButton>(R.id.btnShowControlQr)
        var pageUrl: String? = null

        fun repaintPage(state: ControlServerStatus.State) {
            val row = ControlPageQrModel.row(state)
            pageAddress.text = row.address
            showQr.isEnabled = row.qrEnabled
            pageUrl = row.url
        }
        // Replays the current state on registration, so this is also the initial paint.
        val serverListener: (ControlServerStatus.State) -> Unit = { repaintPage(it) }
        ControlServerStatus.addListener(serverListener)

        showQr.setOnClickListener {
            val url = pageUrl ?: return@setOnClickListener
            val root = activity.layoutInflater.inflate(R.layout.dialog_control_qr, null)
            val card = Dialog(activity)
            card.requestWindowFeature(Window.FEATURE_NO_TITLE)
            card.setContentView(root)
            card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            // Not followDisplaySize: that sizes a CARD to the display, and this is only the
            // code — the window wraps the tile so "outside" starts right at its edge.
            card.window?.setLayout(
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
                android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            )
            card.matchHostSystemBars(activity)
            // The code is the whole dialog: nothing to press, so a tap outside it (touch) or
            // Back (D-pad) is how it closes.
            card.setCanceledOnTouchOutside(true)
            val image = root as ImageView
            // Rendered at the view's own size so each module lands on whole pixels — a blurry
            // upscale is the one thing that makes a code hard to read.
            val px = (300 * activity.resources.displayMetrics.density).toInt()
            image.setImageBitmap(QrBitmap.render(QrCode.encode(url), px))
            card.show()
        }

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
            card.matchHostSystemBars(activity)

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

        // -- announcement voice ---------------------------------------------------------------
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
            ControlServerStatus.removeListener(serverListener)
            shutdownVoiceEngine()
        }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
