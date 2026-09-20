package dev.rusty.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
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
    val mainRtspUrl: String?,
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
        val splitMain = edit.mainRtspUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { CameraCodec.splitInlineCredentials(it) }
        val cleanMain = splitMain?.url
        // splitSnapshot is non-null exactly when edit.snapshotUrl is non-blank (see above), so
        // splitSnapshot's own .url already covers "blank/absent -> null" — no separate fallback
        // to edit.snapshotUrl needed.
        val cleanSnapshot = splitSnapshot?.url

        val username = edit.username?.takeIf { it.isNotBlank() } ?: splitRtsp.username ?: splitMain?.username ?: splitSnapshot?.username
        val password = edit.password?.takeIf { it.isNotBlank() } ?: splitRtsp.password ?: splitMain?.password ?: splitSnapshot?.password

        val id = edit.id ?: freshId(existing)
        val existingRecord = existing.firstOrNull { it.id == id }
        val position = existingRecord?.position ?: existing.size

        val record = CameraRecord(
            id = id,
            name = edit.name,
            rtspUrl = cleanRtsp,
            mainRtspUrl = cleanMain,
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

/** Grid prefs this panel writes; [CameraFragment] consumes them (its `prefsListener`, plus
 *  `refreshCameraList`/`rebuildSnapshotPipeline`) — see that class's doc comments for how a
 *  0-second ("Off") refresh interval is handled without ever being passed to
 *  [SnapshotScheduler]'s constructor. */
/** How the grid lays cameras out: every camera on one screen, or fixed-size pages. */
enum class GridLayoutChoice(val prefValue: String, val pageSize: Int?) {
    ALL("all", null),
    PAGES_4("4", 4),
    PAGES_6("6", 6),
    PAGES_8("8", 8),
}

object CameraBehaviorPrefs {
    const val KEY_GRID_REFRESH_S = "camera_grid_refresh_s"
    const val KEY_GRID_LAYOUT = "camera_grid_layout"
    const val KEY_PAGE_ROTATION_S = "camera_page_rotation_s"
    const val DEFAULT_GRID_REFRESH_S = 30

    /**
     * Slider stops for the refresh interval; index = slider value. 0 means "off".
     *
     * The floor is 30 s on purpose. The scheduler runs one job at a time and a frame grab costs
     * 2.5–8 s of RTSP work (handshake, then a wait for the camera's next keyframe) before the
     * device's own decode; four grab-only cameras need 16–30 s per round, so anything below 30 s
     * keeps the decoder busy around the clock and only *looks* faster on the slider.
     */
    val REFRESH_STEPS_S = listOf(0, 30, 60, 120, 300, 600)

    /** Slider stops for page rotation; index = slider value. 0 means "off". */
    val ROTATION_STEPS_S = listOf(0, 10, 30, 60)

    fun refreshIndex(seconds: Int): Int =
        REFRESH_STEPS_S.indexOf(seconds).takeIf { it >= 0 } ?: REFRESH_STEPS_S.indexOf(DEFAULT_GRID_REFRESH_S)

    fun refreshSecondsAt(index: Int): Int = REFRESH_STEPS_S[index.coerceIn(0, REFRESH_STEPS_S.lastIndex)]

    /** Snaps a stored value onto the current stops: "off" stays off, anything else rounds UP to the
     *  next stop (a 10 s value written by an earlier build becomes 30 s), past the top clamps. */
    fun snapRefresh(seconds: Int): Int = when {
        seconds <= 0 -> 0
        else -> REFRESH_STEPS_S.firstOrNull { it >= seconds } ?: REFRESH_STEPS_S.last()
    }

    /** The effective refresh interval: the stored pref, snapped (see [snapRefresh]). */
    fun refreshSeconds(prefs: android.content.SharedPreferences): Int =
        snapRefresh(prefs.getInt(KEY_GRID_REFRESH_S, DEFAULT_GRID_REFRESH_S))

    fun label(seconds: Int): String = when {
        seconds <= 0 -> "Off"
        seconds % 60 == 0 -> "${seconds / 60} min"
        else -> "$seconds s"
    }

    /** [label] as a cadence: "Every 30 s", "Every 2 min", or "Off". */
    fun everyLabel(seconds: Int): String = if (seconds <= 0) "Off" else "Every ${label(seconds)}"

    fun rotationIndex(seconds: Int): Int = ROTATION_STEPS_S.indexOf(seconds).takeIf { it >= 0 } ?: 0

    fun rotationSecondsAt(index: Int): Int = ROTATION_STEPS_S[index.coerceIn(0, ROTATION_STEPS_S.lastIndex)]

    fun rotationLabel(seconds: Int): String = label(seconds)

    fun gridLayoutFrom(pref: String?): GridLayoutChoice =
        GridLayoutChoice.entries.firstOrNull { it.prefValue == pref } ?: GridLayoutChoice.ALL
}

/**
 * Binder for the Camera settings tab: a CRUD list of configured cameras (with a reorder mode,
 * [CameraReorderMode]), the "Add a camera" chooser ([CameraAddCard], where ONVIF discovery now
 * lives), and the Grid section (layout, page rotation, refresh).
 *
 * Follows [SlideshowSettingsPanel]'s collapsible-section shape (here: Cameras / Behavior) and [RemoteControlSettingsPanel]'s dialog-card idiom for the edit form.
 *
 * The Cameras list is rendered by hand into a plain [LinearLayout] (like every other list in this
 * app's settings — see the Immich filter rows) rather than a RecyclerView: camera counts are small
 * (a handful of RTSP cameras, not hundreds) and full rebuilds keep the D-pad focus/measure story
 * simple.
 *
 * Status dots on each row are LIVE: [CameraFragment]'s grid tick publishes reachability to
 * [CameraStatusRelay], and this panel subscribes while mounted, repainting rows in place (never a
 * full [renderCameraList] rebuild, which would steal D-pad focus) plus a once-a-second ticker so
 * "Offline for N min" keeps advancing on its own.
 */
class CameraSettingsPanel(private val ctx: SettingsPanelContext) : SettingsPanelProvider {

    override val layoutRes: Int = R.layout.settings_panel_camera

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Null until bind() wires it; renderCameraList runs once before that. */
    private var reorder: CameraReorderMode? = null

    override fun bind(panel: View): () -> Unit {
        val activity = ctx.activity
        val prefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val secrets = SecretStore.of(activity)

        val camerasSection = CollapsibleSection(
            panel.findViewById(R.id.headCamCameras), panel.findViewById(R.id.bodyCamCameras),
            "Cameras", startExpanded = false,
        )
        val behaviorSection = CollapsibleSection(
            panel.findViewById(R.id.headCamBehavior), panel.findViewById(R.id.bodyCamBehavior),
            "Grid", startExpanded = false,
        )
        val shareSection = CollapsibleSection(
            panel.findViewById(R.id.headCamShare), panel.findViewById(R.id.bodyCamShare),
            "Share this camera", startExpanded = false,
        )

        val cameraListContainer = panel.findViewById<LinearLayout>(R.id.camCameraList)
        val cameraListEmpty = panel.findViewById<TextView>(R.id.tvCamCameraListEmpty)
        val addCameraRow = panel.findViewById<View>(R.id.btnCamAdd)

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

        val rowViews = HashMap<String, View>()
        // Reorder only means something with two or more cameras; bound here, before the list
        // renderer that shows and hides it.
        val reorderRow = panel.findViewById<View>(R.id.btnCamReorder)

        fun paintRow(row: View, cam: CameraRecord, status: CameraStatus?) {
            val dot = row.findViewById<View>(R.id.dotCamRowStatus)
            val subtitle = row.findViewById<TextView>(R.id.tvCamRowSubtitle)
            val streams = if (cam.mainRtspUrl.isNullOrBlank()) "stream" else "main + sub stream"
            val snapshot = if (cam.snapshotUrl.isNullOrBlank()) "" else " · snapshot URL"
            val now = android.os.SystemClock.elapsedRealtime()
            val (color, text) = when (status?.state) {
                TileState.OK -> R.color.dot_green to "Online · $streams$snapshot"
                TileState.STALE -> R.color.dot_amber to "Updating…"
                TileState.UNREACHABLE -> R.color.dot_red to activity.getString(
                    R.string.camera_tile_offline_for, OfflineText.duration(now - (status.offlineSinceMs ?: now)),
                )
                TileState.NONE, null -> R.color.dot_grey to "Not checked"
            }
            dot.backgroundTintList = ContextCompat.getColorStateList(activity, color)
            subtitle.text = text
            subtitle.setTextColor(ContextCompat.getColor(activity, if (status?.state == TileState.UNREACHABLE) R.color.dot_red else R.color.muted_dim))
        }

        fun renderSummary() {
            // The ticker repaints this every second; while reordering the mode wins over the count.
            if (reorder?.active == true) {
                camerasSection.setSummary(SectionSummary("Reordering", active = true))
                return
            }
            val sorted = cameras.sortedBy { it.position }
            val statuses = CameraStatusRelay.current()
            val offline = sorted.count { statuses[it.id]?.state == TileState.UNREACHABLE }
            val text = when {
                sorted.isEmpty() -> "No cameras"
                offline > 0 -> "${sorted.size} camera${if (sorted.size == 1) "" else "s"} · $offline offline"
                else -> "${sorted.size} camera${if (sorted.size == 1) "" else "s"}"
            }
            camerasSection.setSummary(SectionSummary(text, active = sorted.isNotEmpty() && sorted.all { statuses[it.id]?.state == TileState.OK }))
        }

        fun repaintRows() {
            val statuses = CameraStatusRelay.current()
            for (cam in cameras) rowViews[cam.id]?.let { paintRow(it, cam, statuses[cam.id]) }
            renderSummary()
        }

        fun renderCameraList() {
            reorder?.let { if (it.active) { it.render(); renderSummary(); return } }
            cameraListContainer.removeAllViews()
            rowViews.clear()
            val sorted = cameras.sortedBy { it.position }
            cameraListEmpty.isVisible = sorted.isEmpty()
            reorderRow.isVisible = sorted.size >= 2
            val statuses = CameraStatusRelay.current()
            sorted.forEach { cam ->
                val row = activity.layoutInflater.inflate(R.layout.view_camera_row, cameraListContainer, false)
                row.findViewById<TextView>(R.id.tvCamRowName).text = cam.name
                paintRow(row, cam, statuses[cam.id])
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
                rowViews[cam.id] = row
                cameraListContainer.addView(row)
            }
            renderSummary()
        }
        renderCameraList()

        val statusListener: () -> Unit = { repaintRows() }
        CameraStatusRelay.addListener(statusListener)
        // "Offline for N min" moves on its own; a once-a-second repaint keeps it honest while the
        // sheet is open. Cheap: a handful of rows, text only.
        val rowTicker = android.os.Handler(android.os.Looper.getMainLooper())
        val rowTick = object : Runnable {
            override fun run() { repaintRows(); rowTicker.postDelayed(this, 1_000L) }
        }
        rowTicker.postDelayed(rowTick, 1_000L)

        val actionBar = panel.findViewById<View>(R.id.camActionBar)
        val reorderStrip = panel.findViewById<View>(R.id.camReorderStrip)
        val reorderDoneRow = panel.findViewById<View>(R.id.btnCamReorderDone)

        fun openAddForm(prefill: DiscoveryPrefill?) {
            openEditDialog(
                existingCamera = null,
                prefill = prefill,
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
        addCameraRow.setOnClickListener {
            CameraAddCard(
                activity = activity,
                scope = scope,
                cameras = { cameras },
                // The scan also lists other Rusty devices sharing a camera; this device's own
                // advertisement is filtered out by its Remote Control identity.
                ownDeviceId = ControlSettings.deviceId(prefs),
                askCredentials = ::openDiscoveryCredentialsDialog,
                onManual = { openAddForm(null) },
                onResolved = { openAddForm(it) },
            ).show()
        }

        // ---- Reorder mode -----------------------------------------------------------------------
        // The list re-renders as reorder rows (view_camera_reorder_row.xml); every move is saved at
        // once so the wall follows along. rowViews is left EMPTY in this mode: the status ticker
        // repaints through it, and a reorder row has no status dot to paint.
        reorderRow.setOnClickListener { reorder?.enter() }
        reorderDoneRow.setOnClickListener { reorder?.exit() }
        reorder = CameraReorderMode(
            activity = activity,
            container = cameraListContainer,
            doneButton = reorderDoneRow,
            cameras = { cameras },
            pageSize = { CameraBehaviorPrefs.gridLayoutFrom(prefs.getString(CameraBehaviorPrefs.KEY_GRID_LAYOUT, null)).pageSize },
            onMoved = { moved ->
                cameras = moved
                CameraStore.save(prefs, cameras)
                renderSummary()
            },
            onModeChanged = { active ->
                actionBar.isVisible = !active
                reorderStrip.isVisible = active
                if (active) { rowViews.clear(); renderSummary() } else renderCameraList()
            },
        )

        // ---- Grid -------------------------------------------------------------------------------

        val refreshSlider = panel.findViewById<Slider>(R.id.sliderCamRefresh)
        val refreshValue = panel.findViewById<TextView>(R.id.tvCamRefreshValue)
        val rotationRow = panel.findViewById<View>(R.id.rowCamPageRotation)
        val rotationSlider = panel.findViewById<Slider>(R.id.sliderCamPageRotation)
        val rotationValue = panel.findViewById<TextView>(R.id.tvCamPageRotationValue)

        var refreshSeconds = CameraBehaviorPrefs.refreshSeconds(prefs)
        var layout = CameraBehaviorPrefs.gridLayoutFrom(prefs.getString(CameraBehaviorPrefs.KEY_GRID_LAYOUT, null))
        var rotationSeconds = prefs.getInt(CameraBehaviorPrefs.KEY_PAGE_ROTATION_S, 0)

        fun layoutLabel(choice: GridLayoutChoice) = when (choice) {
            GridLayoutChoice.ALL -> "All in one view"
            else -> "Pages of ${choice.pageSize}"
        }
        fun renderBehaviorSummary() {
            // Same order as the rows: layout, (rotation while paging), refresh.
            val parts = buildList {
                add(layoutLabel(layout))
                if (layout != GridLayoutChoice.ALL && rotationSeconds > 0) add("turn every ${CameraBehaviorPrefs.label(rotationSeconds)}")
                add(CameraBehaviorPrefs.everyLabel(refreshSeconds).replaceFirstChar { it.lowercase() })
            }
            behaviorSection.setSummary(SectionSummary(parts.joinToString(" · "), active = refreshSeconds > 0))
        }

        refreshSlider.value = CameraBehaviorPrefs.refreshIndex(refreshSeconds).toFloat()
        refreshValue.text = CameraBehaviorPrefs.everyLabel(refreshSeconds)
        fun commitRefresh(index: Int) {
            val seconds = CameraBehaviorPrefs.refreshSecondsAt(index)
            if (seconds == refreshSeconds) return
            refreshSeconds = seconds
            prefs.edit().putInt(CameraBehaviorPrefs.KEY_GRID_REFRESH_S, seconds).apply()
            renderBehaviorSummary()
        }
        refreshSlider.addOnChangeListener { slider, value, fromUser ->
            refreshValue.text = CameraBehaviorPrefs.everyLabel(CameraBehaviorPrefs.refreshSecondsAt(value.toInt()))
            // A D-pad step never produces a touch-up, so commit here unless a touch drag is in
            // progress (that commits on release below) — same rule as the Spotify startup volume.
            if (fromUser && !slider.isPressed) commitRefresh(value.toInt())
        }
        refreshSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = commitRefresh(slider.value.toInt())
        })

        fun renderRotationRow() {
            rotationRow.isVisible = layout != GridLayoutChoice.ALL
        }
        bindCameraRadioChoice(
            options = listOf(
                panel.findViewById<RadioButton>(R.id.rbCamLayoutAll) to GridLayoutChoice.ALL,
                panel.findViewById<RadioButton>(R.id.rbCamLayout4) to GridLayoutChoice.PAGES_4,
                panel.findViewById<RadioButton>(R.id.rbCamLayout6) to GridLayoutChoice.PAGES_6,
                panel.findViewById<RadioButton>(R.id.rbCamLayout8) to GridLayoutChoice.PAGES_8,
            ),
            selected = layout,
            onSelect = { choice ->
                layout = choice
                prefs.edit().putString(CameraBehaviorPrefs.KEY_GRID_LAYOUT, choice.prefValue).apply()
                renderRotationRow()
                renderBehaviorSummary()
            },
        )
        renderRotationRow()

        rotationSlider.value = CameraBehaviorPrefs.rotationIndex(rotationSeconds).toFloat()
        rotationValue.text = CameraBehaviorPrefs.everyLabel(rotationSeconds)
        fun commitRotation(index: Int) {
            val seconds = CameraBehaviorPrefs.rotationSecondsAt(index)
            if (seconds == rotationSeconds) return
            rotationSeconds = seconds
            prefs.edit().putInt(CameraBehaviorPrefs.KEY_PAGE_ROTATION_S, seconds).apply()
            renderBehaviorSummary()
        }
        rotationSlider.addOnChangeListener { slider, value, fromUser ->
            rotationValue.text = CameraBehaviorPrefs.everyLabel(CameraBehaviorPrefs.rotationSecondsAt(value.toInt()))
            if (fromUser && !slider.isPressed) commitRotation(value.toInt())
        }
        rotationSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = commitRotation(slider.value.toInt())
        })
        renderBehaviorSummary()

        // ---- Share this camera --------------------------------------------------------------
        // This device's own camera, offered to other Rusty devices as an RTSP stream. Every string
        // the section shows is decided by CameraShareSettingsModel; the code here only probes the
        // Android-side facts and moves the result onto views.

        val shareSwitch = panel.findViewById<SwitchMaterial>(R.id.switchCamShare)
        val shareStatus = panel.findViewById<TextView>(R.id.tvCamShareStatus)
        val shareWarning = panel.findViewById<TextView>(R.id.tvCamShareWarning)
        val shareUrlRow = panel.findViewById<View>(R.id.rowCamShareUrl)
        val shareUrl = panel.findViewById<TextView>(R.id.tvCamShareUrl)
        val lensRow = panel.findViewById<View>(R.id.rowCamShareLens)
        val lensFront = panel.findViewById<RadioButton>(R.id.rbCamShareFront)
        val lensBack = panel.findViewById<RadioButton>(R.id.rbCamShareBack)
        val encodingHint = panel.findViewById<TextView>(R.id.tvCamShareEncodingHint)
        val advice = panel.findViewById<TextView>(R.id.tvCamShareAdvice)
        val adviceDetail = panel.findViewById<TextView>(R.id.tvCamShareAdviceDetail)
        val resolutionButtons = mapOf(
            CameraShareSettings.Resolution.LOW to panel.findViewById<RadioButton>(R.id.rbCamShareLow),
            CameraShareSettings.Resolution.MEDIUM to panel.findViewById<RadioButton>(R.id.rbCamShareMedium),
            CameraShareSettings.Resolution.HIGH to panel.findViewById<RadioButton>(R.id.rbCamShareHigh),
        )
        val tierButtons = mapOf(
            CameraShareSettings.Tier.BASIC to panel.findViewById<RadioButton>(R.id.rbCamShareBasic),
            CameraShareSettings.Tier.GOOD to panel.findViewById<RadioButton>(R.id.rbCamShareGood),
            CameraShareSettings.Tier.BEST to panel.findViewById<RadioButton>(R.id.rbCamShareBest),
        )
        val fpsButtons = mapOf(
            CameraShareSettings.FrameRate.FPS_10 to panel.findViewById<RadioButton>(R.id.rbCamShareFps10),
            CameraShareSettings.FrameRate.FPS_15 to panel.findViewById<RadioButton>(R.id.rbCamShareFps15),
            CameraShareSettings.FrameRate.FPS_30 to panel.findViewById<RadioButton>(R.id.rbCamShareFps30),
        )

        // Probed once per bind: both answers enumerate system codecs / cameras, and neither can
        // change while the sheet is open.
        val lensCount = CameraCapturePipeline.lensCount(activity)
        val shareUnsupported = CameraShareSettingsModel.unsupportedReason(
            hasEncoder = CameraCapturePipeline.hasH264Encoder(),
            lensCount = lensCount,
        )
        // Also probed once per bind, not re-read on every paintShare() as it used to be. The old
        // comment there justified re-reading with "the password lives in another tab", but that is
        // not true: SettingsSheet.showPanel() runs the OUTGOING tab's cleanup before binding the
        // incoming one, so only one settings panel is ever bound at a time and the Remote Control
        // tab cannot mutate the password while this panel's paintShare closure is alive — reopening
        // this tab re-probes fresh anyway. Re-reading on every paint was also pure waste: paintShare
        // runs on every CameraShareStatus tick, i.e. every viewer attach/detach, which would
        // re-decrypt the stored password on the UI thread each time — the exact cost
        // ControlService.refreshAdvertisement's KDoc calls out for the same secret. Worse than waste:
        // requiredPassword reads an EncryptedSharedPreferences entry, and a single undecryptable one
        // throws SecurityException/GeneralSecurityException (SecretStore's own KDoc calls this "a
        // field-reported crash rather than theoretical"). paintShare is invoked from shareListener/
        // controlListener, both dispatched via a main-looper Handler.post, so an uncaught throw there
        // reaches the default handler and kills the process. runCatching keeps that failure from ever
        // escaping — the same shape as ControlService.reconcileNsd.
        val passwordSet = runCatching { ControlSettings.requiredPassword(prefs, secrets) != null }
            .getOrDefault(false)

        lensRow.isVisible = CameraShareSettingsModel.showLensPicker(lensCount)
        (if (CameraShareSettings.lens(prefs) == CameraShareSettings.Lens.BACK) lensBack else lensFront).isChecked = true
        // The RadioGroup owns exclusivity; the service watches KEY_LENS and restarts a live camera.
        lensFront.setOnClickListener { CameraShareSettings.setLens(prefs, CameraShareSettings.Lens.FRONT) }
        lensBack.setOnClickListener { CameraShareSettings.setLens(prefs, CameraShareSettings.Lens.BACK) }

        // Fixed caveat under the advice line: bound once from the constant the model test pins, so
        // the wording lives in exactly one place (the layout carries it only as a tools: preview).
        adviceDetail.text = CameraShareSettingsModel.ADVICE_DETAIL

        // Advice is repainted from every status tick (below) AND from every picker tap: the
        // encoding is an input to it. `showAdvice` is what the last paintShare decided.
        var showAdvice = false
        fun paintEncoding() {
            val e = CameraShareSettings.encoding(prefs)
            encodingHint.text = CameraShareSettingsModel.encodingHint(e)
            val a = if (showAdvice) CameraShareSettingsModel.networkAdvice(WifiLinkProbe.read(activity), e) else null
            advice.isVisible = a != null
            adviceDetail.isVisible = a != null
            if (a != null) {
                val dot = when (a.level) {
                    CameraShareSettingsModel.AdviceLevel.GOOD -> R.color.dot_green
                    CameraShareSettingsModel.AdviceLevel.WARN -> R.color.dot_amber
                    CameraShareSettingsModel.AdviceLevel.BAD -> R.color.dot_red
                }
                val text = android.text.SpannableString("\u25cf " + a.text)
                text.setSpan(
                    android.text.style.ForegroundColorSpan(ContextCompat.getColor(activity, dot)),
                    0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                advice.text = text
                advice.setTextColor(ContextCompat.getColor(activity, if (a.level == CameraShareSettingsModel.AdviceLevel.GOOD) R.color.muted else dot))
            }
        }

        // Same shape as the lens picker: each RadioGroup owns exclusivity, and the service watches
        // KEY_RESOLUTION / KEY_TIER / KEY_FPS, restarting a live camera on the background thread a
        // reopen needs.
        resolutionButtons[CameraShareSettings.resolution(prefs)]?.isChecked = true
        tierButtons[CameraShareSettings.tier(prefs)]?.isChecked = true
        fpsButtons[CameraShareSettings.frameRate(prefs)]?.isChecked = true
        resolutionButtons.forEach { (r, b) -> b.setOnClickListener { CameraShareSettings.setResolution(prefs, r); paintEncoding() } }
        tierButtons.forEach { (t, b) -> b.setOnClickListener { CameraShareSettings.setTier(prefs, t); paintEncoding() } }
        fpsButtons.forEach { (f, b) -> b.setOnClickListener { CameraShareSettings.setFrameRate(prefs, f); paintEncoding() } }

        // Guards the programmatic revert (a refused CAMERA grant) from re-entering the listener —
        // the RemoteControlSettingsPanel idiom.
        var suppressShareSwitch = false
        var cameraPermissionDenied = false

        fun paintShare(state: CameraShareStatus.State) {
            val row = CameraShareSettingsModel.row(
                enabled = CameraShareSettings.isEnabled(prefs),
                state = state,
                // Remote Control is a prerequisite: its service owns the mDNS advertisement and
                // the snapshot endpoint, so sharing without it is a port nothing can discover.
                controlOn = ControlSettings.isEnabled(prefs),
                unsupportedReason = shareUnsupported,
                // Exactly the gate the RTSP server applies per challenge: the switch AND a stored
                // password. Probed once per bind — see `passwordSet` above — not re-read here.
                passwordSet = passwordSet,
                controlUrl = (ControlServerStatus.current() as? ControlServerStatus.State.Running)?.url.orEmpty(),
                permissionDenied = cameraPermissionDenied,
            )
            suppressShareSwitch = true
            shareSwitch.isChecked = row.switchChecked
            suppressShareSwitch = false
            shareSwitch.isEnabled = row.switchEnabled
            shareStatus.text = row.status
            shareWarning.text = row.warning.orEmpty()
            shareWarning.isVisible = row.warning != null
            shareUrl.text = row.url
            shareUrlRow.isVisible = row.url.isNotEmpty()
            shareSection.setSummary(row.summary)
            // The advice only means something while a share is on and could actually run.
            showAdvice = row.switchChecked && row.switchEnabled
            paintEncoding()
        }
        // Inline first, so the collapsed header and the switch are right on the first frame: both
        // publishers replay through a main-thread post, which lands a frame later.
        paintShare(CameraShareStatus.current())

        val shareListener: (CameraShareStatus.State) -> Unit = { paintShare(it) }
        CameraShareStatus.addListener(shareListener)
        // The address follows the control server (DHCP and Wi-Fi moves it), and the whole row
        // follows Remote Control being switched on or off — from its own tab or from the control
        // page — so this section listens to BOTH publishers.
        val controlListener: (ControlServerStatus.State) -> Unit = { paintShare(CameraShareStatus.current()) }
        ControlServerStatus.addListener(controlListener)

        shareSwitch.setOnCheckedChangeListener { _, checked ->
            if (suppressShareSwitch) return@setOnCheckedChangeListener
            if (!checked) {
                CameraShareSettings.setEnabled(prefs, false)
                CameraShareService.syncFromPrefs(activity)
                paintShare(CameraShareStatus.current())
                return@setOnCheckedChangeListener
            }
            cameraPermissionDenied = false
            // A camera foreground service needs the runtime grant (API 34+ refuses to start one
            // without it) and a Service has no window to ask from — so the ask lives here, and the
            // pref is only written once it is answered.
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED
            ) {
                activity.requestCameraShare { granted ->
                    if (granted) {
                        CameraShareSettings.setEnabled(prefs, true)
                        CameraShareService.syncFromPrefs(activity)
                    } else {
                        // Say why, and leave the switch as the user will find it. The reason is
                        // held in a flag rather than written straight onto the label, so the next
                        // status publish repaints it instead of erasing it.
                        cameraPermissionDenied = true
                        suppressShareSwitch = true
                        shareSwitch.isChecked = false
                        suppressShareSwitch = false
                    }
                    paintShare(CameraShareStatus.current())
                }
                return@setOnCheckedChangeListener
            }
            CameraShareSettings.setEnabled(prefs, true)
            CameraShareService.syncFromPrefs(activity)
            paintShare(CameraShareStatus.current())
        }

        return {
            CameraStatusRelay.removeListener(statusListener)
            CameraShareStatus.removeListener(shareListener)
            ControlServerStatus.removeListener(controlListener)
            rowTicker.removeCallbacks(rowTick)
            scope.cancel()
        }
    }

    // ---- Edit dialog ----------------------------------------------------------------------------

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
        val card = CardDialog(activity)
        card.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.setContentView(root)
        card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        card.followDisplaySize(activity)
        card.matchHostSystemBars(activity)

        val title = root.findViewById<TextView>(R.id.tvCamEditTitle)
        val nameField = root.findViewById<EditText>(R.id.etCamEditName)
        val rtspField = root.findViewById<EditText>(R.id.etCamEditRtsp)
        val mainField = root.findViewById<EditText>(R.id.etCamEditMain)
        val mainNote = root.findViewById<TextView>(R.id.tvCamEditMainNote)
        val snapshotField = root.findViewById<EditText>(R.id.etCamEditSnapshot)
        val userField = root.findViewById<EditText>(R.id.etCamEditUser)
        val passField = root.findViewById<EditText>(R.id.etCamEditPass)
        val audioSwitch = root.findViewById<SwitchMaterial>(R.id.switchCamEditAudio)
        val forceTcpSwitch = root.findViewById<SwitchMaterial>(R.id.switchCamEditForceTcp)
        val testButton = root.findViewById<MaterialButton>(R.id.btnCamEditTest)
        val testResult = root.findViewById<TextView>(R.id.tvCamEditTestResult)
        val deleteButton = root.findViewById<MaterialButton>(R.id.btnCamEditDelete)
        val cancelButton = root.findViewById<MaterialButton>(R.id.btnCamEditCancel)
        val saveButton = root.findViewById<MaterialButton>(R.id.btnCamEditSave)
        val error = root.findViewById<TextView>(R.id.tvCamEditError)

        title.text = if (existingCamera == null) "Add camera" else "Edit camera"
        deleteButton.isVisible = existingCamera != null

        nameField.setText(existingCamera?.name ?: prefill?.name.orEmpty())
        rtspField.setText(existingCamera?.rtspUrl ?: prefill?.rtspUrl.orEmpty())
        mainField.setText(existingCamera?.mainRtspUrl ?: prefill?.mainRtspUrl.orEmpty())
        // Discovery only skips a main stream when it found one this device can't decode. Saying so
        // in amber, in place of the neutral hint, explains the empty field instead of leaving it
        // looking like the camera has no main stream at all.
        prefill?.mainSkippedNote?.let {
            mainNote.text = it
            mainNote.setTextColor(ContextCompat.getColor(activity, R.color.dot_amber))
        }
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
            mainRtspUrl = mainField.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() },
            snapshotUrl = snapshotField.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() },
            username = userField.text?.toString(),
            password = passField.text?.toString(),
            audioEnabled = audioSwitch.isChecked,
            forceTcp = forceTcpSwitch.isChecked,
        )

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
                    val lines = mutableListOf<String>()

                    // Each step repaints as soon as it lands, so a slow camera shows the stream's
                    // result while the main stream is still being probed instead of nothing at all.
                    suspend fun probeLine(label: String, rawUrl: String, hintSuffix: String) {
                        val s = CameraCodec.splitInlineCredentials(rawUrl)
                        val uri = CameraUri.withCredentials(s.url, user ?: s.username, pass ?: s.password)
                        val result = withTimeoutOrNull(TEST_TIMEOUT_MS) { probeRtsp(activity, uri, edit.forceTcp) }
                            ?: ProbeResult(false, null)
                        lines += TestReport.line(label, result.ok, result.format)
                        if (!result.ok) TestReport.hint(result.errorKind)?.let { lines += it }
                        val f = result.format
                        if (result.ok && f != null) {
                            // Still a ✓: the RTSP handshake really did succeed, only playback on
                            // this device would fail. The ⚠ line is the hint, not the verdict.
                            CodecHint.build(f, DeviceDecoders.canDecode(f.mime, f.width, f.height), hintSuffix)?.let { lines += "⚠ $it" }
                        }
                        testResult.text = lines.joinToString("\n")
                    }

                    testResult.text = "Testing…"
                    probeLine("Stream", edit.rtspUrl, "set the camera to H.264 at a lower resolution.")
                    edit.mainRtspUrl?.takeIf { it.isNotBlank() }?.let {
                        probeLine("Main", it, "use the stream above for live view, or set the camera to H.264.")
                    }
                    edit.snapshotUrl?.takeIf { it.isNotBlank() }?.let { rawSnapshot ->
                        val snapSplit = CameraCodec.splitInlineCredentials(rawSnapshot)
                        val ok = withTimeoutOrNull(TEST_TIMEOUT_MS) {
                            AndroidSnapshotIo(activity).fetchHttp(snapSplit.url, user ?: snapSplit.username, pass ?: snapSplit.password)
                        } != null
                        lines += TestReport.line("Snapshot", ok, null)
                        testResult.text = lines.joinToString("\n")
                    }
                    testButton.isEnabled = true
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
            val result = CameraSettingsModel.applyEdit(cameras(), edit)
            onSaved(result)
            card.dismiss()
        }

        card.show()
        nameField.requestFocus()
    }

    /** A short headless RTSP probe: builds a throwaway main-looper ExoPlayer against
     *  [RtspMediaSource][androidx.media3.exoplayer.rtsp.RtspMediaSource] and reports success —
     *  plus the video track's codec/resolution/frame rate when the player exposed one — the
     *  moment it reaches `STATE_READY`, failure on any player error. Mirrors
     *  [CameraPlaybackPlan][dev.rusty.app.CameraPlaybackPlan]'s success/failure semantics without
     *  reusing its reconnect state machine — a Test button wants one shot, not a retry ladder.
     *  Always released in `finally`. */
    private suspend fun probeRtsp(context: Context, rtspUriWithCreds: String, forceTcp: Boolean): ProbeResult =
        withContext(Dispatchers.Main.immediate) {
            RtspProbe.run(context, rtspUriWithCreds, forceTcp)
        }

    /** Login prompt for a discovered camera. [fixedUsername] non-null (a Rusty share, which always
     *  authenticates as [RtspAuth.USER]) shows that name in a disabled field and starts on the
     *  password, so the only thing asked for is the only thing that varies. */
    private fun openDiscoveryCredentialsDialog(
        cameraName: String?,
        fixedUsername: String?,
        onSubmit: (username: String, password: String) -> Unit,
    ) {
        val activity = ctx.activity
        val root = activity.layoutInflater.inflate(R.layout.dialog_camera_credentials, null)
        val card = CardDialog(activity)
        card.requestWindowFeature(Window.FEATURE_NO_TITLE)
        card.setContentView(root)
        card.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        card.followDisplaySize(activity)
        card.matchHostSystemBars(activity)

        root.findViewById<TextView>(R.id.tvCamCredTitle).text = cameraName ?: "Camera credentials"
        val userField = root.findViewById<EditText>(R.id.etCamCredUser)
        val passField = root.findViewById<EditText>(R.id.etCamCredPass)
        if (fixedUsername != null) {
            userField.setText(fixedUsername)
            userField.isEnabled = false
            // Disabled is already skipped by focus search; cleared explicitly as well so the D-pad
            // can never stop on a field that cannot be typed into (same belt-and-braces as the
            // already-added discovery rows).
            userField.isFocusable = false
        }

        root.findViewById<MaterialButton>(R.id.btnCamCredCancel).setOnClickListener { card.dismiss() }
        root.findViewById<MaterialButton>(R.id.btnCamCredSave).setOnClickListener {
            val user = userField.text?.toString().orEmpty()
            val pass = passField.text?.toString().orEmpty()
            card.dismiss()
            onSubmit(user, pass)
        }
        card.show()
        (if (fixedUsername != null) passField else userField).requestFocus()
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
        const val TEST_TIMEOUT_MS = 8_000L
    }
}

/** Isolated so [CameraSettingsPanel] stays free of ExoPlayer wiring in its main body. Not unit
 *  tested — thin Android I/O, same posture as [AndroidSnapshotIo.grabFrame]. */
data class ProbeResult(val ok: Boolean, val format: VideoFormatInfo?, val errorKind: StreamErrorKind? = null)

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private object RtspProbe {
    suspend fun run(context: Context, uri: String, forceTcp: Boolean): ProbeResult {
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        return try {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                val done = java.util.concurrent.atomic.AtomicBoolean(false)
                fun finish(result: ProbeResult) {
                    if (done.compareAndSet(false, true) && cont.isActive) cont.resumeWith(Result.success(result))
                }
                player.addListener(object : androidx.media3.common.Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == androidx.media3.common.Player.STATE_READY) {
                            val f = player.videoFormat
                            val info = f?.sampleMimeType?.let { VideoFormatInfo(it, f.width, f.height, f.frameRate) }
                            finish(ProbeResult(true, info))
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        val kind = CameraRetryPolicy.classify(error.errorCode, PlaybackErrorText.flatten(error))
                        finish(ProbeResult(false, null, kind))
                    }
                })
                val source = androidx.media3.exoplayer.rtsp.RtspMediaSource.Factory()
                    .setForceUseRtpTcp(forceTcp)
                    .createMediaSource(androidx.media3.common.MediaItem.fromUri(uri))
                player.setMediaSource(source)
                player.prepare()
                cont.invokeOnCancellation { finish(ProbeResult(false, null)) }
            }
        } finally {
            player.release()
        }
    }
}

/** Mutual exclusion for Flow-positioned radios (not a RadioGroup's direct children); copied from
 *  SpotifyFeature.bindRadioChoice so the two panels share one behaviour. */
private fun <T> bindCameraRadioChoice(options: List<Pair<RadioButton, T>>, selected: T, onSelect: (T) -> Unit) {
    options.forEach { (radio, value) -> radio.isChecked = value == selected }
    var suppress = false
    options.forEach { (radio, value) ->
        radio.setOnCheckedChangeListener { _, isChecked ->
            if (!isChecked || suppress) return@setOnCheckedChangeListener
            suppress = true
            options.forEach { (other, _) -> if (other !== radio) other.isChecked = false }
            suppress = false
            onSelect(value)
        }
    }
}
