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

    /** SystemClock.elapsedRealtime() a tile's frame last landed — mirrors the scheduler's private
     *  `lastOk` bookkeeping so the grid can render an age chip without a public accessor for it. */
    private val tileFetchedAt = HashMap<String, Long>()

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
    private var liveAudioState: TextView? = null
    private var liveHint: TextView? = null
    private var backButton: ImageButton? = null
    private var prevButton: ImageButton? = null
    private var nextButton: ImageButton? = null
    private var connectOverlay: LinearLayout? = null
    private var connectText: TextView? = null
    private var fatalOverlay: LinearLayout? = null
    private var fatalText: TextView? = null
    private var retryButton: MaterialButton? = null

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
            // Skip the rebind while LIVE: the grid isn't visible and re-binding age chips for a
            // camera list nobody can see is wasted work.
            //
            // PAYLOAD_STATE, never a bare notifyItemRangeChanged: a payload-less change tells
            // RecyclerView the whole item is new, and DefaultItemAnimator answers that by
            // cross-fading every holder. Once a second, across every tile, that reads as the whole
            // grid blinking. A non-empty payload makes canReuseUpdatedViewHolder() true, so the
            // holder is repainted in place with no animation at all.
            if (mode == Mode.GRID && cameraList.isNotEmpty()) {
                adapter.notifyItemRangeChanged(0, cameraList.size, PAYLOAD_STATE)
            }
            // NOT skipped while LIVE: the remote's camera list is polled from off-screen too, and
            // ages/failures keep moving whether or not anyone is looking at the grid.
            publishApiStates()
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
        liveAudioState = view.findViewById(R.id.cameraLiveAudioState)
        liveHint = view.findViewById(R.id.cameraLiveHint)
        backButton = view.findViewById(R.id.cameraBackButton)
        prevButton = view.findViewById(R.id.cameraPrevButton)
        nextButton = view.findViewById(R.id.cameraNextButton)
        connectOverlay = view.findViewById(R.id.cameraConnectOverlay)
        connectText = view.findViewById(R.id.cameraConnectText)
        fatalOverlay = view.findViewById(R.id.cameraFatalOverlay)
        fatalText = view.findViewById(R.id.cameraFatalText)
        retryButton = view.findViewById(R.id.cameraRetryButton)

        retryButton?.setOnClickListener { playback?.manualRetry() }
        liveContainer?.setOnClickListener { restoreChrome() }
        // The touch twins of the keys onKeyEvent already handles in LIVE mode. Same call, so
        // there is exactly one implementation of each action however it is reached.
        backButton?.setOnClickListener { showGrid() }
        prevButton?.setOnClickListener { switchCamera(-1) }
        nextButton?.setOnClickListener { switchCamera(1) }

        adapter = object : RecyclerView.Adapter<TileHolder>() {
            override fun getItemCount() = cameraList.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TileHolder {
                val itemView = LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_camera_tile, parent, false)
                return TileHolder(itemView)
            }
            override fun onBindViewHolder(holder: TileHolder, position: Int) = bindTile(holder, cameraList[position])

            /**
             * Partial rebind. Falls back to the full bind for an empty or unrecognised payload
             * list — RecyclerView merges payloads from coalesced notifications, and a list this
             * doesn't understand must never be treated as "nothing to do".
             */
            override fun onBindViewHolder(holder: TileHolder, position: Int, payloads: MutableList<Any>) {
                val cam = cameraList[position]
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
        grid?.layoutManager = GridLayoutManager(requireContext(), spanCountFor(resources.configuration.orientation))
        grid?.adapter = adapter
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
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        (grid?.layoutManager as? GridLayoutManager)?.spanCount = spanCountFor(newConfig.orientation)
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
        liveAudioState = null
        liveHint = null
        backButton = null
        prevButton = null
        nextButton = null
        connectOverlay = null
        connectText = null
        fatalOverlay = null
        fatalText = null
        retryButton = null
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
        applyMode()
        applySchedulerSuspension()
        publishApiStates()
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
                connectText?.text = getString(R.string.camera_connecting)
            }
            LiveState.Playing -> Unit
            is LiveState.Reconnecting -> {
                connectOverlay?.visibility = View.VISIBLE
                val remainingS = ((state.nextAtMs - SystemClock.elapsedRealtime()) / 1000L).coerceAtLeast(0L)
                connectText?.text = getString(R.string.camera_reconnecting, state.attempt, remainingS)
            }
            is LiveState.Fatal -> {
                fatalOverlay?.visibility = View.VISIBLE
                fatalText?.text = getString(fatalMessageRes(state.kind))
            }
        }
        val cam = cameraList.firstOrNull { it.id == currentCameraId }
        liveAudioState?.text = when {
            cam == null || !cam.audioEnabled -> ""
            arbState.cameraAudioOn -> getString(R.string.camera_audio_on)
            else -> getString(R.string.camera_audio_off)
        }
    }

    private fun fatalMessageRes(kind: StreamErrorKind): Int = when (kind) {
        StreamErrorKind.FATAL_AUTH -> R.string.camera_fatal_auth
        StreamErrorKind.FATAL_NOT_FOUND -> R.string.camera_fatal_not_found
        StreamErrorKind.FATAL_UNSUPPORTED -> R.string.camera_fatal_unsupported
        StreamErrorKind.TRANSIENT -> R.string.camera_fatal_generic
    }

    private fun renderLiveIdentity(cam: CameraRecord) {
        liveName?.text = cam.name
        renderOverlay(lastLiveState)
    }

    // ---- Live-view chrome fade ------------------------------------------------------------------

    private fun restoreChrome() {
        // Re-picked on every reveal rather than once at inflate: isInTouchMode flips at runtime
        // the first time a device is touched (or a key is pressed), and both paths land here.
        liveHint?.setText(
            if (view?.isInTouchMode == true) R.string.camera_hint_touch else R.string.camera_hint_row,
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
        liveHint?.let(group::add)
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

    // ---- Key routing (ShellKeyRouting hook: HomeActivity.dispatchKeyEvent -> KeyEventTarget) --------

    override fun onKeyEvent(event: KeyEvent): Boolean {
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
        tileFetchedAt.keys.retainAll(cameraList.mapTo(HashSet()) { it.id })
        scheduler.setCameras(cameraList, frameGrabAllowed = frameGrabAllowedPref())
        adapter.notifyDataSetChanged()
        // A camera added or deleted while a live view is up changes whether there is anything to
        // switch TO, so the chevrons' eligibility is recomputed here rather than only on reveal.
        syncSwitchButtons()
        gridEmpty?.visibility = if (cameraList.isEmpty()) View.VISIBLE else View.GONE
        // A deleted camera must disappear from `GET /api/cameras`' states immediately, not a tick
        // later — the store is already the list's filter, but a stale "live" would still be odd.
        publishApiStates()
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
     *  that repaints a tile's age chip whenever a fetch lands. Used both from [onViewCreated] and
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
            tileFetchedAt[cameraId] = SystemClock.elapsedRealtime()
            val idx = cameraList.indexOfFirst { it.id == cameraId }
            // PAYLOAD_FRAME: a new bitmap to hang, plus the decorations that came with it. Same
            // no-cross-fade reason as the grid tick above.
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
        scheduler.setCameras(cameraList, frameGrabAllowed = frameGrabAllowedPref())
        // A fresh SnapshotScheduler starts un-suspended (liveViewOpen/dlnaVideo default false),
        // which would silently drop whatever suspension the previous instance was holding — see
        // onStart, which does the same re-apply after its own start(). Must run before start()
        // so a suspended scheduler never dispatches a first tick.
        applySchedulerSuspension()
        if (refreshSecondsPref() > 0) snapshots?.start()
        if (cameraList.isNotEmpty()) adapter.notifyItemRangeChanged(0, cameraList.size, PAYLOAD_STATE)
    }

    // ---- Grid tile binding ------------------------------------------------------------------------

    private class TileHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view
        val image: ImageView = view.findViewById(R.id.tileImage)
        val placeholder: ImageView = view.findViewById(R.id.tilePlaceholder)
        val warning: ImageView = view.findViewById(R.id.tileWarning)
        val name: TextView = view.findViewById(R.id.tileName)
        val ageChip: TextView = view.findViewById(R.id.tileAgeChip)
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

    /**
     * Everything about a tile that moves on its own: the age chip, and the placeholder / warning /
     * dimming that [TileState] drives.
     *
     * All of it, not just the age chip — a tile going UNREACHABLE is the case that makes this
     * matter. A failed fetch never reaches the tile listener ([CameraSnapshots] only reports a
     * frame it actually got), so the one-second tick is the ONLY thing that ever repaints a camera
     * that has stopped answering. An age-only repaint would leave the warning icon and the dimmed
     * frame stale for as long as the grid stayed up.
     */
    private fun paintTileState(holder: TileHolder, cam: CameraRecord) {
        val now = SystemClock.elapsedRealtime()
        val state = scheduler.tileState(cam.id, now)
        holder.placeholder.visibility = if (state == TileState.NONE) View.VISIBLE else View.GONE
        holder.warning.visibility = if (state == TileState.UNREACHABLE) View.VISIBLE else View.GONE
        holder.image.alpha = if (state == TileState.UNREACHABLE) 0.5f else 1f

        val fetchedAt = tileFetchedAt[cam.id]
        if (fetchedAt == null) {
            holder.ageChip.visibility = View.GONE
        } else {
            holder.ageChip.visibility = View.VISIBLE
            val ageS = ((now - fetchedAt) / 1000L).coerceAtLeast(0L)
            holder.ageChip.text = getString(R.string.camera_tile_age_seconds, ageS)
            holder.ageChip.setTextColor(colorFor(state))
        }
    }

    private fun colorFor(state: TileState): Int = androidx.core.content.ContextCompat.getColor(
        requireContext(),
        when (state) {
            TileState.OK -> R.color.dot_green
            TileState.STALE -> R.color.dot_amber
            TileState.UNREACHABLE -> R.color.dot_red
            TileState.NONE -> R.color.dot_grey
        },
    )

    private fun spanCountFor(orientation: Int): Int =
        if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) 2 else 1

    // ---- InsetAware / FocusRestorable --------------------------------------------------------------

    override fun onInsets(insets: WindowInsetsCompat) {
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        grid?.setPadding(bars.left + 10, bars.top + 10, bars.right + 10, bars.bottom + 10)
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

        /** Repaint only what moves on its own (age chip, placeholder/warning/dimming). */
        const val PAYLOAD_STATE = "state"

        /** A new frame landed: the bitmap, plus everything [PAYLOAD_STATE] covers. */
        const val PAYLOAD_FRAME = "frame"
    }
}
