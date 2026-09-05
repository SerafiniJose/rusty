package dev.rusty.app

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// =====================================================================================
// Pure model — CameraEdit / CameraSettingsModel. JVM-testable, no Android imports.
// =====================================================================================

/**
 * One camera add/edit form submission. [id] is null for "add" (a fresh id is assigned) and the
 * existing camera's id for "edit" (identity and [CameraRecord.position] are preserved).
 * [username]/[password] are the dialog's own credential fields; they combine with any inline
 * `user:pass@host` userinfo typed into [rtspUrl] or [snapshotUrl] — see
 * [CameraSettingsModel.applyEdit] for precedence.
 */
data class CameraEdit(
    val id: String?,
    val name: String,
    val rtspUrl: String,
    val snapshotUrl: String?,
    val username: String?,
    val password: String?,
    val audioEnabled: Boolean,
    val forceTcp: Boolean,
)

/**
 * Credentials a [CameraSettingsModel.applyEdit] call wants stored for [cameraId]. Nothing in
 * [CameraSettingsModel] ever touches [SecretStore] itself — the model stays pure — [username]/
 * [password] are independently nullable: each null means "this edit computed no value for this
 * slot" (neither typed nor inline), NOT "leave the stored secret alone". The caller (the panel)
 * turns that into a policy: for an *existing* camera a null field clears the corresponding
 * [SecretStore] key via [CameraStore.credentialKeys] (a field the user emptied on a prefilled
 * dialog must not go on silently feeding the old secret to the stream); for a brand-new camera
 * there is nothing stored yet, so null there is simply a no-write.
 */
data class CameraCredentialWrite(val cameraId: String, val username: String?, val password: String?)

/** The result of [CameraSettingsModel.applyEdit]: the new camera list, [cameraId] of the record
 *  that was added or edited (so the caller always knows which [SecretStore] keys are in play, even
 *  when [credentials] is null), and any credentials to persist (see [CameraCredentialWrite]). */
data class CameraEditResult(val cameras: List<CameraRecord>, val cameraId: String, val credentials: CameraCredentialWrite?)

/**
 * Pure camera CRUD logic backing the settings panel: no Android imports, no I/O — everything here
 * takes and returns plain data so [CameraSettingsModelTest] can exercise it directly.
 *
 * Credentials never get persisted here: [applyEdit] only ever *computes* what should be written
 * ([CameraEditResult.credentials]); the caller stores it via [SecretStore] and this model never
 * even sees a [SecretStore] reference.
 */
object CameraSettingsModel {

    /**
     * Adds (when [CameraEdit.id] is null) or updates (preserving id and position) one camera.
     * Inline `user:pass@host` credentials in [CameraEdit.rtspUrl] or [CameraEdit.snapshotUrl] are
     * split off via [CameraCodec.splitInlineCredentials] so the stored URL is always clean; a
     * non-blank typed [CameraEdit.username]/[CameraEdit.password] field wins over an inline value
     * for the same slot (a user who edits the field after pasting a credentialed URL means what
     * they typed), and the rtsp URL's inline credentials win over the snapshot URL's when both
     * carry them.
     */
    fun applyEdit(existing: List<CameraRecord>, edit: CameraEdit): CameraEditResult {
        val splitRtsp = CameraCodec.splitInlineCredentials(edit.rtspUrl)
        val splitSnapshot = edit.snapshotUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { CameraCodec.splitInlineCredentials(it) }

        val cleanRtsp = splitRtsp.url
        // splitSnapshot is non-null exactly when edit.snapshotUrl is non-blank (see above), so
        // splitSnapshot's own .url already covers "blank/absent -> null" — no separate fallback
        // to edit.snapshotUrl needed.
        val cleanSnapshot = splitSnapshot?.url

        val username = edit.username?.takeIf { it.isNotBlank() } ?: splitRtsp.username ?: splitSnapshot?.username
        val password = edit.password?.takeIf { it.isNotBlank() } ?: splitRtsp.password ?: splitSnapshot?.password

        val id = edit.id ?: freshId(existing)
        val existingRecord = existing.firstOrNull { it.id == id }
        val position = existingRecord?.position ?: existing.size

        val record = CameraRecord(
            id = id,
            name = edit.name,
            rtspUrl = cleanRtsp,
            snapshotUrl = cleanSnapshot,
            audioEnabled = edit.audioEnabled,
            forceTcp = edit.forceTcp,
            position = position,
        )
        val cameras = if (existingRecord != null) {
            existing.map { if (it.id == id) record else it }
        } else {
            existing + record
        }
        val credentials = if (username != null || password != null) {
            CameraCredentialWrite(id, username, password)
        } else {
            null
        }
        return CameraEditResult(cameras, id, credentials)
    }

    /** Swaps [id]'s position with its neighbor ([up] = the previous position, else the next);
     *  a no-op at either end of the list or for an unknown [id]. */
    fun move(cameras: List<CameraRecord>, id: String, up: Boolean): List<CameraRecord> {
        val sorted = cameras.sortedBy { it.position }
        val idx = sorted.indexOfFirst { it.id == id }
        if (idx < 0) return cameras
        val swapIdx = if (up) idx - 1 else idx + 1
        if (swapIdx < 0 || swapIdx >= sorted.size) return cameras
        val a = sorted[idx]
        val b = sorted[swapIdx]
        val aMoved = a.copy(position = b.position)
        val bMoved = b.copy(position = a.position)
        return cameras.map {
            when (it.id) {
                a.id -> aMoved
                b.id -> bMoved
                else -> it
            }
        }
    }

    /** Removes [id] and renumbers the remaining cameras' positions contiguously from 0. */
    fun delete(cameras: List<CameraRecord>, id: String): List<CameraRecord> =
        cameras.filter { it.id != id }
            .sortedBy { it.position }
            .mapIndexed { index, cam -> cam.copy(position = index) }

    /** "cam_" + 8 lowercase hex chars, regenerated until it misses every id already in [existing]
     *  (collision is astronomically unlikely with 32 random bits, but a settings screen is cheap
     *  enough that guarding it costs nothing). */
    private fun freshId(existing: List<CameraRecord>): String {
        val known = existing.mapTo(HashSet()) { it.id }
        while (true) {
            val bytes = ByteArray(4)
            SecureRandom().nextBytes(bytes)
            val id = "cam_" + bytes.joinToString("") { "%02x".format(it) }
            if (id !in known) return id
        }
    }
}

// =====================================================================================
// Panel — camera CRUD UI, ONVIF discovery, behavior prefs.
// =====================================================================================

/** Behavior prefs this panel writes; [CameraFragment] consumes them (its `prefsListener`, plus
 *  `refreshCameraList`/`rebuildSnapshotPipeline`) — see that class's doc comments for how a
 *  0-second ("Off") refresh interval is handled without ever being passed to
 *  [SnapshotScheduler]'s constructor. */
object CameraBehaviorPrefs {
    const val KEY_GRID_REFRESH_S = "camera_grid_refresh_s"
    const val KEY_FRAME_GRAB_ENABLED = "camera_frame_grab_enabled"
    const val DEFAULT_GRID_REFRESH_S = 10
    const val DEFAULT_FRAME_GRAB_ENABLED = true

    /** Cycle order for the refresh-interval pill: 0 means "off". */
    val REFRESH_STEPS_S = listOf(0, 5, 10, 30, 60)

    fun label(seconds: Int): String = if (seconds <= 0) "Off" else "${seconds}s"
}

/**
 * Binder for the Camera settings tab: a CRUD list of configured cameras, an ONVIF discovery
 * scanner that pre-fills the add dialog, and the grid-refresh / frame-grab behavior prefs.
 *
 * Follows [SlideshowSettingsPanel]'s three-collapsible-section shape (here: Cameras / Discovery /
 * Behavior) and [RemoteControlSettingsPanel]'s dialog-card idiom for the edit form.
 *
 * The Cameras list is rendered by hand into a plain [LinearLayout] (like every other list in this
 * app's settings — see the Immich filter rows) rather than a RecyclerView: camera counts are small
 * (a handful of RTSP cameras, not hundreds) and full rebuilds keep the D-pad focus/measure story
 * simple.
 *
 * Status dots on each row are STATIC (last-known-reachability is not available from settings —
 * [SnapshotScheduler]'s tile state lives inside the running [CameraFragment], which this panel has
 * no reference to) — see the task-12 report.
 */
class CameraSettingsPanel(private val ctx: SettingsPanelContext) : SettingsPanelProvider {

    override val layoutRes: Int = R.layout.settings_panel_camera

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secrets = SecretStore.of(activity)

        val camerasSection = CollapsibleSection(
            panel.findViewById(R.id.headCamCameras), panel.findViewById(R.id.bodyCamCameras),
            "Cameras", startExpanded = true,
        )
        val discoverySection = CollapsibleSection(
            panel.findViewById(R.id.headCamDiscovery), panel.findViewById(R.id.bodyCamDiscovery),
            "Discovery", startExpanded = false,
        )
        val behaviorSection = CollapsibleSection(
            panel.findViewById(R.id.headCamBehavior), panel.findViewById(R.id.bodyCamBehavior),
            "Behavior", startExpanded = false,
        )

        val cameraListContainer = panel.findViewById<LinearLayout>(R.id.camCameraList)
        val cameraListEmpty = panel.findViewById<TextView>(R.id.tvCamCameraListEmpty)
        val addCameraRow = panel.findViewById<View>(R.id.rowCamAddCamera)

        var cameras: List<CameraRecord> = CameraStore.load(prefs)

        fun secretsFor(cameraId: String): Pair<String?, String?> {
            val (userKey, passKey) = CameraStore.credentialKeys(cameraId)
            return secrets.get(userKey) to secrets.get(passKey)
        }

        // [isEdit] = true clears a field the dialog prefilled and the user then emptied (see
        // CameraCredentialWrite's doc comment) — never for a brand-new camera, which has no stored
        // secret yet to clear.
        fun persistCredentials(result: CameraEditResult, isEdit: Boolean) {
            val (userKey, passKey) = CameraStore.credentialKeys(result.cameraId)
            val write = result.credentials
            if (write?.username != null) secrets.put(userKey, write.username) else if (isEdit) secrets.remove(userKey)
            if (write?.password != null) secrets.put(passKey, write.password) else if (isEdit) secrets.remove(passKey)
        }

        fun scrubCredentials(cameraId: String) {
            val (userKey, passKey) = CameraStore.credentialKeys(cameraId)
            secrets.remove(userKey)
            secrets.remove(passKey)
        }

        fun renderCameraList() {
            cameraListContainer.removeAllViews()
            val sorted = cameras.sortedBy { it.position }
            cameraListEmpty.isVisible = sorted.isEmpty()
            camerasSection.setSummary(
                SectionSummary(
                    text = if (sorted.isEmpty()) "None configured" else "${sorted.size} configured",
                    active = sorted.isNotEmpty(),
                ),
            )
            sorted.forEach { cam ->
                val row = activity.layoutInflater.inflate(R.layout.view_camera_row, cameraListContainer, false)
                row.findViewById<TextView>(R.id.tvCamRowName).text = cam.name
                row.findViewById<TextView>(R.id.tvCamRowSubtitle).text = CameraUri.redact(cam.rtspUrl)
                row.findViewById<View>(R.id.dotCamRowStatus).backgroundTintList =
                    ContextCompat.getColorStateList(activity, R.color.dot_grey)
                row.setOnClickListener {
                    openEditDialog(
                        existingCamera = cam,
                        prefill = null,
                        cameras = { cameras },
                        secretsFor = ::secretsFor,
                        onSaved = { result ->
                            cameras = result.cameras
                            persistCredentials(result, isEdit = true)
                            CameraStore.save(prefs, cameras)
                            renderCameraList()
                        },
                        onDeleted = { id ->
                            cameras = CameraSettingsModel.delete(cameras, id)
                            scrubCredentials(id)
                            CameraStore.save(prefs, cameras)
                            renderCameraList()
                        },
                    )
                }
                cameraListContainer.addView(row)
            }
        }
        renderCameraList()

        addCameraRow.setOnClickListener {
            openEditDialog(
                existingCamera = null,
                prefill = null,
                cameras = { cameras },
                secretsFor = ::secretsFor,
                onSaved = { result ->
                    cameras = result.cameras
                    persistCredentials(result, isEdit = false)
                    CameraStore.save(prefs, cameras)
                    renderCameraList()
                },
                onDeleted = {},
            )
        }

        // ---- Discovery ------------------------------------------------------------------------

        val scanButton = panel.findViewById<MaterialButton>(R.id.btnCamScan)
        val scanSpinner = panel.findViewById<ProgressBar>(R.id.camScanSpinner)
        val discoveryResults = panel.findViewById<LinearLayout>(R.id.camDiscoveryResults)
        val discoveryEmpty = panel.findViewById<TextView>(R.id.tvCamDiscoveryEmpty)

        // The empty line has to say two different things: "you haven't scanned" before the first
        // scan, and "the scan came back with nothing" after one. Without the distinction a finished
        // scan that found no cameras looks exactly like a scan that never ran.
        var hasScanned = false

        fun renderDiscoveryResults(results: List<DiscoveredCamera>, localAddress: Pair<String, Int>) {
            discoveryResults.removeAllViews()
            discoveryEmpty.isVisible = results.isEmpty()
            discoveryEmpty.text = if (hasScanned) {
                "No ONVIF cameras responded on this network."
            } else {
                "No cameras found yet. Tap Scan."
            }
            for (found in results) {
                val xaddr = OnvifDiscoveryProtocol.pickXAddr(found.xaddrs, localAddress.first, localAddress.second)
                val row = activity.layoutInflater.inflate(R.layout.view_camera_discovery_row, discoveryResults, false)
                row.findViewById<TextView>(R.id.tvCamDiscRowName).text = found.name ?: found.hardware ?: found.endpointUuid
                row.findViewById<TextView>(R.id.tvCamDiscRowSubtitle).text = xaddr ?: found.xaddrs.firstOrNull().orEmpty()
                val alreadyAdded = xaddr != null && cameras.any { hostOf(it.rtspUrl) == hostOf(xaddr) }
                val actionable = !alreadyAdded && xaddr != null
                val addedLabel = row.findViewById<TextView>(R.id.tvCamDiscRowAdded)
                addedLabel.isVisible = alreadyAdded
                row.isEnabled = actionable
                // A disabled View is not automatically pulled out of D-pad focus order — set both
                // explicitly so an already-added / unresolvable row is never a dead focus stop.
                row.isFocusable = actionable
                row.isClickable = actionable
                row.alpha = if (actionable) 1f else 0.5f
                if (actionable) {
                    row.setOnClickListener {
                        openDiscoveryCredentialsDialog(found.name) { user, pass ->
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    OnvifClient(AndroidSoapTransport(), nonceSource = ::randomNonce)
                                        .resolve(xaddr, user, pass)
                                }
                                when (result) {
                                    is OnvifResult.Resolved -> openEditDialog(
                                        existingCamera = null,
                                        prefill = DiscoveryPrefill(
                                            name = found.name ?: found.hardware ?: "Camera",
                                            rtspUrl = result.streamUri,
                                            snapshotUrl = result.snapshotUri,
                                            username = user,
                                            password = pass,
                                        ),
                                        cameras = { cameras },
                                        secretsFor = ::secretsFor,
                                        onSaved = { r ->
                                            cameras = r.cameras
                                            persistCredentials(r, isEdit = false)
                                            CameraStore.save(prefs, cameras)
                                            renderCameraList()
                                        },
                                        onDeleted = {},
                                    )
                                    is OnvifResult.Failed -> showFeedback(
                                        panel.findViewById(R.id.tvCamDiscoveryFeedback),
                                        "Couldn't resolve the stream (${result.step}): ${result.detail}",
                                        HaFeedbackKind.ERROR,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    row.setOnClickListener(null)
                }
                discoveryResults.addView(row)
            }
        }

        scanButton.setOnClickListener {
            scanButton.isEnabled = false
            scanSpinner.isVisible = true
            discoveryResults.removeAllViews()
            discoveryEmpty.isVisible = false
            scope.launch {
                // Fresh io/discovery per scan — AndroidDiscoveryIo's socket dies on close().
                val (found, localAddress) = withContext(Dispatchers.IO) {
                    val io = AndroidDiscoveryIo(activity)
                    val results = runCatching { OnvifDiscovery(io).scan() }.getOrElse { emptyList() }
                    results to io.localAddress()
                }
                scanButton.isEnabled = true
                scanSpinner.isVisible = false
                hasScanned = true
                renderDiscoveryResults(found, localAddress)
            }
        }

        // ---- Behavior ---------------------------------------------------------------------------

        val refreshPill = panel.findViewById<MaterialButton>(R.id.btnCamRefreshInterval)
        val frameGrabSwitch = panel.findViewById<SwitchMaterial>(R.id.switchCamFrameGrab)

        var refreshSeconds = prefs.getInt(CameraBehaviorPrefs.KEY_GRID_REFRESH_S, CameraBehaviorPrefs.DEFAULT_GRID_REFRESH_S)
        fun renderRefreshPill() {
            refreshPill.text = CameraBehaviorPrefs.label(refreshSeconds)
            // Kept live so the collapsed section summary never shows a stale value after cycling.
            behaviorSection.setSummary(SectionSummary(CameraBehaviorPrefs.label(refreshSeconds), active = refreshSeconds > 0))
        }
        renderRefreshPill()
        refreshPill.setOnClickListener {
            val steps = CameraBehaviorPrefs.REFRESH_STEPS_S
            val idx = steps.indexOf(refreshSeconds).let { if (it < 0) 0 else it }
            refreshSeconds = steps[(idx + 1) % steps.size]
            prefs.edit().putInt(CameraBehaviorPrefs.KEY_GRID_REFRESH_S, refreshSeconds).apply()
            renderRefreshPill()
        }

        frameGrabSwitch.isChecked = prefs.getBoolean(
            CameraBehaviorPrefs.KEY_FRAME_GRAB_ENABLED,
            CameraBehaviorPrefs.DEFAULT_FRAME_GRAB_ENABLED,
        )
        frameGrabSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(CameraBehaviorPrefs.KEY_FRAME_GRAB_ENABLED, isChecked).apply()
        }

        // Referenced only to keep the section non-empty lint-clean; it remains collapsible.
        discoverySection.setSummary(SectionSummary("ONVIF scan", active = false))

        return { scope.cancel() }
    }

    // ---- Edit dialog ----------------------------------------------------------------------------

    private data class DiscoveryPrefill(
        val name: String,
        val rtspUrl: String,
        val snapshotUrl: String?,
        val username: String?,
        val password: String?,
    )

    private fun openEditDialog(
        existingCamera: CameraRecord?,
        prefill: DiscoveryPrefill?,
        cameras: () -> List<CameraRecord>,
        secretsFor: (String) -> Pair<String?, String?>,
        onSaved: (CameraEditResult) -> Unit,
        onDeleted: (String) -> Unit,
    ) {
        val activity = ctx.activity
        val root = activity.layoutInflater.inflate(R.layout.dialog_camera_edit, null)
        val card = Dialog(activity)
        card.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.setContentView(root)
        card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        card.followDisplaySize(activity)

        val title = root.findViewById<TextView>(R.id.tvCamEditTitle)
        val nameField = root.findViewById<EditText>(R.id.etCamEditName)
        val rtspField = root.findViewById<EditText>(R.id.etCamEditRtsp)
        val snapshotField = root.findViewById<EditText>(R.id.etCamEditSnapshot)
        val userField = root.findViewById<EditText>(R.id.etCamEditUser)
        val passField = root.findViewById<EditText>(R.id.etCamEditPass)
        val audioSwitch = root.findViewById<SwitchMaterial>(R.id.switchCamEditAudio)
        val forceTcpSwitch = root.findViewById<SwitchMaterial>(R.id.switchCamEditForceTcp)
        val moveUpButton = root.findViewById<MaterialButton>(R.id.btnCamEditMoveUp)
        val moveDownButton = root.findViewById<MaterialButton>(R.id.btnCamEditMoveDown)
        val testButton = root.findViewById<MaterialButton>(R.id.btnCamEditTest)
        val testResult = root.findViewById<TextView>(R.id.tvCamEditTestResult)
        val deleteButton = root.findViewById<MaterialButton>(R.id.btnCamEditDelete)
        val cancelButton = root.findViewById<MaterialButton>(R.id.btnCamEditCancel)
        val saveButton = root.findViewById<MaterialButton>(R.id.btnCamEditSave)
        val error = root.findViewById<TextView>(R.id.tvCamEditError)

        title.text = if (existingCamera == null) "Add camera" else "Edit camera"
        deleteButton.isVisible = existingCamera != null
        val movable = existingCamera != null
        moveUpButton.isVisible = movable
        moveDownButton.isVisible = movable

        nameField.setText(existingCamera?.name ?: prefill?.name.orEmpty())
        rtspField.setText(existingCamera?.rtspUrl ?: prefill?.rtspUrl.orEmpty())
        snapshotField.setText(existingCamera?.snapshotUrl ?: prefill?.snapshotUrl.orEmpty())
        audioSwitch.isChecked = existingCamera?.audioEnabled ?: false
        forceTcpSwitch.isChecked = existingCamera?.forceTcp ?: true

        if (existingCamera != null) {
            val (user, pass) = secretsFor(existingCamera.id)
            userField.setText(user.orEmpty())
            passField.setText(pass.orEmpty())
        } else {
            userField.setText(prefill?.username.orEmpty())
            passField.setText(prefill?.password.orEmpty())
        }

        fun currentEdit() = CameraEdit(
            id = existingCamera?.id,
            name = nameField.text?.toString()?.trim().orEmpty(),
            rtspUrl = rtspField.text?.toString()?.trim().orEmpty(),
            snapshotUrl = snapshotField.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() },
            username = userField.text?.toString(),
            password = passField.text?.toString(),
            audioEnabled = audioSwitch.isChecked,
            forceTcp = forceTcpSwitch.isChecked,
        )

        // Position ▲/▼ is staged, not committed: reordering only ever touches this local snapshot
        // of the list, and only reaches CameraStore.save via Save's applyEdit call below — a
        // ▲ then Cancel must leave the persisted order untouched, exactly like every other field
        // in this dialog.
        var stagedCameras = cameras()
        moveUpButton.setOnClickListener {
            existingCamera?.let { stagedCameras = CameraSettingsModel.move(stagedCameras, it.id, true) }
        }
        moveDownButton.setOnClickListener {
            existingCamera?.let { stagedCameras = CameraSettingsModel.move(stagedCameras, it.id, false) }
        }

        testButton.setOnClickListener {
            val edit = currentEdit()
            if (edit.rtspUrl.isBlank()) {
                error.text = "Enter an RTSP URL first."
                error.isVisible = true
                return@setOnClickListener
            }
            error.isVisible = false
            testButton.isEnabled = false
            testResult.isVisible = true
            testResult.text = "Testing…"
            // Mark this camera busy in CameraTestRegistry for the probe's duration — the mounted
            // CameraFragment's scheduler (if any) consults it and skips this camera's frame-grab
            // turn, so a Test never races the grid for the same RTSP session (spec: Test /
            // frame-grab / live view never target one camera concurrently). Only an *existing*
            // camera has a scheduler entry to race in the first place; a new, unsaved camera has
            // none yet, so there is nothing to mark.
            val testCameraId = existingCamera?.id
            testCameraId?.let(CameraTestRegistry::markTesting)
            scope.launch {
                try {
                    val split = CameraCodec.splitInlineCredentials(edit.rtspUrl)
                    val user = edit.username?.takeIf { it.isNotBlank() } ?: split.username
                    val pass = edit.password?.takeIf { it.isNotBlank() } ?: split.password
                    val rtspUri = CameraUri.withCredentials(split.url, user, pass)

                    val rtspOk = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                        probeRtsp(activity, rtspUri, edit.forceTcp)
                    } == true

                    val snapshotResult = edit.snapshotUrl?.takeIf { it.isNotBlank() }?.let { rawSnapshot ->
                        val snapSplit = CameraCodec.splitInlineCredentials(rawSnapshot)
                        val snapUser = user ?: snapSplit.username
                        val snapPass = pass ?: snapSplit.password
                        withTimeoutOrNull(TEST_TIMEOUT_MS) {
                            AndroidSnapshotIo(activity).fetchHttp(snapSplit.url, snapUser, snapPass)
                        } != null
                    }

                    testButton.isEnabled = true
                    val lines = mutableListOf("RTSP: ${if (rtspOk) "✓" else "✗"}")
                    if (snapshotResult != null) lines += "Snapshot: ${if (snapshotResult) "✓" else "✗"}"
                    testResult.text = lines.joinToString("\n")
                } finally {
                    testCameraId?.let(CameraTestRegistry::clearTesting)
                }
            }
        }

        deleteButton.setOnClickListener {
            existingCamera?.let { onDeleted(it.id) }
            card.dismiss()
        }

        cancelButton.setOnClickListener { card.dismiss() }

        saveButton.setOnClickListener {
            val edit = currentEdit()
            if (edit.name.isBlank() || edit.rtspUrl.isBlank()) {
                error.text = "Name and RTSP URL are required."
                error.isVisible = true
                return@setOnClickListener
            }
            error.isVisible = false
            // Apply on top of stagedCameras (not cameras()) so any ▲/▼ clicked in this session is
            // committed together with the field edits, in one save.
            val result = CameraSettingsModel.applyEdit(stagedCameras, edit)
            onSaved(result)
            card.dismiss()
        }

        card.show()
        nameField.requestFocus()
    }

    /** A short headless RTSP probe: builds a throwaway main-looper ExoPlayer against
     *  [RtspMediaSource][androidx.media3.exoplayer.rtsp.RtspMediaSource] and reports true the
     *  moment it reaches `STATE_READY`, false on any player error. Mirrors
     *  [CameraPlaybackPlan][dev.rusty.app.CameraPlaybackPlan]'s success/failure semantics without
     *  reusing its reconnect state machine — a Test button wants one shot, not a retry ladder.
     *  Always released in `finally`. */
    private suspend fun probeRtsp(context: Context, rtspUriWithCreds: String, forceTcp: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            RtspProbe.run(context, rtspUriWithCreds, forceTcp)
        }

    private fun openDiscoveryCredentialsDialog(
        cameraName: String?,
        onSubmit: (username: String, password: String) -> Unit,
    ) {
        val activity = ctx.activity
        val root = activity.layoutInflater.inflate(R.layout.dialog_camera_credentials, null)
        val card = Dialog(activity)
        card.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.setContentView(root)
        card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        card.followDisplaySize(activity)

        root.findViewById<TextView>(R.id.tvCamCredTitle).text = cameraName ?: "Camera credentials"
        val userField = root.findViewById<EditText>(R.id.etCamCredUser)
        val passField = root.findViewById<EditText>(R.id.etCamCredPass)

        root.findViewById<MaterialButton>(R.id.btnCamCredCancel).setOnClickListener { card.dismiss() }
        root.findViewById<MaterialButton>(R.id.btnCamCredSave).setOnClickListener {
            val user = userField.text?.toString().orEmpty()
            val pass = passField.text?.toString().orEmpty()
            card.dismiss()
            onSubmit(user, pass)
        }
        card.show()
        userField.requestFocus()
    }

    private fun randomNonce(): ByteArray {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /** Bare host (no port, no userinfo) out of an rtsp/http(s) URL, for the discovery
     *  already-added match. Best-effort string parsing, same idiom as [OnvifDiscoveryProtocol]. */
    private fun hostOf(url: String): String? {
        val withoutScheme = url.substringAfter("://", missingDelimiterValue = "")
        if (withoutScheme.isEmpty()) return null
        val authority = withoutScheme.substringBefore('/').substringAfterLast('@')
        return authority.substringBefore(':').lowercase()
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
        const val TEST_TIMEOUT_MS = 8_000L
    }
}

/** Isolated so [CameraSettingsPanel] stays free of ExoPlayer wiring in its main body. Not unit
 *  tested — thin Android I/O, same posture as [AndroidSnapshotIo.grabFrame]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private object RtspProbe {
    suspend fun run(context: Context, uri: String, forceTcp: Boolean): Boolean {
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(ok: Boolean) {
                    if (done.compareAndSet(false, true) && cont.isActive) {
                        cont.resumeWith(Result.success(ok))
                    }
                }
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_READY) finish(true)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        finish(false)
                    }
                })
                val source = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                player.setMediaSource(source)
                player.prepare()
                cont.invokeOnCancellation { finish(false) }
            }
        } finally {
            player.release()
        }
    }
}
