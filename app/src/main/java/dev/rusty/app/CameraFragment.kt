package dev.rusty.app

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.google.android.material.button.MaterialButton
import dev.rusty.app.renderer.RendererRuntimeHolder
import dev.rusty.app.renderer.RendererTransport
import dev.rusty.app.renderer.RendererUiSnapshot
import kotlinx.coroutines.launch

/**
 * The Camera feature screen: a snapshot grid and a full-screen live view, toggled inside one
 * [FrameLayout] (`fragment_camera.xml`). This is also where every piece from Tasks 2–10 gets wired
 * together at runtime — [CameraSnapshots]/[SnapshotScheduler] for the grid, [CameraPlayback] for
 * live playback, and [CameraArbitration] for audio focus / Spotify pause-resume / camera mute.
 *
 * Lifecycle: [retainStartedWhenHidden] stays false (see [CameraFeature]), so hiding this fragment
 * (another feature shown, or this one stopped) drives it down to `CREATED` — [onStop] releases the
 * player and stops the snapshot ticker, [onStart] restarts them. Nothing here survives a hide.
 */
class CameraFragment : Fragment(), InsetAware, KeyEventTarget, FocusRestorable {

    private enum class Mode { GRID, LIVE }

    // ---- Arbitration wiring -------------------------------------------------------------------
    //
    // Spotify "generation": a local counter incremented every time the observed Spotify playing
    // state flips false -> true (a fresh play intent, whether the user started it or we resumed it
    // ourselves after releasing camera audio). CameraArbitration only ever compares generations for
    // equality, so any monotonically-increasing scheme satisfies its contract; this is the simplest
    // one that needs no cooperation from the receiver.
    private var arbState = ArbState()
    private var spotifyGeneration = 0L
    private var spotifyPlayingLast = false
    private var dlnaVideoPlayingLast = false

    /** True from a successful [ArbEvent.LiveOpened] dispatch until the matching [ArbEvent.LiveClosed]
     *  — guards the reducer, which is unguarded for a re-entrant open (see the task brief). */
    private var liveSessionOpen = false

    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioManager: AudioManager by lazy {
        requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> dispatch(ArbEvent.FocusGranted)
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> dispatch(ArbEvent.FocusLost)
        }
    }

    private val storeListener = ReceiverStateStore.Listener { snapshot ->
        val playing = snapshot.state.status == "Playing"
        if (playing != spotifyPlayingLast) {
            spotifyPlayingLast = playing
            if (playing) spotifyGeneration++
            dispatch(ArbEvent.SpotifyPlayingChanged(playing, spotifyGeneration))
        }
    }

    private val rendererListener: (RendererUiSnapshot) -> Unit = { snap ->
        val playing = snap.state?.transport == RendererTransport.PLAYING
        if (playing != dlnaVideoPlayingLast) {
            dlnaVideoPlayingLast = playing
            dispatch(ArbEvent.DlnaVideoPlayingChanged(playing))
            applySchedulerSuspension()
        }
    }

    // ---- Playback / snapshots ------------------------------------------------------------------

    // CameraPlayback is TERMINAL: release() latches it dead forever (every later open/switchTo/
    // attachView no-ops, and a second release() never re-fires onClosed). So this is a fresh
    // instance per LIVE SESSION — built in showLive's "opening from grid" branch, released and
    // dropped in showGrid/onStop — never a fragment-scoped singleton. [lastLiveState] mirrors
    // whatever the live instance last reported so overlay/keep-screen-on code never has to guess a
    // default for "no session open".
    private var playback: CameraPlayback? = null
    private var lastLiveState: LiveState = LiveState.Connecting

    // Rebuilt (not just reconfigured) whenever camera_grid_refresh_s changes — refreshIntervalMs is
    // a constructor val — see rebuildSnapshotPipeline(). Assigned in onViewCreated, once prefs is
    // available; unused before that (nothing runs before onViewCreated).
    private lateinit var scheduler: SnapshotScheduler

    // CameraSnapshots captures a CoroutineScope (viewLifecycleOwner.lifecycleScope) that is
    // cancelled in onDestroyView — and, per this app's retained-fragment shell, a HIDDEN feature is
    // capped below STARTED, which DOES tear down and later rebuild the fragment's view (see
    // HomeAssistantFragment's WebView rebuild in its own onCreateView/onDestroyView). A single
    // fragment-scoped instance would therefore run its ticker into a dead scope after the first
    // hide/re-show. So this is built fresh per view in onViewCreated and dropped in onDestroyView.
    private var snapshots: CameraSnapshots? = null

    private lateinit var prefs: SharedPreferences
    private lateinit var secretStore: SecretStore
    private var cameraList: List<CameraRecord> = emptyList()

    /** Last painted (state, offline text) per tile, so the one-second tick repaints only the tiles
     *  whose visible status actually moved — see [gridTickRunnable]. */
    private val tilePaint = TilePaintCache()

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            CameraStore.KEY_CAMERA_LIST -> {
                refreshCameraList()
                if (mode == Mode.LIVE && cameraList.none { it.id == currentCameraId }) showGrid()
            }
            CameraFeature.KEY_ENABLED -> if (!CameraFeature.isEnabled(prefs)) showGrid()
            // frame-grab is a plain SnapshotScheduler.setCameras() argument (not baked into its
            // constructor), so re-running refreshCameraList() is enough to pick it up live.
            CameraBehaviorPrefs.KEY_FRAME_GRAB_ENABLED -> refreshCameraList()
            // refreshIntervalMs IS a SnapshotScheduler constructor val — the scheduler (and the
            // CameraSnapshots that captured it) must be rebuilt, not just re-argued.
            CameraBehaviorPrefs.KEY_GRID_REFRESH_S -> rebuildSnapshotPipeline()
            // A new layout re-fits the columns and restarts at page 0; the paint cache is keyed by
            // camera id, so it has to be dropped for the tiles to repaint in their new positions.
            CameraBehaviorPrefs.KEY_GRID_LAYOUT -> { currentPage = 0; tilePaint.prune(emptySet()); relayoutGrid(); adapter.notifyDataSetChanged() }
            CameraBehaviorPrefs.KEY_PAGE_ROTATION_S -> armPageRotation()
        }
    }

    private var mode = Mode.GRID
    private var currentCameraId: String? = null

    /**
     * Whether the live chrome is currently revealed. The single source of truth for
     * [setChromeVisible]: the fade-out ends by setting views INVISIBLE, and an animation that
     * finishes after a newer reveal has already run must not undo it — so the completion re-reads
     * this flag instead of trusting the state it was scheduled in.
     */
    private var chromeRevealed = true
    private var keepScreenOnBase = false

    // ---- Views ----------------------------------------------------------------------------------

    private var gridContainer: View? = null
    private var grid: RecyclerView? = null
    private var gridEmpty: TextView? = null
    private var liveContainer: FrameLayout? = null
    private var playerView: PlayerView? = null
    private var liveChrome: View? = null
    private var liveName: TextView? = null
    private var audioButton: ImageButton? = null
    private var liveHint: TextView? = null
    private var liveBottomBar: View? = null
    /** True only between a user-driven sub/main switch and its outcome, so the connect overlay
     *  says "Switching to … stream…" for an actual switch and plain "Connecting…" for a first
     *  open or a reconnect. Cleared on Playing/Fatal and by every [setMode] (which every camera
     *  switch and every exit to the grid goes through). */
    private var pendingStreamSwitch = false
    private var streamToggle: View? = null
    private var streamSub: TextView? = null
    private var streamMain: TextView? = null
    private var backButton: ImageButton? = null
    private var prevButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var connectOverlay: LinearLayout? = null
    private var connectText: TextView? = null
    private var fatalOverlay: LinearLayout? = null
    private var fatalText: TextView? = null
    private var retryButton: MaterialButton? = null
    private var snapshotButton: ImageButton? = null
    /** [SystemClock.elapsedRealtime] of the last accepted [saveSnapshot] press, so a burst of OK
     *  presses or button taps within a second coalesces into a single capture. */
    private var lastSnapshotAt = 0L

    // ---- Grid layout / paging -------------------------------------------------------------------

    private var pageNumbers: LinearLayout? = null
    private var lastInsets: androidx.core.graphics.Insets = androidx.core.graphics.Insets.NONE
    private var currentPage = 0
    private var currentCols = 2
    private val pageRotationRunnable = object : Runnable {
        override fun run() {
            if (mode == Mode.GRID && pageCount() > 1) flipPage(1)
            armPageRotation()
        }
    }

    private fun layoutChoice(): GridLayoutChoice =
        CameraBehaviorPrefs.gridLayoutFrom(prefs.getString(CameraBehaviorPrefs.KEY_GRID_LAYOUT, null))

    private fun pageSize(): Int? = layoutChoice().pageSize

    private fun pageCount(): Int = pageSize()?.let { Pager.pageCount(cameraList.size, it) } ?: 1

    /** The cameras the adapter is showing right now: the whole list, or the current page. */
    private fun visibleCameras(): List<CameraRecord> {
        val size = pageSize() ?: return cameraList
        currentPage = Pager.clamp(currentPage, cameraList.size, size)
        return Pager.slice(cameraList, size, currentPage)
    }

    private lateinit var adapter: RecyclerView.Adapter<TileHolder>

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideChromeRunnable = Runnable { setChromeVisible(false) }
    private val reconnectTickRunnable = object : Runnable {
        override fun run() {
            renderOverlay(lastLiveState)
            mainHandler.postDelayed(this, 500L)
        }
    }
    private val gridTickRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            if (mode == Mode.GRID) {
                // Repaint only tiles whose (state, offline text) moved — a healthy grid repaints
                // nothing; an offline tile once a second for its first minute, then once a minute.
                visibleCameras().forEachIndexed { index, cam ->
                    val state = scheduler.tileState(cam.id, now)
                    if (tilePaint.update(cam.id, state, offlineTextFor(cam.id, state, now))) {
                        adapter.notifyItemChanged(index, PAYLOAD_STATE)
                    }
                }
            }
            // NOT skipped while LIVE: the remote and the settings rows read these off-screen too.
            publishApiStates()
            publishStatuses(now)
            mainHandler.postDelayed(this, 1_000L)
        }
    }

    /** Re-arms the shell's idle screensaver timer while a live view keeps rendering without any
     *  key/touch input of its own (the same purpose [KeepScreenOnSettings] serves for the display
     *  timeout). Not needed for Fatal — the screen is not worth keeping awake for a dead camera. */
    private val idleKeepAliveRunnable = object : Runnable {
        override fun run() {
            (activity as? ShellHost)?.keepAlive()
            mainHandler.postDelayed(this, IDLE_KEEP_ALIVE_MS)
        }
    }

    // ---- Fragment lifecycle ----------------------------------------------------------------------

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_camera, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        secretStore = SecretStore.of(requireContext())

        gridContainer = view.findViewById(R.id.cameraGridContainer)
        grid = view.findViewById(R.id.cameraGrid)
        gridEmpty = view.findViewById(R.id.cameraGridEmpty)
        liveContainer = view.findViewById(R.id.cameraLiveContainer)
        playerView = view.findViewById(R.id.cameraPlayerView)
        liveChrome = view.findViewById(R.id.cameraLiveChrome)
        liveName = view.findViewById(R.id.cameraLiveName)
        audioButton = view.findViewById(R.id.cameraAudioButton)
        liveHint = view.findViewById(R.id.cameraLiveHint)
        liveBottomBar = view.findViewById(R.id.cameraLiveBottomBar)
        relayoutLiveBars()
        streamToggle = view.findViewById(R.id.cameraStreamToggle)
        streamSub = view.findViewById(R.id.cameraStreamSub)
        streamMain = view.findViewById(R.id.cameraStreamMain)
        backButton = view.findViewById(R.id.cameraBackButton)
        prevButton = view.findViewById(R.id.cameraPrevButton)
        nextButton = view.findViewById(R.id.cameraNextButton)
        connectOverlay = view.findViewById(R.id.cameraConnectOverlay)
        connectText = view.findViewById(R.id.cameraConnectText)
        fatalOverlay = view.findViewById(R.id.cameraFatalOverlay)
        fatalText = view.findViewById(R.id.cameraFatalText)
        retryButton = view.findViewById(R.id.cameraRetryButton)
        pageNumbers = view.findViewById(R.id.cameraPageNumbers)
        snapshotButton = view.findViewById(R.id.cameraSnapshotButton)

        retryButton?.setOnClickListener { playback?.manualRetry() }
        liveContainer?.setOnClickListener { restoreChrome() }
        // The touch twins of the keys onKeyEvent already handles in LIVE mode. Same call, so
        // there is exactly one implementation of each action however it is reached.
        backButton?.setOnClickListener { showGrid() }
        prevButton?.setOnClickListener { switchCamera(-1) }
        nextButton?.setOnClickListener { switchCamera(1) }
        streamSub?.setOnClickListener { selectStream(StreamChoice.SUB) }
        streamMain?.setOnClickListener { selectStream(StreamChoice.MAIN) }
        snapshotButton?.setOnClickListener { saveSnapshot() }
        audioButton?.setOnClickListener { toggleAudio() }

        adapter = object : RecyclerView.Adapter<TileHolder>() {
            override fun getItemCount() = visibleCameras().size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
                val itemView = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_camera_tile, parent, false)
                return TileHolder(itemView)
            }
            override fun onBindViewHolder(holder: TileHolder, position: Int) = bindTile(holder, visibleCameras()[position])

            /**
             * Partial rebind. Falls back to the full bind for an empty or unrecognised payload
             * list — RecyclerView merges payloads from coalesced notifications, and a list this
             * doesn't understand must never be treated as "nothing to do".
             */
            override fun onBindViewHolder(holder: TileHolder, position: Int, payloads: MutableList<Any>) {
                val cam = visibleCameras()[position]
                when {
                    payloads.isEmpty() -> bindTile(holder, cam)
                    payloads.all { it == PAYLOAD_STATE } -> paintTileState(holder, cam)
                    payloads.all { it == PAYLOAD_STATE || it == PAYLOAD_FRAME } -> {
                        holder.image.setImageBitmap(snapshots?.tiles?.get(cam.id))
                        paintTileState(holder, cam)
                    }
                    else -> bindTile(holder, cam)
                }
            }
        }
        grid?.layoutManager = GridLayoutManager(requireContext(), 2)
        grid?.adapter = adapter

        // Swipe flips pages on a touchscreen — the touch twin of the LEFT/RIGHT edge keys below.
        // A plain ACTION_DOWN (no fling) still re-arms the rotation timer, so a user handling the
        // grid is never yanked to another page mid-look.
        val swipe = android.view.GestureDetector(requireContext(), object : android.view.GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: android.view.MotionEvent?, e2: android.view.MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || pageCount() <= 1) return false
                val dx = e2.x - e1.x
                if (kotlin.math.abs(dx) < dp(80) || kotlin.math.abs(vx) < 800f || kotlin.math.abs(dx) < kotlin.math.abs(e2.y - e1.y)) return false
                flipPage(if (dx < 0) 1 else -1)
                return true
            }
        })
        grid?.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (e.action == android.view.MotionEvent.ACTION_DOWN) armPageRotation()
                swipe.onTouchEvent(e)
                return false
            }
        })

        // Belt and braces alongside the payloads above: refreshCameraList()'s notifyDataSetChanged
        // is a legitimate payload-less notification, and nothing about a tile's CONTENT changing is
        // worth a cross-fade in a grid of live camera frames.
        (grid?.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        // scheduler is (re)built here — and again by rebuildSnapshotPipeline() whenever
        // camera_grid_refresh_s changes — because refreshIntervalMs is a SnapshotScheduler
        // constructor val; see the field comment on [scheduler].
        scheduler = SnapshotScheduler(refreshIntervalMs = effectiveRefreshIntervalMs())

        // Built fresh per view (see the field comment on [snapshots]) — the tile-listener closure
        // over `adapter` is torn down with this same instance in onDestroyView, so it never
        // outlives the adapter it references. buildSnapshots() attaches the same listener; also
        // used by rebuildSnapshotPipeline() when camera_grid_refresh_s changes mid-view.
        snapshots = buildSnapshots()

        // A hide/re-show cycle recreates the view (and this fragment's `mode` was already reset to
        // GRID in the onStop that preceded the teardown), so the freshly-inflated live container
        // always starts hidden here — applyMode() just makes that authoritative.
        applyMode()
        relayoutGrid()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // Posted: the RecyclerView has not been measured against the new configuration yet.
        grid?.post { relayoutGrid() }
    }

    override fun onStart() {
        super.onStart()
        refreshCameraList()
        // 0 = off: the loop simply never runs — tiles stay at TileState.NONE (placeholder), never
        // TileState.UNREACHABLE, since no job is ever dispatched to fail. Do NOT pass 0 as
        // refreshIntervalMs itself — SnapshotScheduler treats that as "due immediately, always".
        if (refreshSecondsPref() > 0) snapshots?.start()
        mainHandler.post(gridTickRunnable)
        RustyApp.from(requireContext()).let { store ->
            storeSource = store
            store.addListener(storeListener)
        }
        RendererRuntimeHolder.addListener(rendererListener)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
        CameraTestRegistry.addListener(testRegistryListener)
        applySchedulerSuspension()
        armPageRotation()
        // onStart..onStop is exactly the window in which this fragment can answer the remote:
        // the list is loaded, the snapshot loop runs, and showLive can open a session.
        CameraControlRelay.attachHost(controlHost)
    }

    override fun onStop() {
        // Detached FIRST: from here on this fragment can no longer honestly answer the remote, and
        // a summon arriving mid-teardown must find no host (its retry loop then simply waits for
        // the next onStart) rather than a fragment being taken apart.
        CameraControlRelay.detachHost(controlHost)
        apiStates = emptyMap()
        // Nothing is polling the cameras any more, so the settings rows must read "Not checked"
        // rather than keep showing whatever state was frozen at the moment the panel closed.
        CameraStatusRelay.publish(emptyMap())
        // A live view being stopped (Home press, screen off, another feature shown) ends a summon
        // just as surely as BACK does — this path resets `mode` itself instead of going through
        // showGrid, so the notification has to be raised here too. Without it the summon would
        // survive an app stop: the plan would stay active with nothing on screen, a later dismiss
        // would "restore" into a window that no longer exists, and the camera panel would still be
        // the selected one at the next launch. Raised AFTER detachHost, so the restore it triggers
        // finds no camera host to call showGrid on — the teardown below is already doing that.
        val wasLive = mode == Mode.LIVE
        if (wasLive) CameraControlRelay.notifyLiveExited()
        // Release the live session (if any) BEFORE resetting `mode`/`currentCameraId` — release()
        // synchronously fires onPlaybackClosed -> ArbEvent.LiveClosed (guarded by liveSessionOpen),
        // and that dispatch's command execution (Abandon/MuteCameraAudio, ResumeSpotify) still
        // needs the pre-teardown state to be coherent.
        playback?.release()
        playback = null
        snapshots?.stop()
        // Reset for the NEXT onCreateView: `mode` is a fragment-instance field that survives a
        // hide (unlike the view itself, which gets torn down and rebuilt — see the field comment
        // on [snapshots]), so leaving it at LIVE would show an empty live container with no
        // player and a wrongly-suspended scheduler on the next show. But a hidden-feature switch
        // is NOT the only path through onStop — an Activity-level stop (Home press, screen off)
        // stops this fragment WITHOUT tearing its view down, so the view from a moment ago is
        // still live right now. Re-apply the mode immediately (not just leave it for a
        // onViewCreated that may never come) so that path doesn't leave cameraLiveContainer
        // visible with no player underneath.
        mode = Mode.GRID
        currentCameraId = null
        applyMode()
        mainHandler.removeCallbacks(gridTickRunnable)
        mainHandler.removeCallbacks(reconnectTickRunnable)
        mainHandler.removeCallbacks(hideChromeRunnable)
        mainHandler.removeCallbacks(idleKeepAliveRunnable)
        mainHandler.removeCallbacks(pageRotationRunnable)
        storeSource?.removeListener(storeListener)
        storeSource = null
        RendererRuntimeHolder.removeListener(rendererListener)
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        CameraTestRegistry.removeListener(testRegistryListener)
        // Unconditional safety net: never trust `focusHeld` bookkeeping on teardown (a stray missed
        // FocusGranted/Lost edge could otherwise leave the OS holding focus for us indefinitely).
        abandonAudioFocus()
        super.onStop()
    }

    override fun onDestroyView() {
        // Defensive: onStop (which already stopped it) always precedes onDestroyView on a normal
        // hide, but stop() is idempotent, so re-stopping here costs nothing and guarantees the
        // scope-holding instance is never left running past its view's lifetime.
        snapshots?.stop()
        snapshots = null
        apiSnapshots = null
        grid?.adapter = null
        gridContainer = null
        grid = null
        gridEmpty = null
        liveContainer = null
        playerView = null
        liveChrome = null
        liveName = null
        audioButton = null
        liveHint = null
        liveBottomBar = null
        streamToggle = null
        streamSub = null
        streamMain = null
        backButton = null
        prevButton = null
        nextButton = null
        connectOverlay = null
        connectText = null
        fatalOverlay = null
        fatalText = null
        retryButton = null
        snapshotButton = null
        pageNumbers = null
        super.onDestroyView()
    }

    private var storeSource: ReceiverStateStore? = null

    // ---- Public surface (Task 13) ----------------------------------------------------------------

    /**
     * Shows the live view for [cameraId]. Called both by the grid's OK/tap and, in a later task,
     * the summon coordinator.
     *
     * If [cameraId] is already the camera on screen this still re-renders the identity chrome and
     * restores it from a faded-out state — a Task 13 summon of the camera already showing must not
     * look like a no-op — but it does not touch [CameraPlayback] or dispatch an arbitration event.
     *
     * Dropped silently (no-op) if [cameraId] is not in [cameraList] yet — in particular, a call
     * that lands before this fragment's first [onStart]/[refreshCameraList] has ever run (cold
     * summon) finds an empty list and does nothing. Also dropped if the fragment's view isn't
     * currently alive and RESUMED — a stopped-but-populated fragment (Home press, screen off,
     * feature hidden) must not build a live session nobody can see and that would still take
     * audio focus. Task 13's summon coordinator must call this only once the fragment is
     * confirmed foreground, or be prepared to retry.
     */
    fun showLive(cameraId: String) {
        if (view == null || !isResumed) return
        val cam = cameraList.firstOrNull { it.id == cameraId } ?: return
        val wasLive = mode == Mode.LIVE
        if (wasLive && currentCameraId == cameraId) {
            renderLiveIdentity(cam)
            restoreChrome()
            return
        }
        val (user, pass) = secretsFor(cam.id)
        setMode(Mode.LIVE)
        if (wasLive) {
            // Same live session, different camera: reuse the existing (non-terminal-yet)
            // CameraPlayback instance.
            playback?.switchTo(cam, user, pass)
            currentCameraId = cam.id
            dispatch(ArbEvent.CameraSwitched(cam.audioEnabled))
        } else {
            // Fresh session: CameraPlayback is terminal (release() latches it dead forever), so a
            // brand-new instance is built here rather than reused from a previous session.
            val p = buildPlayback()
            playback = p
            p.attachView(playerView)
            p.open(cam, user, pass)
            currentCameraId = cam.id
            liveSessionOpen = true
            dispatch(ArbEvent.LiveOpened(cam.audioEnabled))
        }
        // setMode above published the states before currentCameraId was known; republish now that
        // it is, so `GET /api/cameras` reports the right camera as "live" without waiting a tick.
        publishApiStates()
        renderLiveIdentity(cam)
        restoreChrome()
    }

    private fun buildPlayback(): CameraPlayback {
        // A prior session's terminal state (e.g. Fatal) must not leak into this fresh one and
        // briefly compute keep-screen-on/idle-suppression wrong before the first onState callback
        // lands (CameraPlayback.open() calls it synchronously right after this returns, but this
        // keeps the field correct even for the instant in between).
        lastLiveState = LiveState.Connecting
        return CameraPlayback(
            requireContext(),
            onState = ::onLiveStateChanged,
            onClosed = ::onPlaybackClosed,
            onKeepScreenOn = { keepScreenOnBase = it; applyKeepScreenOn() },
        )
    }

    /** Returns to the snapshot grid, tearing down live playback. Idempotent. */
    fun showGrid() {
        if (mode == Mode.GRID) return
        playback?.release() // fires onPlaybackClosed -> ArbEvent.LiveClosed (guarded by liveSessionOpen)
        playback = null
        setMode(Mode.GRID)
        currentCameraId = null
        // The single external-exit hook for the remote-control summon (Task 13): BACK, a deleted
        // camera and the feature being switched off ALL leave the live view through here, so one
        // call site covers every one of them. A summon's own restore also calls showGrid — which
        // is why this sits after the early return above: by then its plan is already inactive, and
        // an inactive plan produces no commands, so the two can never ping-pong.
        CameraControlRelay.notifyLiveExited()
    }

    // ---- Remote-control seam (CameraControlHost) --------------------------------------------------

    /**
     * Attached for `onStart`..`onStop` — exactly the window in which this fragment has a camera
     * list and a running snapshot loop, and therefore the only window in which any of these can
     * answer honestly.
     */
    private val controlHost = object : CameraControlHost {
        override fun showLiveNow(cameraId: String): Boolean {
            showLive(cameraId)
            // showLive drops the call when the fragment isn't resumed or the list hasn't loaded;
            // observing the resulting mode is how the summon's retry loop learns which happened.
            return mode == Mode.LIVE && currentCameraId == cameraId
        }

        override fun showGridNow() = showGrid()

        // jpegs is a ConcurrentHashMap and apiStates is a @Volatile immutable map, so both are
        // safe to read straight from an HTTP pool thread.
        override fun snapshotJpeg(cameraId: String): ByteArray? = apiSnapshots?.jpegs?.get(cameraId)

        override fun cameraStates(): Map<String, String> = apiStates
    }

    /** The live [snapshots] instance, published for cross-thread reads by [controlHost]. */
    @Volatile private var apiSnapshots: CameraSnapshots? = null

    /** camera id -> wire state for `GET /api/cameras`; replaced wholesale (never mutated) on the
     *  main thread by [publishApiStates], so a reader always sees a coherent map. */
    @Volatile private var apiStates: Map<String, String> = emptyMap()

    /** Recomputes [apiStates]. Called on every grid tick (so it is at most ~1 s stale), and on the
     *  edges that change it outright: a mode switch and a camera-list reload. */
    private fun publishApiStates() {
        val now = SystemClock.elapsedRealtime()
        apiStates = cameraList.associate { cam ->
            cam.id to when {
                mode == Mode.LIVE && cam.id == currentCameraId -> "live"
                else -> scheduler.tileState(cam.id, now).name.lowercase()
            }
        }
    }

    // ---- Mode / scheduler suspension --------------------------------------------------------------

    private fun setMode(next: Mode) {
        mode = next
        // Every exit to the grid and every camera switch passes through here, and neither is a
        // stream switch — so a stale `true` can never mislabel the next camera's first connect.
        pendingStreamSwitch = false
        applyMode()
        applySchedulerSuspension()
        publishApiStates()
        renderPageNumbers()
        armPageRotation()
        // Returning to GRID is the first moment a cold-summoned view can be measured, and the
        // deferred relayout chain was deliberately dropped while the container was GONE (see
        // relayoutGrid) — so re-fit here or the grid would land at the default 2 columns.
        relayoutGrid()
    }

    private fun applyMode() {
        gridContainer?.visibility = if (mode == Mode.GRID) View.VISIBLE else View.GONE
        liveContainer?.visibility = if (mode == Mode.LIVE) View.VISIBLE else View.GONE
        if (mode == Mode.GRID) {
            mainHandler.removeCallbacks(hideChromeRunnable)
            mainHandler.removeCallbacks(reconnectTickRunnable)
        }
    }

    private fun applySchedulerSuspension() {
        scheduler.setSuspended(
            liveViewOpen = mode == Mode.LIVE,
            dlnaVideo = dlnaVideoPlayingLast,
            testRunning = CameraTestRegistry.current(),
        )
    }

    /** Re-applies suspension whenever the settings Test button marks/clears a camera id in
     *  [CameraTestRegistry] — see [onStart]/[onStop] for the registration window. */
    private val testRegistryListener: () -> Unit = { applySchedulerSuspension() }

    // ---- Playback callbacks --------------------------------------------------------------------

    private fun onPlaybackClosed() {
        if (liveSessionOpen) {
            liveSessionOpen = false
            dispatch(ArbEvent.LiveClosed)
        }
    }

    private fun onLiveStateChanged(state: LiveState) {
        lastLiveState = state
        applyKeepScreenOn()
        // The switch has an outcome now, so the next Connecting is an ordinary one. Cleared before
        // the render, which is the first thing that must see the new value. Reconnecting keeps the
        // flag: the switch has not landed yet, and the retry is still on its way to the new stream.
        if (state is LiveState.Playing || state is LiveState.Fatal) pendingStreamSwitch = false
        renderOverlay(state)
        if (state is LiveState.Reconnecting) {
            mainHandler.removeCallbacks(reconnectTickRunnable)
            mainHandler.post(reconnectTickRunnable)
        } else {
            mainHandler.removeCallbacks(reconnectTickRunnable)
        }
        if (state is LiveState.Fatal && view?.isInTouchMode == false) {
            retryButton?.requestFocus()
        }
    }

    /** Drops keep-screen-on while a [LiveState.Fatal] overlay is shown — a dead camera must not pin
     *  the screen — even though [CameraPlayback] itself only toggles the flag on open/release. The
     *  same condition also gates the idle-screensaver keep-alive ticker: a Fatal overlay is exactly
     *  when the screensaver SHOULD be free to take over. */
    private fun applyKeepScreenOn() {
        val awake = keepScreenOnBase && lastLiveState !is LiveState.Fatal
        view?.keepScreenOn = awake
        mainHandler.removeCallbacks(idleKeepAliveRunnable)
        if (awake) mainHandler.post(idleKeepAliveRunnable)
    }

    private fun renderOverlay(state: LiveState) {
        connectOverlay?.visibility = View.GONE
        fatalOverlay?.visibility = View.GONE
        when (state) {
            LiveState.Connecting -> {
                connectOverlay?.visibility = View.VISIBLE
                connectText?.text = if (pendingStreamSwitch) {
                    getString(R.string.camera_switching_stream, getString(if (currentStream() == StreamChoice.MAIN) R.string.camera_stream_main else R.string.camera_stream_sub))
                } else getString(R.string.camera_connecting)
            }
            LiveState.Playing -> Unit
            is LiveState.Reconnecting -> {
                connectOverlay?.visibility = View.VISIBLE
                val remainingS = ((state.nextAtMs - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
                connectText?.text = getString(R.string.camera_reconnecting, state.attempt, remainingS)
            }
            is LiveState.Fatal -> {
                fatalOverlay?.visibility = View.VISIBLE
                val f = playback?.lastVideoFormat
                fatalText?.text = if (state.kind == StreamErrorKind.FATAL_UNSUPPORTED && f != null && !DeviceDecoders.canDecode(f.mime, f.width, f.height)) {
                    val res = if (currentStream() == StreamChoice.MAIN) R.string.camera_fatal_codec_main else R.string.camera_fatal_codec
                    getString(res, CodecNames.label(f.mime), f.width, f.height)
                } else getString(fatalMessageRes(state.kind))
            }
        }
        renderAudioButton()
    }

    /**
     * Paints the mute button from arbitration state. Hidden outright for a camera saved without
     * audio: [CameraPlayback] deselects its audio track at session start, so there is nothing the
     * button could unmute.
     */
    private fun renderAudioButton() {
        val button = audioButton ?: return
        val cam = cameraList.firstOrNull { it.id == currentCameraId }
        if (cam == null || !cam.audioEnabled) {
            button.visibility = View.GONE
            return
        }
        val on = arbState.cameraAudioOn
        button.visibility = View.VISIBLE
        button.setImageResource(if (on) R.drawable.ic_mdi_volume_high else R.drawable.ic_mdi_volume_off)
        button.imageTintList = androidx.core.content.ContextCompat.getColorStateList(
            requireContext(),
            if (on) R.color.accent_fallback else R.color.muted,
        )
        button.contentDescription = getString(
            if (on) R.string.camera_audio_mute_description else R.string.camera_audio_unmute_description,
        )
    }

    /**
     * Mutes or unmutes the open live view. Routed through the reducer rather than straight at the
     * player so that giving audio up also hands focus back and resumes the Spotify WE paused —
     * the same release path [ArbEvent.LiveClosed] takes.
     */
    private fun toggleAudio() {
        if (mode != Mode.LIVE) return
        val cam = cameraList.firstOrNull { it.id == currentCameraId } ?: return
        if (!cam.audioEnabled) return
        dispatch(ArbEvent.AudioToggled(wanted = !arbState.cameraAudioOn))
        restoreChrome()
    }

    private fun fatalMessageRes(kind: StreamErrorKind): Int = when (kind) {
        StreamErrorKind.FATAL_AUTH -> R.string.camera_fatal_auth
        StreamErrorKind.FATAL_NOT_FOUND -> R.string.camera_fatal_not_found
        StreamErrorKind.FATAL_UNSUPPORTED -> R.string.camera_fatal_unsupported
        StreamErrorKind.TRANSIENT -> R.string.camera_fatal_generic
    }

    private fun renderLiveIdentity(cam: CameraRecord) {
        liveName?.text = cam.name
        renderStreamToggle()
        renderOverlay(lastLiveState)
    }

    // ---- Sub / main stream ----------------------------------------------------------------------

    private fun currentStream(): StreamChoice = playback?.currentStream ?: StreamChoice.SUB

    private fun currentCameraHasMain(): Boolean =
        cameraList.firstOrNull { it.id == currentCameraId }?.mainRtspUrl.isNullOrBlank().not()

    private fun selectStream(next: StreamChoice) {
        if (mode != Mode.LIVE || next == currentStream()) return
        // Going up to MAIN needs a main URL; coming back down only needs to BE on MAIN — otherwise
        // clearing the main URL in Settings while live on it would strand the view there.
        if (next == StreamChoice.MAIN && !currentCameraHasMain()) return
        // Set BEFORE the call: switchStream publishes Connecting synchronously, and that render
        // is the one that has to read "Switching to …".
        pendingStreamSwitch = true
        playback?.switchStream(next)
        renderStreamToggle()
        restoreChrome()
    }

    private fun renderStreamToggle() {
        val has = currentCameraHasMain()
        streamToggle?.visibility = if (has) View.VISIBLE else View.GONE
        val on = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_fallback)
        val off = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.muted)
        val main = currentStream() == StreamChoice.MAIN
        streamSub?.setTextColor(if (main) off else on)
        streamMain?.setTextColor(if (main) on else off)
    }

    // ---- Live-view chrome fade ------------------------------------------------------------------

    private fun restoreChrome() {
        // Re-picked on every reveal rather than once at inflate: isInTouchMode flips at runtime
        // the first time a device is touched (or a key is pressed), and both paths land here.
        liveHint?.setText(
            when {
                view?.isInTouchMode == true -> R.string.camera_hint_touch
                currentCameraHasMain() -> R.string.camera_hint_row_streams
                else -> R.string.camera_hint_row
            },
        )
        setChromeVisible(true)
        mainHandler.removeCallbacks(hideChromeRunnable)
        mainHandler.postDelayed(hideChromeRunnable, CHROME_FADE_MS)
    }

    /**
     * Reveals or fades the whole live-view chrome group as one: the top row (badge, name, audio
     * state, back), the bottom hint, and the prev/next chevrons.
     *
     * Fading to alpha 0 is not enough on its own — a fully transparent ImageButton still consumes
     * the tap, so an invisible back button would silently swallow the very tap meant to bring the
     * chrome back. So a reveal sets the eligible views VISIBLE up front, and the fade-out ends by
     * setting them INVISIBLE. That completion is guarded by [chromeRevealed]: a late fade-out
     * landing after a newer reveal must not hide what was just shown.
     *
     * Eligibility is [chromeViews]'s job, not this one's — prev/next only exist as controls when
     * there is another camera to switch to.
     */
    private fun setChromeVisible(visible: Boolean) {
        chromeRevealed = visible
        val alpha = if (visible) 1f else 0f
        for (v in chromeViews()) {
            v.animate().cancel()
            if (visible) v.visibility = View.VISIBLE
            v.animate()
                .alpha(alpha)
                .setDuration(CHROME_ANIM_MS)
                .withEndAction { if (!chromeRevealed) v.visibility = View.INVISIBLE }
                .start()
        }
    }

    /** The views the chrome fade owns, with prev/next dropped when there is nothing to switch to.
     *  The Fatal overlay's Retry button is deliberately NOT here: it is the only thing on screen
     *  worth touching when a camera is dead, and must not fade out from under the user. */
    private fun chromeViews(): List<View> {
        val group = mutableListOf<View>()
        liveChrome?.let(group::add)
        // The bar, not the hint inside it: the snapshot button rides in the same row and has to
        // fade — and be made INVISIBLE — with it, or a transparent button keeps eating taps.
        liveBottomBar?.let(group::add)
        if (cameraList.size >= 2) {
            prevButton?.let(group::add)
            nextButton?.let(group::add)
        }
        return group
    }

    /**
     * Applies prev/next eligibility after the camera list changes, which can happen while a live
     * view is up (a camera added or deleted in settings). GONE rather than INVISIBLE so the
     * chevrons stop taking touches entirely; a still-eligible pair is left exactly as the current
     * chrome state has it, never independently forced visible.
     */
    private fun syncSwitchButtons() {
        val eligible = cameraList.size >= 2
        for (button in listOfNotNull(prevButton, nextButton)) {
            button.visibility = when {
                !eligible -> View.GONE
                chromeRevealed -> View.VISIBLE
                else -> View.INVISIBLE
            }
            button.alpha = if (eligible && chromeRevealed) 1f else 0f
        }
    }

    // ---- Camera switching (LEFT/RIGHT) -----------------------------------------------------------

    private fun switchCamera(delta: Int) {
        if (cameraList.size < 2) return
        val curId = currentCameraId ?: return
        val idx = cameraList.indexOfFirst { it.id == curId }
        if (idx < 0) return
        val nextIdx = (idx + delta + cameraList.size) % cameraList.size
        showLive(cameraList[nextIdx].id)
    }

    // ---- Snapshot (OK key / chrome button) ---------------------------------------------------------

    private fun saveSnapshot() {
        if (mode != Mode.LIVE) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastSnapshotAt < 1_000L) return
        lastSnapshotAt = now
        val cam = cameraList.firstOrNull { it.id == currentCameraId } ?: return
        val surface = playerView?.videoSurfaceView as? android.view.SurfaceView
        if (lastLiveState !is LiveState.Playing || surface == null || surface.width == 0) {
            android.widget.Toast.makeText(requireContext(), R.string.camera_snapshot_no_frame, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val f = playback?.lastVideoFormat
        val w = f?.width?.takeIf { it > 0 } ?: surface.width
        val h = f?.height?.takeIf { it > 0 } ?: surface.height
        // Throwable, not Exception: a MAIN 2560×1440 buffer is 14.7 MB and a 4K one 33 MB, so the
        // failure this has to survive is an OutOfMemoryError — an Error, which `catch (Exception)`
        // would let kill the process.
        val bitmap = try {
            android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        } catch (_: Throwable) {
            android.widget.Toast.makeText(requireContext(), R.string.camera_snapshot_failed, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val takenAt = System.currentTimeMillis()
        try {
            android.view.PixelCopy.request(surface, bitmap, { result ->
                if (result != android.view.PixelCopy.SUCCESS) {
                    bitmap.recycle()
                    context?.let { android.widget.Toast.makeText(it, R.string.camera_snapshot_failed, android.widget.Toast.LENGTH_SHORT).show() }
                    return@request
                }
                // The view (and its viewLifecycleOwner) can be torn down between submitting the
                // PixelCopy request and this callback firing later on mainHandler — e.g. the user
                // backs out to another feature right after pressing OK. Bail before touching either.
                val appCtx = if (view == null) null else context?.applicationContext
                if (appCtx == null) {
                    bitmap.recycle()
                    return@request
                }
                viewLifecycleOwner.lifecycleScope.launch {
                    val ok = try {
                        CameraSnapshotSaver.save(appCtx, bitmap, cam.name, takenAt)
                    } finally {
                        bitmap.recycle()
                    }
                    val ctx = context ?: return@launch
                    val text = if (ok) {
                        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(takenAt))
                        getString(R.string.camera_snapshot_saved, cam.name, time)
                    } else getString(R.string.camera_snapshot_failed)
                    android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_SHORT).show()
                }
            }, mainHandler)
        } catch (_: Throwable) {
            bitmap.recycle()
            android.widget.Toast.makeText(requireContext(), R.string.camera_snapshot_failed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Key routing (ShellKeyRouting hook: HomeActivity.dispatchKeyEvent -> KeyEventTarget) --------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (mode == Mode.GRID) {
            // Any key restarts the rotation countdown, so the page never flips out from under
            // someone who is actually driving the grid.
            if (event.action == KeyEvent.ACTION_DOWN) armPageRotation()
            if (pageCount() <= 1 || event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
            // GridLayoutManager's focus search dead-ends at a row edge; that failure is the hook
            // for flipping. Anything that is not an edge press returns false, so ordinary focus
            // movement inside the page still happens.
            // Focus already on a page number: ▲ hands it back to the tiles, and ◀ ▶ move along the
            // row and STOP at its ends — left to ordinary focus search, a press past the last
            // number escapes upward into whatever tile is nearest, and OK then opens that camera
            // (seen on the Echo Show). OK falls through to the number's own click listener.
            pageNumbers?.let { row ->
                val focused = row.focusedChild ?: return@let
                val at = row.indexOfChild(focused)
                return when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { focusBottomTileRow(); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { row.getChildAt(at - 1)?.requestFocus(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { row.getChildAt(at + 1)?.requestFocus(); true }
                    else -> false
                }
            }
            val pos = focusedTilePosition() ?: return false
            val col = pos % currentCols
            return when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (col == 0) { flipPage(-1); true } else false
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (col == currentCols - 1 || pos == visibleCameras().size - 1) { flipPage(1); true } else false
                // RecyclerView's focus search dead-ends inside itself rather than escaping to the
                // sibling row below, so leaving the grid downwards has to be done by hand — the
                // same shape of fix as the LEFT/RIGHT edge-flip above.
                KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (GridFit.isInBottomRow(pos, visibleCameras().size, currentCols)) {
                        focusSelectedPageNumber()
                    } else {
                        false
                    }
                else -> false
            }
        }
        if (mode != Mode.LIVE) return false
        if (event.action == KeyEvent.ACTION_DOWN) restoreChrome()
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                if (event.action == KeyEvent.ACTION_DOWN) showGrid()
                true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) switchCamera(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) switchCamera(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                // Swallowed when there IS a second stream, and also when we are stuck ON the main
                // stream of a camera whose main URL has since been cleared — ▲▼ must still get back.
                val switchable = currentCameraHasMain() || currentStream() == StreamChoice.MAIN
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && switchable) {
                    selectStream(if (currentStream() == StreamChoice.MAIN) StreamChoice.SUB else StreamChoice.MAIN)
                }
                switchable
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                // Only while playing: on a Fatal overlay OK must keep reaching the focused Retry button.
                if (lastLiveState !is LiveState.Playing) return false
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) saveSnapshot()
                true
            }
            else -> false
        }
    }

    // ---- Arbitration dispatch/execute -----------------------------------------------------------

    private fun dispatch(event: ArbEvent) {
        val (next, commands) = CameraArbitration.reduce(arbState, event)
        arbState = next
        commands.forEach(::execute)
        // Re-render the audio-state line after commands that can flip `cameraAudioOn`
        // (Enable/MuteCameraAudio). Guarded so a Spotify/DLNA edge observed while the grid is
        // showing (no live session open) is a no-op.
        if (mode == Mode.LIVE) renderOverlay(lastLiveState)
    }

    private fun execute(command: ArbCommand) {
        when (command) {
            ArbCommand.RequestFocus -> requestAudioFocus()
            ArbCommand.AbandonFocus -> abandonAudioFocus()
            ArbCommand.EnableCameraAudio -> playback?.setAudioActive(true)
            ArbCommand.MuteCameraAudio -> playback?.setAudioActive(false)
            ArbCommand.PauseSpotify -> NativeBridge.pause()
            ArbCommand.ResumeSpotify -> NativeBridge.play()
        }
    }

    private fun requestAudioFocus() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(audioFocusListener, mainHandler)
            .build()
        audioFocusRequest = request
        when (audioManager.requestAudioFocus(request)) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> dispatch(ArbEvent.FocusGranted)
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> dispatch(ArbEvent.FocusDenied)
            // AUDIOFOCUS_REQUEST_DELAYED: the listener callback resolves it later.
        }
    }

    private fun abandonAudioFocus() {
        audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        audioFocusRequest = null
    }

    // ---- Data / secrets --------------------------------------------------------------------------

    private fun secretsFor(cameraId: String): Pair<String?, String?> {
        val (userKey, passKey) = CameraStore.credentialKeys(cameraId)
        return secretStore.get(userKey) to secretStore.get(passKey)
    }

    private fun refreshCameraList() {
        cameraList = CameraStore.load(prefs)
        tilePaint.prune(cameraList.mapTo(HashSet()) { it.id })
        scheduler.setCameras(cameraList, frameGrabAllowed = frameGrabAllowedPref())
        // Before the notify: a shorter or longer list can change both the fitted column count and
        // the number of pages, and the adapter must be told about the new item count against them.
        relayoutGrid()
        adapter.notifyDataSetChanged()
        // A camera added or deleted while a live view is up changes whether there is anything to
        // switch TO, so the chevrons' eligibility is recomputed here rather than only on reveal.
        syncSwitchButtons()
        gridEmpty?.visibility = if (cameraList.isEmpty()) View.VISIBLE else View.GONE
        // A deleted camera must disappear from `GET /api/cameras`' states immediately, not a tick
        // later — the store is already the list's filter, but a stale "live" would still be odd.
        publishApiStates()
        publishStatuses(SystemClock.elapsedRealtime())
    }

    // ---- Behavior prefs (Task 12's CameraBehaviorPrefs) -------------------------------------------

    private fun refreshSecondsPref(): Int =
        prefs.getInt(CameraBehaviorPrefs.KEY_GRID_REFRESH_S, CameraBehaviorPrefs.DEFAULT_GRID_REFRESH_S)

    private fun frameGrabAllowedPref(): Boolean =
        prefs.getBoolean(CameraBehaviorPrefs.KEY_FRAME_GRAB_ENABLED, CameraBehaviorPrefs.DEFAULT_FRAME_GRAB_ENABLED)

    /** [SnapshotScheduler]'s constructor `refreshIntervalMs` — only meaningful while the loop is
     *  actually running (`refreshSecondsPref() > 0`); an "off" pref never starts it (see
     *  [onStart]/[rebuildSnapshotPipeline]), so this fallback value is inert in that case. */
    private fun effectiveRefreshIntervalMs(): Long {
        val seconds = refreshSecondsPref()
        return if (seconds > 0) seconds * 1000L else DEFAULT_REFRESH_INTERVAL_MS
    }

    /** Builds a fresh [CameraSnapshots] wired to the current [scheduler], with the tile-listener
     *  that repaints a tile whenever a fresh frame lands. Used both from [onViewCreated] and
     *  [rebuildSnapshotPipeline] (camera_grid_refresh_s changed mid-view) so the wiring lives in
     *  exactly one place. */
    private fun buildSnapshots(): CameraSnapshots {
        val snaps = CameraSnapshots(
            scope = viewLifecycleOwner.lifecycleScope,
            scheduler = scheduler,
            io = AndroidSnapshotIo(requireContext()),
            cameras = { cameraList },
            secrets = ::secretsFor,
        )
        snaps.setTileListener { cameraId ->
            // An ADAPTER position, so it must be searched in the adapter's own list: a global index
            // would be out of range (dropped) or point at an unrelated tile on any page but the
            // first, and PAYLOAD_STATE never hangs the bitmap — so those tiles would freeze on
            // their bind-time frame.
            val idx = visibleCameras().indexOfFirst { it.id == cameraId }
            // PAYLOAD_FRAME: a new bitmap to hang, plus the decorations that came with it. A
            // payload, never a bare notifyItemChanged, for the no-cross-fade reason on
            // [PAYLOAD_STATE].
            if (idx >= 0) adapter.notifyItemChanged(idx, PAYLOAD_FRAME)
        }
        // Published for the remote-control seam (read from HTTP pool threads); every assignment to
        // `snapshots` goes through here, so the two can never drift apart.
        apiSnapshots = snaps
        return snaps
    }

    /**
     * camera_grid_refresh_s changed while this view is alive: refreshIntervalMs is a
     * [SnapshotScheduler] constructor val, so re-arguing the existing instance isn't possible —
     * both the scheduler and the [CameraSnapshots] that captured it are torn down and rebuilt.
     * Tiles briefly show placeholders again (a fresh scheduler has no fetch history) until the new
     * loop (if the new value isn't "off") lands its first frame — acceptable for a settings change
     * that is rare and user-initiated.
     */
    private fun rebuildSnapshotPipeline() {
        snapshots?.stop()
        scheduler = SnapshotScheduler(refreshIntervalMs = effectiveRefreshIntervalMs())
        snapshots = buildSnapshots()
        // A fresh scheduler has no fetch history, so every tile's state changes back to NONE —
        // forget the old paint so the tick (and the range-change below) actually repaints them.
        tilePaint.prune(emptySet())
        scheduler.setCameras(cameraList, frameGrabAllowed = frameGrabAllowedPref())
        // A fresh SnapshotScheduler starts un-suspended (liveViewOpen/dlnaVideo default false),
        // which would silently drop whatever suspension the previous instance was holding — see
        // onStart, which does the same re-apply after its own start(). Must run before start()
        // so a suspended scheduler never dispatches a first tick.
        applySchedulerSuspension()
        if (refreshSecondsPref() > 0) snapshots?.start()
        // The adapter's own count, not the whole list: past getItemCount() is an over-notify.
        val shown = visibleCameras().size
        if (shown > 0) adapter.notifyItemRangeChanged(0, shown, PAYLOAD_STATE)
    }

    // ---- Grid tile binding ------------------------------------------------------------------------

    private class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val image: ImageView = view.findViewById(R.id.tileImage)
        val placeholder: ImageView = view.findViewById(R.id.tilePlaceholder)
        val dot: View = view.findViewById(R.id.tileDot)
        val name: TextView = view.findViewById(R.id.tileName)
        val offline: TextView = view.findViewById(R.id.tileOffline)
    }

    /** Full bind: the parts that only change when the CAMERA does, plus everything
     *  [paintTileState] paints. */
    private fun bindTile(holder: TileHolder, cam: CameraRecord) {
        holder.image.setImageBitmap(snapshots?.tiles?.get(cam.id))
        holder.name.text = cam.name
        holder.root.contentDescription = cam.name
        holder.root.setOnClickListener { showLive(cam.id) }
        paintTileState(holder, cam)
    }

    /** Text for an UNREACHABLE tile, null otherwise. Shared by the tile and the status relay. */
    private fun offlineTextFor(cameraId: String, state: TileState, now: Long): String? {
        if (state != TileState.UNREACHABLE) return null
        val since = scheduler.offlineSince(cameraId) ?: now
        return getString(R.string.camera_tile_offline_for, OfflineText.duration(now - since))
    }

    /**
     * Everything about a tile that moves on its own: the status dot's colour, the "Offline for
     * 12 min" sentence, and the placeholder / dimming that [TileState] drives.
     *
     * A failed fetch never reaches the tile listener ([CameraSnapshots] only reports a frame it
     * actually got), so the one-second tick is the ONLY thing that ever repaints a camera that has
     * stopped answering — without it the dot would stay green and the frame undimmed for as long
     * as the grid stayed up.
     *
     * The [tilePaint] update at the end is not redundant with the tick's own: this also runs on a
     * plain bind (a new frame, a scroll, a fresh holder), and the cache has to follow what was
     * actually painted or the next tick would skip a repaint the tile still needs.
     */
    private fun paintTileState(holder: TileHolder, cam: CameraRecord) {
        val now = SystemClock.elapsedRealtime()
        val state = scheduler.tileState(cam.id, now)
        holder.placeholder.visibility = if (state == TileState.NONE) View.VISIBLE else View.GONE
        holder.image.alpha = if (state == TileState.UNREACHABLE) 0.45f else 1f
        holder.dot.backgroundTintList = androidx.core.content.ContextCompat.getColorStateList(requireContext(), dotColorRes(state))
        val text = offlineTextFor(cam.id, state, now)
        holder.offline.visibility = if (text == null) View.GONE else View.VISIBLE
        holder.offline.text = text
        tilePaint.update(cam.id, state, text)
    }

    private fun dotColorRes(state: TileState): Int = when (state) {
        TileState.OK -> R.color.dot_green
        TileState.STALE -> R.color.dot_amber
        TileState.UNREACHABLE -> R.color.dot_red
        TileState.NONE -> R.color.dot_grey
    }

    private fun publishStatuses(now: Long) {
        CameraStatusRelay.publish(
            cameraList.associate { cam ->
                cam.id to CameraStatus(scheduler.tileState(cam.id, now), scheduler.offlineSince(cam.id))
            },
        )
    }

    // ---- Grid geometry, pages, rotation -----------------------------------------------------------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Recomputes columns and vertical centring from the current geometry, list and layout pref. */
    private fun relayoutGrid() {
        val g = grid ?: return
        // Called before the first layout pass (onViewCreated, an inset callback): without this the
        // fit would be computed from a zero-sized box. Re-post and do it once there is geometry.
        //
        // Only while the grid is actually showing. A cold summon (ControlService ->
        // PanelControlRelay -> FeatureNavigator.switchTo, which uses commitNow) creates this
        // fragment and goes LIVE inside a single main-thread message, so no traversal ever runs and
        // the grid's width stays 0 for the whole live session — and View.post has no delay, so an
        // unconditional re-post would spin the main thread flat out until the user came back.
        // setMode() re-runs this on the way back to GRID, which is what picks up the dropped chain.
        if (g.width == 0 || g.height == 0) {
            if (gridContainer?.visibility == View.VISIBLE) g.post { relayoutGrid() }
            return
        }
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val pad = dp(10)
        val gap = dp(12)
        // The shell parks its clock in the top-right corner over this feature, so the grid reserves
        // the same top strip the Home Assistant page does (shell_clock_clearance) instead of the
        // plain 10dp pad — otherwise the clock sits on the top-right tile's picture.
        val topPad = resources.getDimensionPixelSize(R.dimen.shell_clock_clearance)
        val w = g.width - (lastInsets.left + pad) - (lastInsets.right + pad)
        val h = g.height - (lastInsets.top + topPad) - (lastInsets.bottom + pad)
        val count = pageSize() ?: cameraList.size
        currentCols = GridFit.columns(count, w, h, gap, minCols = if (landscape) 2 else 1)
        // Pages centre by a FULL page so a short last page keeps its tiles where the others were.
        val extraTop = GridFit.topPaddingPx(count, currentCols, w, h, gap)
        (g.layoutManager as? GridLayoutManager)?.let { if (it.spanCount != currentCols) it.spanCount = currentCols }
        g.setPadding(lastInsets.left + pad, lastInsets.top + topPad + extraTop, lastInsets.right + pad, lastInsets.bottom + pad)
        // The page row is a sibling BELOW the grid, so it no longer sits inside the grid's
        // inset-aware padding the way the old overlaid dots did — it has to carry the bottom
        // window inset itself or a system nav bar covers the numbers.
        pageNumbers?.setPadding(0, 0, 0, lastInsets.bottom + dp(6))
        renderPageNumbers()
    }

    /**
     * Builds the numbered page row from [PageIndicator]. The numbers are focusable, unlike the
     * live view's chrome: they sit BELOW the grid in a real vertical stack, so ▼ off the bottom
     * tile row reaches them through ordinary focus search and OK jumps straight to a page. The
     * tiles' own LEFT/RIGHT edge-flip (see [onKeyEvent]) is unaffected.
     */
    private fun renderPageNumbers() {
        val row = pageNumbers ?: return
        val model = PageIndicator.model(cameraList.size, pageSize(), currentPage)
        row.visibility = if (model.visible && mode == Mode.GRID) View.VISIBLE else View.GONE
        if (!model.visible) {
            row.removeAllViews()
            return
        }
        if (row.childCount != model.labels.size) {
            row.removeAllViews()
            model.labels.forEachIndexed { index, label ->
                row.addView(
                    TextView(requireContext()).apply {
                        text = label
                        contentDescription = getString(R.string.camera_page_description, label)
                        textSize = 12f
                        gravity = android.view.Gravity.CENTER
                        minWidth = dp(28)
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        background = androidx.core.content.ContextCompat
                            .getDrawable(context, R.drawable.bg_page_number)
                        foreground = androidx.core.content.ContextCompat
                            .getDrawable(context, R.drawable.bg_page_number_focus)
                        isFocusable = true
                        isClickable = true
                        defaultFocusHighlightEnabled = false
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                        ).apply { marginStart = dp(4); marginEnd = dp(4) }
                        setOnClickListener { goToPage(index) }
                    },
                )
            }
        }
        val accent = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.accent_fallback)
        val idle = androidx.core.content.ContextCompat.getColor(requireContext(), R.color.surface_raised)
        for (i in 0 until row.childCount) {
            val pill = row.getChildAt(i) as TextView
            val on = i == model.selected
            pill.backgroundTintList = android.content.res.ColorStateList.valueOf(if (on) accent else idle)
            pill.setTextColor(
                androidx.core.content.ContextCompat.getColor(
                    requireContext(),
                    if (on) R.color.bg_base else R.color.muted,
                ),
            )
            pill.typeface = if (on) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
        }
    }

    /** Moves [delta] pages (wrapping), repaints, and lands focus on the first tile. */
    private fun flipPage(delta: Int) {
        val pages = pageCount()
        if (pages <= 1) return
        goToPage(((currentPage + delta) % pages + pages) % pages)
    }

    /**
     * Shows page [target] (clamped), repaints and lands focus on its first tile. Every page change
     * — edge-flip, rotation timer, or a tap/OK on a number — goes through here, so there is one
     * implementation of "change page" however it is reached.
     */
    private fun goToPage(target: Int) {
        val size = pageSize() ?: return
        val next = Pager.clamp(target, cameraList.size, size)
        if (Pager.pageCount(cameraList.size, size) <= 1) return
        currentPage = next
        // The paint cache is keyed by camera id, and those ids now sit in different positions — a
        // stale entry would leave the new page's tiles unpainted until their status next moved.
        tilePaint.prune(emptySet())
        adapter.notifyDataSetChanged()
        renderPageNumbers()
        // Only off a touchscreen: grabbing focus on a touch device pops a focus ring nobody asked for.
        // A page that does not fit (GridFit falls back to MAX_COLS and the grid scrolls) keeps its
        // scroll offset across notifyDataSetChanged, so reset it and ask for position 0 by name
        // rather than for whatever child happens to be attached first.
        if (view?.isInTouchMode == false) {
            grid?.scrollToPosition(0)
            grid?.post { grid?.layoutManager?.findViewByPosition(0)?.requestFocus() }
        }
        armPageRotation()
    }

    private fun armPageRotation() {
        mainHandler.removeCallbacks(pageRotationRunnable)
        val seconds = prefs.getInt(CameraBehaviorPrefs.KEY_PAGE_ROTATION_S, 0)
        if (seconds > 0 && pageSize() != null && mode == Mode.GRID) {
            mainHandler.postDelayed(pageRotationRunnable, seconds * 1_000L)
        }
    }

    /** Moves focus onto the current page's number. False when the row is not on screen to take it. */
    private fun focusSelectedPageNumber(): Boolean {
        val row = pageNumbers ?: return false
        if (row.visibility != View.VISIBLE) return false
        val target = row.getChildAt(currentPage.coerceIn(0, row.childCount - 1)) ?: return false
        return target.requestFocus()
    }

    /** Hands focus back from the page row to the grid's bottom row. */
    private fun focusBottomTileRow() {
        val size = visibleCameras().size
        if (size == 0) return
        val bottomRowStart = ((size - 1) / currentCols) * currentCols
        grid?.layoutManager?.findViewByPosition(bottomRowStart)?.requestFocus()
    }

    /** Adapter position of the focused tile, or null. */
    private fun focusedTilePosition(): Int? {
        val g = grid ?: return null
        val child = g.focusedChild ?: return null
        return g.findContainingViewHolder(child)?.bindingAdapterPosition?.takeIf { it >= 0 }
    }

    // ---- InsetAware / FocusRestorable --------------------------------------------------------------

    override fun onInsets(insets: WindowInsetsCompat) {
        lastInsets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        relayoutGrid()
        relayoutLiveBars()
    }

    /**
     * Pads the live view's bottom bar to the window insets + the shell cluster's inset, so the
     * snapshot button and the hint share a row with the floating settings / info / menu pills
     * (which the shell places at base pad + chrome_bar_margin from the inset edges). The bar is not
     * inset-padded by the shell (the live view is deliberately full-bleed), so it has to carry the
     * bars itself or a system nav bar covers it.
     */
    private fun relayoutLiveBars() {
        val inset = resources.getDimensionPixelSize(R.dimen.camera_live_bar_inset)
        liveBottomBar?.setPadding(
            lastInsets.left + inset, dp(8), lastInsets.right + inset, lastInsets.bottom + inset,
        )
    }

    override fun restoreFocus() {
        if (view?.isInTouchMode == true) return
        if (mode == Mode.LIVE) {
            if (lastLiveState is LiveState.Fatal) retryButton?.requestFocus() else liveContainer?.requestFocus()
        } else {
            grid?.getChildAt(0)?.requestFocus()
        }
    }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
        const val CHROME_FADE_MS = 4_000L
        const val CHROME_ANIM_MS = 200L
        // Kept in lockstep with CameraBehaviorPrefs.DEFAULT_GRID_REFRESH_S rather than duplicating
        // "10" as an independent literal.
        const val DEFAULT_REFRESH_INTERVAL_MS = CameraBehaviorPrefs.DEFAULT_GRID_REFRESH_S * 1000L
        const val IDLE_KEEP_ALIVE_MS = 15_000L

        /**
         * Repaint only what moves on its own (status dot, offline sentence, placeholder/dimming).
         *
         * A payload, never a payload-less change: the latter tells RecyclerView the whole item is
         * new, and DefaultItemAnimator answers that by cross-fading the holder — across a grid,
         * once a second, that reads as the whole thing blinking. A non-empty payload makes
         * canReuseUpdatedViewHolder() true, so the holder is repainted in place with no animation.
         */
        const val PAYLOAD_STATE = "state"

        /** A new frame landed: the bitmap, plus everything [PAYLOAD_STATE] covers. */
        const val PAYLOAD_FRAME = "frame"
    }
}
