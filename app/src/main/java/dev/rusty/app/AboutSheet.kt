package dev.rusty.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.format.DateFormat
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import java.util.Date

/**
 * The shell-owned About & updates card: the running version, the update on offer with its
 * release notes laid out by section ([WhatsNewView]), Check now, and the repo/issue links. It
 * reads the day's cached check first ([UpdateNotice.current]) so it opens already filled in, and
 * opening it with an update on offer marks that release seen, which clears the info-button dot.
 * A release with no APK asset falls back to opening the release page.
 *
 * The shell's lifecycle outlives the card, so the guards are the activity's own
 * finishing/destroyed flags plus `dialog.isShowing` — which is also the sole stop condition for the
 * install poll.
 */
object AboutSheet {

    /** Two What's new columns from this screen width up (landscape Echo Show, tablets, TVs). */
    private const val TWO_COLUMN_MIN_WIDTH_DP = 720

    fun show(activity: HomeActivity) {
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_about, null)
        val dialog = createCardDialog(activity, view)
        val version = appVersionName(activity)

        val versionLine = view.findViewById<TextView>(R.id.tvAboutVersion)
        val releaseMeta = view.findViewById<TextView>(R.id.tvReleaseMeta)
        val statusLine = view.findViewById<TextView>(R.id.tvUpdateStatus)
        val downloadButton = view.findViewById<MaterialButton>(R.id.btnDownload)
        val whatsNewBlock = view.findViewById<View>(R.id.layWhatsNew)
        val whatsNewSections = view.findViewById<LinearLayout>(R.id.llWhatsNew)
        val whatsNewPlain = view.findViewById<TextView>(R.id.tvWhatsNew)
        val detailsButton = view.findViewById<MaterialButton>(R.id.btnWhatsNewDetails)
        val checkedAt = view.findViewById<TextView>(R.id.tvCheckedAt)
        val checkNow = view.findViewById<MaterialButton>(R.id.btnCheckNow)
        val sourceRow = view.findViewById<View>(R.id.rowSource)

        versionLine.text = "Version $version"
        sourceRow.setOnClickListener { openUrl(activity, UpdateRepository.REPO_URL) }
        view.findViewById<View>(R.id.rowReportIssue)
            .setOnClickListener { openUrl(activity, UpdateRepository.ISSUES_URL) }

        val twoColumns = activity.resources.configuration.screenWidthDp >= TWO_COLUMN_MIN_WIDTH_DP
        var sections: List<ReleaseNotes.Section> = emptyList()
        var showDetails = false
        var installBoundFor: String? = null
        var installPoll: Job? = null

        fun renderWhatsNew() {
            WhatsNewView.bind(activity, whatsNewSections, sections, showDetails, twoColumns)
            detailsButton.text = if (showDetails) "Hide details" else "Show details"
        }
        detailsButton.setOnClickListener {
            showDetails = !showDetails
            renderWhatsNew()
        }

        fun render(check: UpdateRepository.UpdateCheck?) {
            val checkedMs = check?.checkedAtMs
            checkedAt.visibility = if (checkedMs != null) View.VISIBLE else View.GONE
            if (checkedMs != null) checkedAt.text = checkedLabel(activity, checkedMs)
            val failedNote = if (check?.lastCheckFailed == true) "The last check couldn't reach GitHub." else null

            val latest = check?.latest
            if (check?.status == UpdateRepository.UpdateStatus.UPDATE_AVAILABLE && latest != null) {
                versionLine.text = versionArrow(activity, version, latest.versionName)
                val meta = listOfNotNull(
                    ReleaseNotes.releasedLabel(latest.publishedAt, System.currentTimeMillis(), ZoneId.systemDefault()),
                    ReleaseNotes.sizeLabel(latest.apkSizeBytes)?.let { "$it download" },
                ).joinToString(" · ")
                releaseMeta.text = meta
                releaseMeta.visibility = if (meta.isEmpty()) View.GONE else View.VISIBLE
                statusLine.text = failedNote.orEmpty()
                statusLine.visibility = if (failedNote == null) View.GONE else View.VISIBLE

                downloadButton.visibility = View.VISIBLE
                if (installBoundFor != latest.versionName) {
                    installBoundFor = latest.versionName
                    val apkUrl = latest.apkUrl
                    installPoll?.cancel()
                    if (apkUrl == null) {
                        // Release without an APK asset — the browser is all we can offer.
                        downloadButton.text = "Open release page"
                        downloadButton.setOnClickListener { openUrl(activity, latest.releaseUrl) }
                    } else {
                        installPoll = bindDirectInstall(activity, dialog, downloadButton, statusLine, apkUrl, latest)
                    }
                    // A remote lands on Update rather than on Check now once there is one.
                    if (!downloadButton.isInTouchMode && (checkNow.isFocused || view.findFocus() == null)) {
                        downloadButton.post {
                            downloadButton.requestFocus()
                            view.scrollTo(0, 0)
                        }
                    }
                }

                sections = ReleaseNotes.parse(latest.notes)
                whatsNewBlock.visibility = if (latest.notes.isBlank()) View.GONE else View.VISIBLE
                if (sections.isEmpty()) {
                    whatsNewSections.visibility = View.GONE
                    whatsNewPlain.visibility = View.VISIBLE
                    whatsNewPlain.text = latest.notes
                    detailsButton.visibility = View.GONE
                } else {
                    whatsNewSections.visibility = View.VISIBLE
                    whatsNewPlain.visibility = View.GONE
                    detailsButton.visibility =
                        if (sections.any { s -> s.entries.any { it.detail.isNotEmpty() } }) View.VISIBLE else View.GONE
                    renderWhatsNew()
                }
                UpdateNotice.markSeen(latest.versionName)
            } else {
                versionLine.text = "Version $version"
                releaseMeta.visibility = View.GONE
                downloadButton.visibility = View.GONE
                whatsNewBlock.visibility = View.GONE
                statusLine.visibility = View.VISIBLE
                statusLine.text = when (check?.status) {
                    null -> "Checking for updates…"
                    UpdateRepository.UpdateStatus.UP_TO_DATE ->
                        listOfNotNull("You're on the latest version.", failedNote).joinToString(" ")
                    else -> "Couldn't check for updates. Try Check now, or open Source & releases."
                }
            }
        }

        /** [force] = Check now. The first, unforced call is answered from the day's cache. */
        fun runCheck(force: Boolean) {
            checkNow.isEnabled = false
            checkNow.text = "Checking…"
            // withContext(IO) cancels UI application when the activity goes away, but a blocking
            // network call may still run to its timeout — guard with the activity's own flags +
            // isShowing before touching any view.
            activity.lifecycleScope.launch {
                val check = withContext(Dispatchers.IO) { UpdateRepository.check(version, force) }
                if (!activity.isAlive() || !dialog.isShowing) return@launch
                checkNow.isEnabled = true
                checkNow.text = "Check now"
                render(check)
            }
        }
        checkNow.setOnClickListener { runCheck(force = true) }

        // After show(): the install poll runs only while the dialog is showing.
        dialog.show()
        render(UpdateNotice.current())
        runCheck(force = false)
        // Check now is always present; Update takes the focus over once a check finds one.
        requestInitialFocus(if (downloadButton.visibility == View.VISIBLE) downloadButton else checkNow)
        // Focusing Update scrolls a card taller than the screen down to it; keep the header (the
        // versions and release date) in view instead — Update sits right under it.
        view.post { view.scrollTo(0, 0) }
    }

    /** "Version 2.6.0 → 2.7.0", the new version in the ink colour. */
    private fun versionArrow(activity: HomeActivity, current: String, latest: String): CharSequence {
        val ink = ContextCompat.getColor(activity, R.color.ink)
        return SpannableStringBuilder("Version $current → ").apply {
            val start = length
            append(latest)
            setSpan(ForegroundColorSpan(ink), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun checkedLabel(activity: HomeActivity, checkedAtMs: Long): String =
        ReleaseNotes.checkedLabel(
            checkedAtMs,
            System.currentTimeMillis(),
            ZoneId.systemDefault(),
            DateFormat.getTimeFormat(activity).format(Date(checkedAtMs)),
        )

    /**
     * Wires the Update button to the in-app installer instead of the browser: download → system
     * confirm dialog on this screen, no browser round-trip (which on a TV meant a D-pad fight with a
     * download manager).
     *
     * A 500 ms poll — not a listener — drives the label, because the installer is shared with the
     * remote-control API: an install started from the control page may already be running when this
     * sheet opens, and the poll picks that up with zero extra wiring.
     *
     * After a failure the button becomes a browser fallback to [releaseUrl] — whatever broke the
     * in-app path (disk, network, installer refusal), the release page always works. Returns the
     * poll, so a newer release found by Check now can replace it.
     */
    private fun bindDirectInstall(
        activity: HomeActivity,
        dialog: Dialog,
        button: MaterialButton,
        statusLine: TextView,
        apkUrl: String,
        release: UpdateRepository.ReleaseInfo,
    ): Job {
        val releaseUrl = release.releaseUrl
        val installer = ApkInstall.installer(activity)
        button.setOnClickListener {
            if (installer.snapshot().phase == InstallPhase.ERROR) {
                openUrl(activity, releaseUrl)
            } else {
                installer.start(apkUrl)
            }
        }
        return activity.lifecycleScope.launch {
            while (activity.isAlive() && dialog.isShowing) {
                val snap = installer.snapshot()
                when (snap.phase) {
                    InstallPhase.IDLE -> {
                        button.isEnabled = true
                        button.text = "Update to ${release.versionName}"
                    }
                    InstallPhase.DOWNLOADING -> {
                        button.isEnabled = false
                        button.text = snap.progress?.let { "Downloading… $it%" } ?: "Downloading…"
                    }
                    InstallPhase.AWAITING_CONFIRM -> {
                        button.isEnabled = false
                        button.text = "Confirm on screen"
                    }
                    InstallPhase.ERROR -> {
                        button.isEnabled = true
                        button.text = "Open release page"
                        statusLine.visibility = View.VISIBLE
                        statusLine.text = "Install failed: ${snap.error}"
                    }
                }
                delay(500)
            }
        }
    }

    /** The running version, read from the package manager (the same source the fragment used). */
    fun appVersionName(activity: HomeActivity): String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
    }.getOrNull() ?: "unknown"

    /** Opens [url] in a browser, ignoring the (unlikely) no-browser case rather than crashing. */
    internal fun openUrl(activity: HomeActivity, url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser/handler available — nothing actionable to do.
        }
    }

    /** No-op in touch mode; posted so layout has completed before focus traversal. */
    internal fun requestInitialFocus(target: View) {
        if (target.isInTouchMode) return
        target.post { target.requestFocus() }
    }

    /**
     * A centered, rounded popup card, following the display so it re-sizes on rotation (the shell
     * absorbs configuration changes, so nothing is re-created and a landscape-width card would
     * otherwise overflow a portrait screen). Tracked by the shell so [HomeActivity.onDestroy] can
     * close it instead of leaking a window.
     */
    internal fun createCardDialog(
        activity: HomeActivity,
        view: View,
        onDismiss: (() -> Unit)? = null,
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.followDisplaySize(activity)
        dialog.matchHostSystemBars(activity)
        activity.trackShellDialog(dialog)
        dialog.setOnDismissListener {
            activity.untrackShellDialog(dialog)
            activity.reassertImmersiveIfEnabled()
            onDismiss?.invoke()
        }
        return dialog
    }

    private fun HomeActivity.isAlive(): Boolean = !isFinishing && !isDestroyed
}
