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
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
 */
internal class CameraAddCard(
    private val activity: Activity,
    private val scope: CoroutineScope,
    private val cameras: () -> List<CameraRecord>,
    private val askCredentials: (cameraName: String?, onSubmit: (username: String, password: String) -> Unit) -> Unit,
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

        val title = root.findViewById<TextView>(R.id.tvCamAddTitle)
        val choices = root.findViewById<View>(R.id.camAddChoices)
        val scanRow = root.findViewById<View>(R.id.rowCamAddScan)
        val scanSpinner = root.findViewById<ProgressBar>(R.id.camAddScanSpinner)
        val scanChevron = root.findViewById<View>(R.id.tvCamAddScanChevron)
        val addressRow = root.findViewById<View>(R.id.rowCamAddAddress)
        val manualRow = root.findViewById<View>(R.id.rowCamAddManual)
        val pane = root.findViewById<View>(R.id.camAddResultsPane)
        val results = root.findViewById<LinearLayout>(R.id.camAddResults)
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
            askCredentials(displayName) { user, pass ->
                scope.launch {
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
            divider.isVisible = scanned
            manualButton.isVisible = true
            rescanButton.isVisible = scanned
            rescanButton.text = "Rescan"
            error.isVisible = false
        }

        fun renderResults(found: List<DiscoveredCamera>, localAddress: Pair<String, Int>) {
            results.removeAllViews()
            empty.isVisible = found.isEmpty()
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

        fun scan() {
            scanSpinner.isVisible = true
            scanChevron.isVisible = false
            rescanButton.isEnabled = false
            rescanButton.text = "Scanning…"
            scanRow.isEnabled = false
            scope.launch {
                // Fresh io/discovery per scan — AndroidDiscoveryIo's socket dies on close().
                val (found, localAddress) = withContext(Dispatchers.IO) {
                    val io = AndroidDiscoveryIo(activity)
                    val list = runCatching { OnvifDiscovery(io).scan() }.getOrElse { emptyList() }
                    list to io.localAddress()
                }
                scanSpinner.isVisible = false
                scanChevron.isVisible = true
                scanRow.isEnabled = true
                rescanButton.isEnabled = true
                title.text = "Cameras on this network"
                showPane(scanned = true)
                renderResults(found, localAddress)
                if (!results.isInTouchMode) (results.getChildAt(0)?.takeIf { it.isFocusable } ?: manualButton).requestFocus()
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
            scope.launch {
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
