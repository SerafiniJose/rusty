package dev.rusty.app

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.View
import android.view.Window
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The shell-owned About & updates card, lifted verbatim out of [SpotifyFragment] when Info stopped
 * being a Spotify-only sheet. Behaviour is unchanged: the same 15-minute-cached update check, the same
 * copy, the same "no APK asset → open the release page" fallback, and the same 500 ms install poll.
 *
 * The one thing that had to change is the scope. The fragment version ran on
 * `viewLifecycleOwner.lifecycleScope` and guarded with `isAdded`; the shell's lifecycle is far
 * longer-lived, so the guards are the activity's own finishing/destroyed flags plus `dialog.isShowing`
 * — which is now the sole stop condition for the install poll.
 */
object AboutSheet {

    fun show(activity: HomeActivity) {
        val view = activity.layoutInflater.inflate(R.layout.bottom_sheet_about, null)
        val dialog = createCardDialog(activity, view)
        val version = appVersionName(activity)

        val banner = view.findViewById<View>(R.id.cardUpdateBanner)
        val bannerText = view.findViewById<TextView>(R.id.tvUpdateBannerText)
        val statusLine = view.findViewById<TextView>(R.id.tvUpdateStatus)
        val whatsNewTitle = view.findViewById<TextView>(R.id.tvWhatsNewTitle)
        val whatsNew = view.findViewById<TextView>(R.id.tvWhatsNew)
        val downloadButton = view.findViewById<MaterialButton>(R.id.btnDownload)
        val sourceRow = view.findViewById<View>(R.id.rowSource)

        view.findViewById<TextView>(R.id.tvAboutVersion).text = "Version $version"
        sourceRow.setOnClickListener { openUrl(activity, UpdateRepository.REPO_URL) }

        // withContext(IO) cancels UI application when the activity goes away, but a blocking network
        // call may still run to its timeout — guard with the activity's own flags + isShowing before
        // touching any view.
        activity.lifecycleScope.launch {
            val check = withContext(Dispatchers.IO) { UpdateRepository.check(version) }
            if (!activity.isAlive() || !dialog.isShowing) return@launch
            when (check.status) {
                UpdateRepository.UpdateStatus.UPDATE_AVAILABLE -> {
                    val latest = check.latest!!
                    statusLine.visibility = View.GONE
                    banner.visibility = View.VISIBLE
                    bannerText.text = "Update available · ${latest.versionName}"
                    if (latest.notes.isNotEmpty()) {
                        whatsNewTitle.visibility = View.VISIBLE
                        whatsNew.visibility = View.VISIBLE
                        whatsNew.text = latest.notes
                    }
                    downloadButton.visibility = View.VISIBLE
                    val apkUrl = latest.apkUrl
                    if (apkUrl == null) {
                        // Release without an APK asset — the browser is all we can offer.
                        downloadButton.setOnClickListener { openUrl(activity, latest.releaseUrl) }
                    } else {
                        bindDirectInstall(activity, dialog, downloadButton, statusLine, apkUrl, latest.releaseUrl)
                    }
                }
                UpdateRepository.UpdateStatus.UP_TO_DATE ->
                    statusLine.text = "You're on the latest version."
                UpdateRepository.UpdateStatus.ERROR ->
                    statusLine.text = "Couldn't check for updates. Tap “Source & releases” to check manually."
            }
        }

        dialog.show()
        // "Source & releases" is always present; the Download button only appears once an update is
        // found, so the source row is the reliable initial focus target.
        requestInitialFocus(sourceRow)
    }

    /**
     * Wires the Download button to the in-app installer instead of the browser: download → system
     * confirm dialog on this screen, no browser round-trip (which on a TV meant a D-pad fight with a
     * download manager).
     *
     * A 500 ms poll — not a listener — drives the label, because the installer is shared with the
     * remote-control API: an install started from the control page may already be running when this
     * sheet opens, and the poll picks that up with zero extra wiring.
     *
     * After a failure the button becomes a browser fallback to [releaseUrl] — whatever broke the
     * in-app path (disk, network, installer refusal), the release page always works.
     */
    private fun bindDirectInstall(
        activity: HomeActivity,
        dialog: Dialog,
        button: MaterialButton,
        statusLine: TextView,
        apkUrl: String,
        releaseUrl: String,
    ) {
        val installer = ApkInstall.installer(activity)
        button.setOnClickListener {
            if (installer.snapshot().phase == InstallPhase.ERROR) {
                openUrl(activity, releaseUrl)
            } else {
                installer.start(apkUrl)
            }
        }
        activity.lifecycleScope.launch {
            while (activity.isAlive() && dialog.isShowing) {
                val snap = installer.snapshot()
                when (snap.phase) {
                    InstallPhase.IDLE -> {
                        button.isEnabled = true
                        button.text = "Download"
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
