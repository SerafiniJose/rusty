package dev.rusty.app

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.children
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How long a Rusty result row ignores a repeat tap after the first — long enough to swallow a
 *  hardware-duplicated D-pad OK (the reason this guard exists at all), short enough that it never
 *  outlives addRusty's own credentials prompt. That prompt's Cancel/BACK has no callback of any
 *  kind, so the row cannot wait for a signal that a cancelled prompt will never send; it has to
 *  re-arm itself on a timer instead. */
private const val RUSTY_ROW_TAP_GUARD_MS = 250L

/** What ONVIF discovery hands the Add form: every field it could work out, ready to be edited. */
internal data class DiscoveryPrefill(
    val name: String,
    val rtspUrl: String,
    val mainRtspUrl: String?,
    val mainSkippedNote: String?,
    val snapshotUrl: String?,
    val username: String?,
    val password: String?,
)

/**
 * The "Add a camera" chooser card (dialog_camera_add.xml): scan this network, look one up by
 * address, or go straight to the form. Scan and address turn the same card into a results pane;
 * tapping a result asks for the camera's login ([askCredentials]), resolves its streams over
 * ONVIF and hands a [DiscoveryPrefill] to [onResolved], which opens the Add form. [onManual]
 * opens it empty. The card dismisses itself before either callback so the form is the only card up.
 *
 * Replaces the old Discovery settings section: the scan is now something you meet on the way to
 * adding a camera instead of a second place to know about.
 *
 * The scan looks for two different things at once: ONVIF cameras (multicast probe) and other Rusty
 * devices sharing their own camera (mDNS, [RustyCameraDiscovery]). A Rusty peer already advertises
 * everything the form needs, so its row skips the ONVIF resolve entirely — one tap, and at most a
 * password. [ownDeviceId] is this device's Remote Control identity, which the Rusty scan uses to
 * leave this device's own share out of the list.
 */
internal class CameraAddCard(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val cameras: () -> List<CameraRecord>,
    private val ownDeviceId: String,
    /** [fixedUsername] non-null pre-fills the login's user name and locks the field — a Rusty share
     *  always authenticates as [BasicAuth.USER], so only its password is ever in question. */
    private val askCredentials: (
        cameraName: String?,
        fixedUsername: String?,
        onSubmit: (username: String, password: String) -> Unit,
    ) -> Unit,
    private val onManual: () -> Unit,
    private val onResolved: (DiscoveryPrefill) -> Unit,
) {
    fun show() {
        val root = activity.layoutInflater.inflate(R.layout.dialog_camera_add, null)
        val card = CardDialog(activity)
        card.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.setContentView(root)
        card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        card.followDisplaySize(activity)
        card.matchHostSystemBars(activity)

        // Own child job, so every way this card ends — Cancel, a resolved row's own
        // card.dismiss(), or hardware/remote BACK — cancels whatever scan/resolve/lookup is still
        // in flight instead of leaving it running against a dialog nobody can see. (BACK alone
        // routes through Dialog.cancel() -> dismiss(); tap-outside does not dismiss here at all,
        // since nothing in this file — or CardDialog — calls setCanceledOnTouchOutside.) A
        // SupervisorJob child of the panel scope's Job: cancelling it here never cancels that Job,
        // so bind()'s own teardown (scope.cancel()) is untouched; that same teardown still
        // cascades down and cancels this one too if the panel unbinds while the dialog is still
        // up. It must be a supervisor, not a plain child Job: scan(), lookUpAddress() and
        // resolveAndOpen() are launched as independent siblings on this scope, and an uncaught
        // exception in one (lookUpAddress's probe loop and resolveAndOpen's resolve call have no
        // runCatching of their own, unlike scan()'s two sub-scans) must not cancel the others —
        // they can genuinely run concurrently, since nothing cancels a scan when the user switches
        // to the address pane.
        val dialogScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        card.setOnDismissListener { dialogScope.cancel() }

        val title = root.findViewById<TextView>(R.id.tvCamAddTitle)
        val choices = root.findViewById<View>(R.id.camAddChoices)
        val scanRow = root.findViewById<View>(R.id.rowCamAddScan)
        val scanSpinner = root.findViewById<ProgressBar>(R.id.camAddScanSpinner)
        val scanChevron = root.findViewById<View>(R.id.tvCamAddScanChevron)
        val addressRow = root.findViewById<View>(R.id.rowCamAddAddress)
        val manualRow = root.findViewById<View>(R.id.rowCamAddManual)
        val pane = root.findViewById<View>(R.id.camAddResultsPane)
        val results = root.findViewById<LinearLayout>(R.id.camAddResults)
        val rustyHeading = root.findViewById<TextView>(R.id.tvCamAddRustyHeading)
        val rustyResults = root.findViewById<LinearLayout>(R.id.camAddRustyResults)
        val empty = root.findViewById<TextView>(R.id.tvCamAddEmpty)
        val divider = root.findViewById<View>(R.id.camAddDivider)
        val addressField = root.findViewById<EditText>(R.id.etCamAddAddress)
        val lookupButton = root.findViewById<MaterialButton>(R.id.btnCamAddLookup)
        val lookupSpinner = root.findViewById<ProgressBar>(R.id.camAddLookupSpinner)
        val error = root.findViewById<TextView>(R.id.tvCamAddError)
        val manualButton = root.findViewById<MaterialButton>(R.id.btnCamAddManual)
        val rescanButton = root.findViewById<MaterialButton>(R.id.btnCamAddRescan)
        val cancelButton = root.findViewById<MaterialButton>(R.id.btnCamAddCancel)

        fun fail(message: String) {
            error.text = message
            error.isVisible = true
        }

        /** Login prompt → ONVIF resolve → prefilled form. Shared by scan rows and the address path. */
        fun resolveAndOpen(xaddr: String, displayName: String?, onAuthFailed: () -> Unit) {
            // null fixed user name: an ONVIF camera's user name is whatever it was set to.
            askCredentials(displayName, null) { user, pass ->
                dialogScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        OnvifClient(AndroidSoapTransport(), nonceSource = ::randomNonce, canDecode = DeviceDecoders::canDecode)
                            .resolve(xaddr, user, pass)
                    }
                    when (result) {
                        is OnvifResult.Resolved -> {
                            card.dismiss()
                            onResolved(
                                DiscoveryPrefill(
                                    name = displayName ?: "Camera",
                                    rtspUrl = result.streamUri,
                                    mainRtspUrl = result.mainStreamUri,
                                    // Discovery only skips a main stream when it found one this device
                                    // can't decode; the form shows this in amber in place of its hint.
                                    mainSkippedNote = result.skippedMain?.let {
                                        "Main stream skipped: this device can't decode ${it.width}×${it.height} " +
                                            CodecNames.label(CodecNames.mimeForOnvif(it.codec) ?: it.codec)
                                    },
                                    snapshotUrl = result.snapshotUri,
                                    username = user,
                                    password = pass,
                                ),
                            )
                        }
                        is OnvifResult.Failed ->
                            if (result.step == "auth") onAuthFailed()
                            else fail("Couldn't resolve the stream (${result.step}): ${result.detail}")
                    }
                }
            }
        }

        fun showPane(scanned: Boolean) {
            choices.isVisible = false
            pane.isVisible = true
            results.isVisible = scanned
            // Shown again by renderRustyResults, and only when a peer answered this scan: hidden
            // here so the address path — and a rescan that finds none — never leaves a stale
            // heading over an empty list.
            rustyHeading.isVisible = false
            rustyResults.isVisible = false
            divider.isVisible = scanned
            manualButton.isVisible = true
            rescanButton.isVisible = scanned
            rescanButton.text = "Rescan"
            error.isVisible = false
        }

        fun renderResults(found: List<DiscoveredCamera>, localAddress: Pair<String, Int>) {
            results.removeAllViews()
            val known = cameras()
            for (device in found) {
                val xaddr = OnvifDiscoveryProtocol.pickXAddr(device.xaddrs, localAddress.first, localAddress.second)
                val row = activity.layoutInflater.inflate(R.layout.view_camera_discovery_row, results, false)
                val name = device.name ?: device.hardware ?: device.endpointUuid
                row.findViewById<TextView>(R.id.tvCamDiscRowName).text = name
                // An already-added camera is dimmed and named as Rusty knows it, so a wall of
                // identical model names still tells you which ones are left to add.
                val added = xaddr?.let { x -> known.firstOrNull { hostOf(it.rtspUrl) == hostOf(x) } }
                val actionable = added == null && xaddr != null
                row.findViewById<TextView>(R.id.tvCamDiscRowSubtitle).text =
                    if (added != null) "${hostOf(xaddr!!)} · ${added.name}" else xaddr ?: device.xaddrs.firstOrNull().orEmpty()
                row.findViewById<TextView>(R.id.tvCamDiscRowAdded).isVisible = added != null
                row.isEnabled = actionable
                // A disabled View is not automatically pulled out of D-pad focus order — set both
                // explicitly so an already-added / unresolvable row is never a dead focus stop.
                row.isFocusable = actionable
                row.isClickable = actionable
                row.alpha = if (actionable) 1f else 0.5f
                if (actionable) {
                    row.setOnClickListener {
                        resolveAndOpen(xaddr!!, device.name ?: device.hardware) { fail("Wrong username or password for $name") }
                    }
                }
                results.addView(row)
            }
        }

        /** One tap on a Rusty peer: it advertises its own stream and snapshot URLs, so there is no
         *  ONVIF resolve to do — at most a password, and the form opens filled in. Declared before
         *  renderRustyResults, which calls it: Kotlin local functions are not hoisted. */
        fun addRusty(cam: DiscoveredRustyCamera) {
            fun open(user: String?, pass: String?) {
                card.dismiss()
                onResolved(
                    DiscoveryPrefill(
                        name = cam.name,
                        rtspUrl = cam.rtspUrl,
                        // A share has exactly one stream, and nothing was skipped for want of a
                        // decoder: it is 720p H.264 by construction (CameraShareSettings).
                        mainRtspUrl = null,
                        mainSkippedNote = null,
                        snapshotUrl = cam.snapshotUrl,
                        username = user,
                        password = pass,
                    ),
                )
            }
            // The sharing side always authenticates as BasicAuth.USER, so the prompt asks for the
            // password only — there is nothing for the user to get wrong in the other field.
            if (cam.requiresAuth) askCredentials(cam.name, BasicAuth.USER) { user, pass -> open(user, pass) }
            else open(null, null)
        }

        fun renderRustyResults(rusty: List<DiscoveredRustyCamera>) {
            rustyResults.removeAllViews()
            val merged = RustyCameraDiscoveryPlan.merge(rusty, cameras())
            // Heading and list travel together: no peers, no section at all.
            rustyHeading.isVisible = merged.isNotEmpty()
            rustyResults.isVisible = merged.isNotEmpty()
            for ((cam, added) in merged) {
                val row = activity.layoutInflater.inflate(R.layout.view_camera_discovery_row, rustyResults, false)
                row.findViewById<TextView>(R.id.tvCamDiscRowName).text = cam.name
                row.findViewById<TextView>(R.id.tvCamDiscRowSubtitle).text =
                    RustyCameraDiscoveryPlan.rowSubtitle(cam, added)
                row.findViewById<TextView>(R.id.tvCamDiscRowAdded).isVisible = added != null
                // Same dimmed, unfocusable treatment an already-added ONVIF row gets above: a row
                // with nothing left to do must never be a dead stop for the D-pad.
                val actionable = added == null
                row.isEnabled = actionable
                row.isFocusable = actionable
                row.isClickable = actionable
                row.alpha = if (actionable) 1f else 0.5f
                if (actionable) {
                    row.setOnClickListener {
                        // addRusty's no-auth branch runs card.dismiss() then onResolved with no
                        // suspension point in between — the one action on a list row in this file
                        // with no network gap and no existing guard. A duplicated D-pad OK (a real
                        // quirk on the Echo Show/Android TV hardware this app targets) could fire
                        // it twice and stack a second Add-camera form. This only needs to swallow
                        // that duplicate, not lock the row for as long as a modal stays up: on the
                        // auth branch, addRusty opens a credentials prompt whose Cancel/BACK has no
                        // callback at all, so latching isEnabled here permanently would leave a
                        // cancelled prompt's row dead — focusable, full brightness, but inert —
                        // until the whole card was reopened. A timed re-enable, reusing isEnabled
                        // exactly as lookUpAddress does on lookupButton, closes the guard window
                        // without ever needing a signal the cancelled prompt won't send.
                        if (!row.isEnabled) return@setOnClickListener
                        row.isEnabled = false
                        row.postDelayed({ row.isEnabled = true }, RUSTY_ROW_TAP_GUARD_MS)
                        addRusty(cam)
                    }
                }
                rustyResults.addView(row)
            }
        }

        fun scan() {
            scanSpinner.isVisible = true
            scanChevron.isVisible = false
            rescanButton.isEnabled = false
            rescanButton.text = "Scanning…"
            scanRow.isEnabled = false
            dialogScope.launch {
                // Two protocols, one wait: the ONVIF probe and the mDNS browse both take about
                // three seconds, so the Rusty scan runs alongside it instead of after it. Each is
                // caught on its own, so one coming back empty or failing outright still leaves the
                // other's results on screen — but a CancellationException is rethrown rather than
                // swallowed: dialogScope's job is cancelled (see the dismiss listener in show())
                // the moment this dialog is dismissed by any route, so that exception means this
                // dialog is genuinely gone, and none of the view work below may run.
                // Fresh io/discovery per scan — AndroidDiscoveryIo's socket dies on close().
                val (found, localAddress, rusty) = withContext(Dispatchers.IO) {
                    val io = AndroidDiscoveryIo(activity)
                    val rustyScan = async {
                        runCatching { RustyCameraDiscovery(activity).scan(ownDeviceId) }
                            .getOrElse { if (it is CancellationException) throw it else emptyList() }
                    }
                    val list = runCatching { OnvifDiscovery(io).scan() }
                        .getOrElse { if (it is CancellationException) throw it else emptyList() }
                    Triple(list, io.localAddress(), rustyScan.await())
                }
                scanSpinner.isVisible = false
                scanChevron.isVisible = true
                scanRow.isEnabled = true
                rescanButton.isEnabled = true
                title.text = "Cameras on this network"
                showPane(scanned = true)
                renderResults(found, localAddress)
                renderRustyResults(rusty)
                // One note for the whole scan: it only means "nothing at all answered".
                empty.isVisible = found.isEmpty() && rusty.isEmpty()
                // Rusty rows are as tappable as ONVIF ones, so the remote lands on whichever
                // focusable row comes first — never on a dimmed already-added row.
                if (!results.isInTouchMode) {
                    ((results.children + rustyResults.children).firstOrNull { it.isFocusable } ?: manualButton)
                        .requestFocus()
                }
            }
        }

        fun lookUpAddress() {
            // Disabled for the whole probe; a second OK (remote or soft keyboard) while one is in
            // flight must not start another.
            if (!lookupButton.isEnabled) return
            val candidates = OnvifAddress.candidates(addressField.text?.toString().orEmpty())
            error.isVisible = false
            if (candidates.isEmpty()) {
                fail("Enter a host name or IP address")
                return
            }
            lookupButton.isEnabled = false
            lookupSpinner.isVisible = true
            dialogScope.launch {
                var answered: String? = null
                var sawNotOnvif = false
                withContext(Dispatchers.IO) {
                    for (xaddr in candidates) {
                        when (OnvifClient(AndroidSoapTransport(), nonceSource = ::randomNonce).probeDevice(xaddr)) {
                            OnvifProbe.Answered -> { answered = xaddr; break }
                            OnvifProbe.NotOnvif -> sawNotOnvif = true
                            OnvifProbe.NoAnswer -> Unit
                        }
                    }
                }
                lookupButton.isEnabled = true
                lookupSpinner.isVisible = false
                val xaddr = answered
                if (xaddr == null) {
                    val ports = candidates.map { it.substringAfter("://").substringBefore('/').substringAfterLast(':') }
                    fail(if (sawNotOnvif) "Not an ONVIF endpoint" else "Nothing answered on ${ports.joinToString(" or ")}")
                    return@launch
                }
                val host = xaddr.substringAfter("://").substringBefore('/').substringBefore(':').trim('[', ']')
                resolveAndOpen(xaddr, host) { fail("Wrong username or password") }
            }
        }

        scanRow.setOnClickListener { scan() }
        rescanButton.setOnClickListener { scan() }
        addressRow.setOnClickListener {
            title.text = "Add by address"
            showPane(scanned = false)
            addressField.requestFocus()
        }
        manualRow.setOnClickListener { card.dismiss(); onManual() }
        manualButton.setOnClickListener { card.dismiss(); onManual() }
        cancelButton.setOnClickListener { card.dismiss() }
        lookupButton.setOnClickListener { lookUpAddress() }
        // imeOptions is actionGo; a hardware/Bluetooth Enter arrives as a KEYCODE_ENTER event with
        // an unspecified action id instead. Gate on ACTION_DOWN so a remote's OK fires one probe.
        addressField.setOnEditorActionListener { _, actionId, event ->
            val enterKeyDown = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE || enterKeyDown) {
                lookUpAddress()
                true
            } else {
                false
            }
        }

        card.show()
        scanRow.requestFocus()
    }
}

internal fun randomNonce(): ByteArray {
    val bytes = ByteArray(16)
    SecureRandom().nextBytes(bytes)
    return bytes
}

/** Bare host (no port, no userinfo) out of an rtsp/http(s) URL, for the discovery already-added
 *  match. Best-effort string parsing, same idiom as [OnvifDiscoveryProtocol]. */
internal fun hostOf(url: String): String? {
    val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
    if (withoutScheme.isEmpty()) return null
    val authority = withoutScheme.substringBefore('/').substringAfterLast('@')
    return authority.substringBefore(':').lowercase()
}
