package dev.rusty.app

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
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
 * Two sections:
 *  - CONTROL PAGE: the server's address and a Show QR button, so a phone can reach the page by
 *    scanning instead of typing. Follows [ControlServerStatus] live; the code encodes the plain
 *    URL only, never the password (the page asks for that itself).
 *  - ACCESS: the API password. The switch and the secret are separate — the password survives
 *    turning the requirement off — but the switch can never be left on without a stored password
 *    ([ControlSettings.requiredPassword] would enforce nothing, so the UI refuses the state
 *    rather than pretend): enabling with no password detours through the set-password dialog,
 *    and cancelling that reverts the switch.
 *
 * The announcement voice lives in its own tab next to this one ([VoiceSettingsPanel]).
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

        return { ControlServerStatus.removeListener(serverListener) }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
    }
}
