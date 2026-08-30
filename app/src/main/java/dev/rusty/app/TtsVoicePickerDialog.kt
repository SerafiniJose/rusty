package dev.rusty.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.android.material.button.MaterialButton

/**
 * Announcement-voice picker: the selectable voices on top, the downloadable Piper catalog below,
 * the on-device twin of the control page's Voice row and voice-catalog chips.
 *
 * Rows are built in code rather than with a RecyclerView: the list is bounded (an engine ships
 * tens of voices, not thousands), radios and buttons are natively D-pad focusable, and a tap on
 * a voice IS the commit — pick and the card closes.
 *
 * The dialog owns no state of its own; every read and every action goes through
 * [TtsVoicePickerModel], which is also what the control routes use. While a download runs it
 * polls the process-wide slot and repaints ONLY the catalog labels and the progress bar — a full
 * rebuild every tick would yank D-pad focus out from under the user.
 */
class TtsVoicePickerDialog(
    private val activity: Activity,
    private val model: TtsVoicePickerModel,
    /** Fired after a pick, a download or a delete, so the settings row repaints its value. */
    private val onSelectionChanged: () -> Unit,
) {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dialog: Dialog
    private lateinit var rowsBox: LinearLayout
    private lateinit var catalogBox: LinearLayout
    private lateinit var catalogHeader: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var note: TextView

    /** Every download/remove button on screen, so a busy tick can park them all without
     *  rebuilding (a rebuild would yank D-pad focus mid-download). */
    private var catalogButtons: List<View> = emptyList()

    /** A message from the last action, outranking the standing note until the next rebuild. */
    private var notice: String? = null
    private var polling = false

    fun show(onDismissed: () -> Unit = {}) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_tts_voice_picker, null)
        dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Re-sizes on rotation too, like the Immich picker card.
        dialog.followDisplaySize(activity, heightFraction = 0.80f)

        rowsBox = root.findViewById(R.id.llVoiceRows)
        catalogBox = root.findViewById(R.id.llVoiceCatalog)
        progressBar = root.findViewById(R.id.pbVoiceDownload)
        note = root.findViewById(R.id.tvVoiceNote)
        catalogHeader = root.findViewById(R.id.tvVoiceCatalogHeader)
        catalogHeader.isVisible(model.catalogEntries().isNotEmpty())

        render(focusSelected = true)

        root.findViewById<View>(R.id.btnVoiceClose).setOnClickListener { dialog.dismiss() }
        dialog.setOnDismissListener {
            handler.removeCallbacksAndMessages(null)
            onDismissed()
        }
        dialog.show()
    }

    // -- rendering ----------------------------------------------------------------------------

    /**
     * Rebuilds both lists from the model. [focusSelected] moves D-pad focus to the current
     * choice (dialog entry); a rebuild after an action instead restores focus to the catalog
     * entry that was acted on, so the remote never lands on nothing.
     */
    private fun render(focusSelected: Boolean = false, refocusVoiceId: String? = null) {
        val selectedId = model.selectedId()
        val font = ResourcesCompat.getFont(activity, R.font.hanken_regular)
        val accent = ColorStateList.valueOf(ContextCompat.getColor(activity, R.color.accent_fallback))

        val download = model.downloadSnapshot()
        val busy = download.busy
        val buttons = ArrayList<View>()
        var refocusButton: View? = null
        val inflater = LayoutInflater.from(activity)

        rowsBox.removeAllViews()
        var selectedRadio: RadioButton? = null
        model.rows().forEach { v ->
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
                    model.select(v)
                    onSelectionChanged()
                    dialog.dismiss()
                }
            }
            if (radio.isChecked) selectedRadio = radio

            // A downloaded voice is removed from where it lives — its own row — rather than
            // from a second copy of itself in the list below. Nothing else is deletable: the
            // default row is static and a system engine's voices are Android's, not ours.
            val installed = model.installedVoiceFor(v)
            if (installed == null) {
                rowsBox.addView(radio, matchWidth())
            } else {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                row.addView(
                    radio,
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                val remove = ImageButton(activity).apply {
                    setImageDrawable(HaIcons.iconDrawable(activity, "mdi:delete-outline", dp(22)))
                    imageTintList = accent
                    setBackgroundResource(R.drawable.bg_tv_focus)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Remove ${installed.label}"
                    // Parked while the single download slot is busy, like every catalog action.
                    isEnabled = !busy
                    alpha = if (busy) 0.4f else 1f
                    setOnClickListener { onDelete(installed) }
                }
                if (installed.id == refocusVoiceId) refocusButton = remove
                buttons.add(remove)
                row.addView(
                    remove,
                    LinearLayout.LayoutParams(dp(40), dp(40)),
                )
                rowsBox.addView(row, matchWidth())
            }
        }

        catalogBox.removeAllViews()
        val quality = model.quality()
        catalogHeader.text = "DOWNLOADABLE VOICES \u00b7 ${quality.label}"
        val entries = model.downloadableEntries()
        // Empty means either "you already have them all" or "this tier has none for these
        // languages" — both normal, and worth telling apart where the rows would have been.
        if (entries.isEmpty()) {
            catalogBox.addView(
                TextView(activity).apply {
                    // Which emptiness this is: the tier having voices at all is exactly what
                    // separates "you already downloaded them" from "there are none here".
                    text = TtsVoices.catalogEmptyNote(quality, allInstalled = model.qualityHasVoices())
                    typeface = font
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(activity, R.color.muted_dim))
                    setPadding(dp(2), dp(8), dp(2), dp(4))
                },
                matchWidth(),
            )
        }
        entries.forEach { entry ->
            val item = inflater.inflate(R.layout.item_tts_catalog_voice, catalogBox, false)
            item.findViewById<TextView>(R.id.tvVoiceName).text = entry.voice.label
            item.findViewById<TextView>(R.id.tvVoiceMeta).text = TtsVoices.catalogMetaShort(entry.voice)
            val action = item.findViewById<ImageButton>(R.id.btnVoiceAction).apply {
                setImageDrawable(HaIcons.iconDrawable(activity, "mdi:download", dp(22)))
                imageTintList = accent
                contentDescription = TtsVoices.catalogActionDescription(entry)
                // One slot process-wide: while anything is in flight every action is parked,
                // not queued — the same posture as the page's chips.
                isEnabled = !busy
                alpha = if (busy) 0.4f else 1f
                setOnClickListener { onDownload(entry.voice) }
            }
            if (entry.voice.id == refocusVoiceId) refocusButton = action
            buttons.add(action)
            catalogBox.addView(item)
        }
        catalogButtons = buttons

        paintDownloadState(download)
        when {
            refocusButton != null -> refocusButton?.requestFocus()
            // D-pad entry lands on the current choice, so OK-without-moving is a no-op re-commit.
            focusSelected -> (selectedRadio ?: rowsBox.getChildAt(0))?.requestFocus()
        }
        if (busy) schedulePoll()
    }

    /**
     * Repaints only what a download tick changes: the catalog labels, whether actions are live,
     * and the progress bar. Focus and the selectable rows are left alone.
     */
    private fun repaintDownload() {
        val download = model.downloadSnapshot()
        catalogButtons.forEach { button ->
            button.isEnabled = !download.busy
            button.alpha = if (download.busy) 0.4f else 1f
        }
        paintDownloadState(download)
    }

    private fun paintDownloadState(download: VoiceDownloadSnapshot) {
        if (download.busy) {
            progressBar.visibility = View.VISIBLE
            // Only a byte-counted download has a percentage; verify and unpack are opaque.
            val pct = download.progress.takeIf { download.phase == VoiceDownloadPhase.DOWNLOADING }
            progressBar.isIndeterminate = pct == null
            if (pct != null) progressBar.progress = pct
        } else {
            progressBar.visibility = View.GONE
        }
        // One status line: the live download outranks a just-acted message, which outranks the
        // standing explanation.
        note.text = TtsVoices.downloadStatus(download, model.catalogLabelFor(download.voiceId))
            ?: notice
            ?: standingNote()
    }

    /** What the card says when nothing has just happened. */
    private fun standingNote(): String = if (model.hasSystemEngine) {
        "More voices can be installed in Android's text-to-speech settings."
    } else {
        // The case that used to block this picker entirely.
        "This device has no system speech engine, so “System default” can't speak — " +
            "download a voice above to hear announcements."
    }

    // -- actions ------------------------------------------------------------------------------

    /**
     * Confirms first, because that card is where the voice's full size, license and dataset
     * attribution are shown — the terms belong at the decision, not spread across every row.
     */
    private fun onDownload(voice: PiperVoice) {
        confirm(
            title = "Download ${voice.label}?",
            body = TtsVoices.catalogMeta(voice),
            confirmLabel = "Download",
        ) {
            notice = model.download(voice)
            render(refocusVoiceId = voice.id)
        }
    }

    /** Confirms too: what stops an accidental D-pad OK from throwing away a 64 MB install. */
    private fun onDelete(voice: PiperVoice) {
        confirm(
            title = "Remove ${voice.label}?",
            body = "Its ${TtsVoices.megabytes(voice.sizeBytes)} of voice data will be " +
                "deleted from this device. You can download it again later.",
            confirmLabel = "Remove",
        ) {
            notice = model.delete(voice) ?: "Removed ${voice.label}."
            // A delete may have reset the selection to the default, so the settings row behind
            // the dialog repaints too.
            onSelectionChanged()
            render(refocusVoiceId = voice.id)
        }
    }

    /** Small two-button card over the picker, D-pad focus landing on the confirming action. */
    private fun confirm(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit) {
        val root = LayoutInflater.from(activity).inflate(R.layout.dialog_voice_confirm, null)
        val card = Dialog(activity)
        card.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.setContentView(root)
        card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        card.followDisplaySize(activity)
        root.findViewById<TextView>(R.id.tvConfirmTitle).text = title
        root.findViewById<TextView>(R.id.tvConfirmBody).text = body
        root.findViewById<MaterialButton>(R.id.btnConfirmCancel).setOnClickListener { card.dismiss() }
        val ok = root.findViewById<MaterialButton>(R.id.btnConfirmOk).apply {
            text = confirmLabel
            setOnClickListener {
                card.dismiss()
                onConfirm()
            }
        }
        card.show()
        ok.requestFocus()
    }

    private fun schedulePoll() {
        if (polling) return
        polling = true
        handler.postDelayed(
            {
                polling = false
                if (!dialog.isShowing) return@postDelayed
                val download = model.downloadSnapshot()
                if (download.busy) {
                    repaintDownload()
                    schedulePoll()
                } else {
                    // The finishing tick DOES rebuild: an installed voice becomes a selectable
                    // row, and its catalog action becomes Remove.
                    notice = null
                    render(refocusVoiceId = download.voiceId)
                    onSelectionChanged()
                }
            },
            POLL_MS,
        )
    }

    // -- labels / helpers ---------------------------------------------------------------------

    /** Language, name, and — for a downloaded Piper voice — the quality tier it belongs to. */
    private fun rowLabel(v: VoiceInfo): String = buildString {
        if (v.language.isNotEmpty()) append(v.language).append(" · ")
        append(v.label)
        // Only a downloaded voice names its tier: it is the thing the quality row chose, and an
        // installed voice is never filtered out, so a Medium one must explain itself while the
        // catalog below offers Low. A system voice's self-reported bucket is not that choice.
        if (TtsVoices.parse(v.id) is VoiceSelector.Piper) {
            VoiceQuality.parse(v.quality)?.let { append(" · ").append(it.label) }
        }
        if (!v.installed) append(" (needs download)")
        else if (v.requiresNetwork) append(" (online)")
        // Not a page label: on a device with no engine at all the default row is still
        // selectable (and is what a deleted voice falls back to) but nothing can speak it.
        if (v.id == TtsVoices.SYSTEM_DEFAULT && !model.hasSystemEngine) append(" (no engine)")
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun View.isVisible(visible: Boolean) {
        visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()

    private companion object {
        /** In-process snapshot read, so this can be far tighter than the page's 1.5 s poll. */
        const val POLL_MS = 500L
    }
}
