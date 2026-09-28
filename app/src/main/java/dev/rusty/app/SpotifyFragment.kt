package dev.rusty.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.TransitionDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import coil.dispose
import coil.load
import com.google.android.material.card.MaterialCardView
import androidx.annotation.VisibleForTesting
import java.util.Locale
import java.util.TimeZone

/**
 * The Spotify feature's view: an immersive, appliance-style Now Playing screen with
 * transport controls, a connection status dot, and in-place Settings/Info bottom
 * sheets. A pure renderer — the app-global concerns (service lifecycle, notification
 * permission, receiver name/bitrate, window/immersive state) live on the shell
 * ([HomeActivity]); this fragment delegates control to it via [ShellHost].
 */
class SpotifyFragment : Fragment(), InsetAware, KeyEventTarget, ScreensaverExitTarget, ReceiverStateAware, FocusRestorable, ShellContribution {

    private val shell: ShellHost get() = requireActivity() as ShellHost

    private lateinit var prefs: SharedPreferences

    // Header
    private lateinit var statusDot: View
    private lateinit var statusName: TextView
    private lateinit var identitySuffix: TextView

    // Ambient / idle
    private lateinit var meshView: AmbientMeshView
    private lateinit var washImage: ImageView
    /** Darkens the background layers through a dissolve; nothing else ever touches its alpha. */
    private lateinit var transitionDim: View
    private lateinit var scrimView: View
    private lateinit var clockText: TextView
    private lateinit var idleGroup: View
    private lateinit var clockDateText: TextView
    private lateinit var idleStatusText: TextView

    // Playing
    private lateinit var eyebrowText: TextView
    private lateinit var titleText: TextView
    private lateinit var artistText: TextView
    private lateinit var elapsedText: TextView
    private lateinit var durationText: TextView
    private lateinit var progressBar: ProgressBarView

    /** Seek gestures + the optimistic position; Android-free and unit-tested, see its KDoc. */
    private val scrub = ProgressScrubModel { SystemClock.elapsedRealtime() }

    /** The debounced D-pad seek ([ProgressScrubModel.Effect.ArmCommit] re-arms it per press). */
    private val stepCommit = Runnable { runScrubEffect(scrub.onCommitTimer()) }
    private lateinit var albumArtCard: MaterialCardView
    private lateinit var playingInfo: View
    private lateinit var albumArtImage: ImageView
    private lateinit var albumGlyphText: TextView
    private lateinit var rootView: View
    private lateinit var contentLayer: View

    // Canvas
    private lateinit var canvasPlayer: CanvasPlayerView
    private var canvasController: CanvasController? = null
    /** Drives the Canvas layer through a track transition; shared with the lockscreen theme. */
    private var canvasLayer: CanvasLayer? = null
    private var canvasActive = false
    // True while the screensaver overlay covers us. The fragment stays RESUMED underneath it, so
    // without this both this view and the saver's CanvasTheme would hold a video codec at once.
    private var canvasCovered = false

    // Transport
    private lateinit var prevButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton

    private lateinit var bloom: BloomController

    // Local copy of the receiver name for rendering + broadcast fallbacks; the shell owns the
    // canonical value. Seeded from the shell and kept in sync by renderDashboardState.
    private var deviceName = DEFAULT_DEVICE_NAME
    private var dashboardState = ReceiverDashboardState.waiting(DEFAULT_DEVICE_NAME)
    private var loadedCoverUrl: String? = null
    private var artworkRequestId = 0

    // ---- The on-screen crossfade (see TrackTransition) ------------------------------------
    /** The incoming track's cover, stacked over [albumArtImage] and faded up by the dissolve. */
    private lateinit var albumArtIncoming: ImageView
    private var dissolve: ValueAnimator? = null
    private val accentBlend = ArgbEvaluator()
    /** True while a dissolve owns the title/artist: renders park the new track's words in
     *  [heldWords] instead of writing them, and the dissolve swaps them at its midpoint. */
    private var wordsHeld = false
    private var heldWords: Pair<String, String>? = null
    /** The incoming cover's colours, applied at the midpoint of the dissolve or the instant it is
     *  landed early — so an interrupted dissolve still leaves the new track's palette on screen. */
    private var pendingAccent: Int? = null
    private var pendingWash: Bitmap? = null
    /** Backstop: a cover load that never completes must not keep the outgoing words on screen. */
    private val wordHoldDeadline = Runnable { releaseWords() }

    private var firstRender = true

    // Last visual state we moved D-pad focus for, so we only re-home focus on an idle⇄active
    // edge (not on every per-second render, which would yank focus away from the user).
    private var lastFocusVisual: VisualState? = null
    private val handler = Handler(Looper.getMainLooper())

    private val store: ReceiverStateStore by lazy { RustyApp.from(requireContext()) }

    /** The store's status/playback observer (Task 12), replacing the two broadcast receivers. */
    private val storeListener = ReceiverStateStore.Listener { snapshot ->
        renderDashboardState(snapshot.state)
    }

    // The 1 Hz tick re-renders from the store's current state + live (extrapolated) position and
    // NEVER writes elapsed back to the store — it only updates this fragment's own render.
    private val playbackClockTick = object : Runnable {
        override fun run() {
            val state = store.snapshot.state
            if (state.isPlaybackClockRunning) {
                renderDashboardState(state.copy(elapsedMs = store.liveElapsedMs()))
            }
        }
    }
    /** Clock-format override; seeded from the device 12/24h setting on first run (see onCreate). */
    private var is24HourClock = false

    private val clockTickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = updateClock()
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        val is24 = is24HourClock
        val locale = resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        clockText.text = ClockFormat.time(now, is24, locale, zone)
        clockDateText.text = ClockFormat.date(now, locale, zone)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_spotify, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        deviceName = shell.currentDeviceName
        is24HourClock = prefs.getBoolean(
            KEY_TIME_FORMAT_24H,
            android.text.format.DateFormat.is24HourFormat(requireContext())
        )

        bindViews(view)

        // Seed from the store snapshot so a live session (e.g. the screen woke up
        // mid-playback) is preserved instead of resetting to a cold "starting".
        val cached = store.snapshot.state
        dashboardState = if (cached.sessionUser != null || cached.coverArtUrl != null) {
            cached.copy(receiverName = deviceName)
        } else {
            ReceiverDashboardState.starting(deviceName)
        }
        renderDashboardState(dashboardState)
        wireInteractions()

        canvasPlayer = view.findViewById(R.id.canvasPlayer)
        // Center-crop so the (vertical) Canvas loop fills the square album-art card completely in
        // both orientations — landscape used to FIT, which pillarboxed the 9:16 video inside the card.
        canvasPlayer.setFill(true)
        canvasLayer = CanvasLayer(canvasPlayer)

        val activity = requireActivity() as HomeActivity
        canvasController = CanvasController(
            store = store,
            fetcher = CanvasRepository.shared,
            tokenProvider = androidSpotifyTokenProvider(requireContext())::token,
            isEnabled = { activity.isCanvasEnabled },
            scope = viewLifecycleOwner.lifecycleScope,
        ).also { controller ->
            controller.addListener { state -> renderCanvas(state) }
        }
    }

    override fun onResume() {
        super.onResume()
        canvasController?.reevaluate()
    }

    override fun onStart() {
        super.onStart()
        // The status/playback consumption is now a store Listener (Task 12); addListener delivers
        // the current snapshot immediately, so this also catches up anything that changed while
        // the screen was stopped (the old explicit renderDashboardState(holder.current) catch-up).
        store.addListener(storeListener)
        requireContext().registerReceiver(clockTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        updateClock()
        bloom.onVisible()   // starts the mesh only if currently idle
        if (!canvasCovered) canvasController?.start()
    }

    override fun onStop() {
        handler.removeCallbacks(playbackClockTick)
        // A D-pad seek still waiting out its debounce is committed now rather than lost.
        handler.removeCallbacks(stepCommit)
        runScrubEffect(scrub.onCommitTimer())
        store.removeListener(storeListener)
        requireContext().unregisterReceiver(clockTickReceiver)
        bloom.onHidden()    // pauses the mesh while off-screen
        canvasController?.stop()
        canvasPlayer.clear()
        super.onStop()
    }

    override fun onDestroyView() {
        // Lands any dissolve first: it holds the words and a half-faded cover in THIS view.
        landDissolve()
        albumArtImage.dispose()
        artworkRequestId++
        // These caches describe what's currently rendered into THIS view's widgets, but the fragment
        // instance (and so these fields) outlives the view when hidden=CREATED tears the view down
        // (e.g. switching to Home Assistant). Reset them so the recreated view re-renders from
        // scratch — otherwise renderAlbumArt's `url == loadedCoverUrl` guard suppresses reloading the
        // cover into the fresh (empty) ImageView and the album art is lost on return.
        loadedCoverUrl = null
        lastFocusVisual = null
        firstRender = true
        scrub.reset()
        canvasController?.stop()
        canvasController = null
        canvasLayer?.reset()
        canvasLayer = null
        canvasPlayer.release()
        canvasActive = false
        super.onDestroyView()
    }

    /** True once cover art has been loaded into the live album-art ImageView. Lets instrumentation
     *  assert the art survives a hide→show view recreation (the loaded-guard reset contract). */
    @androidx.annotation.VisibleForTesting
    fun albumArtHasImageForTest(): Boolean =
        ::albumArtImage.isInitialized && view != null && albumArtImage.drawable != null

    /** Instrumentation: is the Canvas video layer currently shown over the art? */
    @VisibleForTesting
    fun canvasIsActiveForTest(): Boolean = canvasActive

    /** Instrumentation: does this fragment currently hold a Canvas video codec? */
    @VisibleForTesting
    fun canvasHasPlayerForTest(): Boolean =
        ::canvasPlayer.isInitialized && canvasPlayer.hasPlayerForTest

    /**
     * The screensaver started (or stopped) covering us. While covered we give up the Canvas codec
     * entirely rather than decode video nobody can see behind an opaque overlay — the saver's own
     * Canvas theme is the one on screen. Uncovering restarts the controller only if our view is at
     * least STARTED; otherwise [onStart] does it.
     */
    fun setCanvasCovered(covered: Boolean) {
        if (canvasCovered == covered) return
        canvasCovered = covered
        if (covered) {
            // Nothing of ours is visible under the saver; land the dissolve on its final frame.
            landDissolve()
            canvasController?.stop()
            if (::canvasPlayer.isInitialized) {
                canvasLayer?.reset()
                canvasPlayer.release()
            }
            canvasActive = false
        } else if (
            viewLifecycleOwnerLiveData.value?.lifecycle?.currentState
                ?.isAtLeast(Lifecycle.State.STARTED) == true
        ) {
            canvasController?.start()
        }
    }

    /**
     * Hands the resolver's state to [CanvasLayer] on the dissolve's own window, so the video is part
     * of the crossfade instead of being torn down before it: the outgoing loop is held and dipped
     * while the next URL resolves, swapped at the bottom of the dip, and brought back up. A track
     * with no Canvas fades the loop out over the same window, into the cover that is dissolving
     * underneath it.
     */
    private fun renderCanvas(state: CanvasState) {
        if (view == null) return
        canvasLayer?.apply(state, TrackTransition.durationMs(shell.currentCrossfadeSeconds))
        canvasActive = canvasLayer?.isShowing == true
    }

    /** Called by the shell after it changes receiver state (start/stop/rename) so this renderer
     *  catches up from the shared snapshot. Also refreshes the local device-name copy. */
    override fun onShellStateChanged() {
        deviceName = shell.currentDeviceName
        renderDashboardState(store.snapshot.state)
    }

    /**
     * The screensaver is crossfading out into us. If a track is now playing, snap the bloom to
     * IDLE (clock centered, hidden under the still-opaque overlay) and replay the animated bloom
     * so the centered clock flies to the corner as now-playing blooms — in sync with the
     * overlay crossfade. If we're idle, the idle face is already correct; nothing to morph.
     */
    override fun onReturnFromScreensaver(showMesh: Boolean) {
        // Read the authoritative store, not our own dashboardState: the screensaver controller's
        // exit fires off the store write, which the store delivers asynchronously AFTER this call —
        // so our field can still be the stale IDLE value at this instant.
        if (store.snapshot.state.visualState() == VisualState.ACTIVE) {
            bloom.resetToIdleInstant(showMesh)
            bloom.apply(VisualState.ACTIVE, animate = true)
        }
    }

    private fun bindViews(view: View) {
        rootView = view.findViewById(R.id.nowPlayingRoot)
        contentLayer = view.findViewById(R.id.contentLayer)
        statusDot = view.findViewById(R.id.viewStatusDot)
        statusName = view.findViewById(R.id.tvStatusName)
        identitySuffix = view.findViewById(R.id.tvIdentitySuffix)

        meshView = view.findViewById(R.id.viewAmbientMesh)
        washImage = view.findViewById(R.id.ivWash)
        transitionDim = view.findViewById(R.id.viewTransitionDim)
        scrimView = view.findViewById(R.id.viewScrim)
        // The clock is shell-owned now (it floats above every feature); the fragment only animates it
        // via its BloomController for the morph's lifetime.
        clockText = (requireActivity() as ShellHost).sharedClock()
        idleGroup = view.findViewById(R.id.idleGroup)
        clockDateText = view.findViewById(R.id.tvClockDate)
        idleStatusText = view.findViewById(R.id.tvIdleStatus)

        eyebrowText = view.findViewById(R.id.tvEyebrow)
        titleText = view.findViewById(R.id.tvFullTitle)
        artistText = view.findViewById(R.id.tvFullArtist)
        elapsedText = view.findViewById(R.id.tvFullElapsed)
        durationText = view.findViewById(R.id.tvFullDuration)
        progressBar = view.findViewById(R.id.viewFullProgress)
        albumArtCard = view.findViewById(R.id.albumArtCard)
        playingInfo = view.findViewById(R.id.playingInfo)
        albumArtImage = view.findViewById(R.id.ivFullAlbumArt)
        albumArtIncoming = view.findViewById(R.id.ivFullAlbumArtIncoming)
        albumGlyphText = view.findViewById(R.id.tvFullAlbumGlyph)

        prevButton = view.findViewById(R.id.btnPrev)
        playPauseButton = view.findViewById(R.id.btnPlayPause)
        nextButton = view.findViewById(R.id.btnNext)

        bloom = BloomController(
            clock = clockText,
            idleViews = listOf(idleGroup),
            activeViews = listOf(identitySuffix, albumArtCard, playingInfo),
            mesh = meshView,
            wash = washImage,
            scrim = scrimView
        )
    }

    private fun wireInteractions() {
        prevButton.setOnClickListener { NativeBridge.previousTrack() }
        nextButton.setOnClickListener { NativeBridge.nextTrack() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        albumArtCard.setOnClickListener { openLyrics() }

        // Seeking. The view reports ratios; the model decides what they mean; runScrubEffect does
        // the one Android thing (the native seek / the debounce timer) and redraws.
        progressBar.scrubListener = object : ProgressBarView.ScrubListener {
            override fun onScrubStart(ratio: Float) = runScrubEffect(scrub.onDragStart(ratio))
            override fun onScrubMove(ratio: Float) = runScrubEffect(scrub.onDragMove(ratio))
            override fun onScrubEnd(ratio: Float) = runScrubEffect(scrub.onDragEnd(ratio))
            override fun onScrubCancel() = runScrubEffect(scrub.onDragCancel())
        }
        progressBar.stepListener = ProgressBarView.StepListener { direction, repeatCount ->
            runScrubEffect(scrub.onStep(direction, repeatCount, store.liveElapsedMs()))
        }

        // The album-art card is the only way into the lyrics screen, so it must be reachable and
        // visibly focusable by a D-pad. MaterialCardView manages its own foreground (ripple), so a
        // generic focus drawable won't stick — toggle the card's own stroke instead, matching its
        // rounded shape and the brand-green ring used elsewhere.
        val focusStroke = (2 * resources.displayMetrics.density).toInt()
        val focusStrokeColor = ContextCompat.getColor(requireContext(), R.color.accent_fallback)
        albumArtCard.setOnFocusChangeListener { _, hasFocus ->
            albumArtCard.strokeColor = focusStrokeColor
            albumArtCard.strokeWidth = if (hasFocus) focusStroke else 0
        }
        // The clock tap (→ screensaver) and its focus-recolor are wired by the shell now that the
        // clock is shell-owned (HomeActivity.setupChrome).
    }

    /** Toggles playback — shared by the on-screen button and the remote's transport keys. */
    private fun togglePlayPause() {
        if (dashboardState.status == STATUS_PLAYING) NativeBridge.pause() else NativeBridge.play()
    }

    /**
     * Routes the TV remote's / headset's dedicated transport keys (PLAY/PAUSE/NEXT/PREVIOUS) to
     * playback, then routes D-pad focus onto/off the clock. Everything else — including center —
     * is left to the framework so normal focus traversal works (center still "clicks" the focused
     * control, e.g. toggling the clock-face overlay once the clock holds focus).
     */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (TvRemote.dispatchTransportKey(
                event,
                onPlayPause = { togglePlayPause() },
                onNext = { NativeBridge.nextTrack() },
                onPrevious = { NativeBridge.previousTrack() },
            )
        ) return true
        if (routeClockFocus(event)) return true
        return false
    }

    /**
     * The clock is animated into the top-right corner via scale + translation, so its layout rect
     * stays centered and the framework's focus search (even an explicit `nextFocus`) won't land on
     * it. Route it by hand. Vertical order on the active face, bottom to top: transport row →
     * progress bar → clock. UP from any transport button lands on the bar (this routing used to
     * jump straight to the clock, which would skip the bar now that it is focusable); UP from the
     * bar lands on the clock; DOWN off the clock returns to play/pause (DOWN off the bar is the
     * layout's `nextFocusDown`). LEFT/RIGHT on the bar are seek steps, consumed by the view.
     * Center is left native, so OK on the focused clock toggles the clock-face overlay (and OK
     * again, on the now-large clock, returns to now-playing).
     */
    private fun routeClockFocus(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (rootView.isInTouchMode || !clockText.isFocusable) return false
        val focusedId = requireActivity().currentFocus?.id
        val transportVisible = playingInfo.visibility == View.VISIBLE
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                val fromTransport = focusedId == R.id.btnPrev ||
                    focusedId == R.id.btnPlayPause || focusedId == R.id.btnNext
                if (fromTransport && transportVisible) return progressBar.requestFocus()
                // From settings/info while the clock-overlay is showing (transport hidden, so the
                // framework would otherwise lose focus trying to reach the transform-moved clock).
                val fromChromeInOverlay = !transportVisible &&
                    (focusedId == R.id.btnSettings || focusedId == R.id.btnInfo)
                if (focusedId == R.id.viewFullProgress || fromChromeInOverlay) return clockText.requestFocus()
            }
            KeyEvent.KEYCODE_DPAD_DOWN ->
                // Active face: DOWN off the clock returns to play/pause. In overlay mode the
                // transport is hidden, so DOWN falls through to the framework (→ settings below).
                if (focusedId == R.id.tvClock && transportVisible)
                    return playPauseButton.requestFocus()
        }
        return false
    }

    /** Opens the full-screen lyrics page for the current track (no-op if nothing is playing). */
    private fun openLyrics() {
        if (dashboardState.visualState() != VisualState.ACTIVE) return
        if (dashboardState.trackId.isNullOrBlank()) return
        startActivity(Intent(requireContext(), LyricsActivity::class.java))
        @Suppress("DEPRECATION")
        requireActivity().overridePendingTransition(R.anim.lyrics_slide_up_in, R.anim.now_playing_recede_out)
    }

    // ---- Rendering ----------------------------------------------------------

    /**
     * [anchorGeneration] tells the scrub model whether this render carries a NEW anchor (the
     * receiver confirmed a seek, changed track, paused…) or just the current one again (a 1 Hz
     * tick, a status change); it defaults to the store's, which is right for every caller.
     */
    private fun renderDashboardState(
        state: ReceiverDashboardState,
        anchorGeneration: Long = store.snapshot.anchor.generation,
    ) {
        dashboardState = state
        // Read-only renderer (Task 12): the fragment observes the store and never writes back, so a
        // stale render — including the 1 Hz tick's extrapolated elapsed — can never clobber the store.
        deviceName = state.receiverName

        statusName.text = state.receiverName
        durationText.text = state.durationLabel

        val visual = state.visualState()
        if (visual == VisualState.IDLE) {
            val (label, colorRes) = state.idleStatus()
            idleStatusText.text = label
            statusDot.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(requireContext(), colorRes))
        } else {
            val (_, dotColor) = statusInfo(state)
            statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        }

        playPauseButton.setImageResource(
            if (state.status == STATUS_PLAYING) R.drawable.ic_pause else R.drawable.ic_play
        )
        renderControlsEnabled(state)
        renderAlbumArt(state)
        // After renderAlbumArt, which is what arms the word hold when a dissolve starts.
        renderTrackWords(state)
        renderProgress(state, anchorGeneration)
        schedulePlaybackClockTick(state)
        rootView.keepScreenOn = state.isPlaybackClockRunning

        bloom.apply(visual, animate = !firstRender)
        firstRender = false

        // The clock enters the screensaver on tap — a playing-only action — so it joins the
        // D-pad focus order only while playback is active. Avoids a stray focus ring on the
        // genuinely-idle clock.
        clockText.isFocusable = visual == VisualState.ACTIVE
        homeFocusFor(visual)
    }

    /**
     * On an idle⇄active edge, parks D-pad focus on a sensible default — play/pause while playing,
     * the settings button while idle — so the focus ring is always present and never stranded on a
     * control that's about to disappear. No-op in touch mode, so phones/tablets are unaffected.
     */
    private fun homeFocusFor(visual: VisualState) {
        if (visual == lastFocusVisual) return
        lastFocusVisual = visual
        if (rootView.isInTouchMode) return
        // Settings now lives in the shell chrome cluster; resolve it across the activity view tree.
        val target: View? = when {
            visual == VisualState.ACTIVE -> playPauseButton
            else -> requireActivity().findViewById(R.id.btnSettings)
        }
        // Queue after bloom's posted show/hide so the target is visible (focusable) when we ask.
        target?.let { t -> rootView.post { t.requestFocus() } }
    }

    /**
     * Re-homes D-pad focus when this retained fragment is shown again after a feature switch
     * ([FocusRestorable]). The fragment instance survives the switch, so [lastFocusVisual] still
     * holds the value from when it was last visible — clearing it lets [homeFocusFor] re-park focus
     * for the CURRENT visual state instead of short-circuiting on the unchanged edge.
     */
    override fun restoreFocus() {
        if (!::rootView.isInitialized || rootView.isInTouchMode) return
        lastFocusVisual = null
        homeFocusFor(dashboardState.visualState())
    }

    /** Transport is actionable only once a controller is connected. */
    private fun renderControlsEnabled(state: ReceiverDashboardState) {
        val live = state.sessionUser != null || state.status == STATUS_PLAYING
        val alpha = if (live) 1f else 0.35f
        for (button in listOf(prevButton, playPauseButton, nextButton)) {
            button.isEnabled = live
            button.alpha = alpha
        }
    }

    /**
     * Loads cover art (Coil), then derives the accent + wash via ArtworkProcessor.
     *
     * A cover that replaces one already on screen dissolves over it instead of cutting: the load
     * goes into [albumArtIncoming] and [startDissolve] runs the blend once the palette is known.
     */
    private fun renderAlbumArt(state: ReceiverDashboardState) {
        val url = state.coverArtUrl
        if (url == loadedCoverUrl) return
        loadedCoverUrl = url
        // Two boundaries in quick succession (skip, skip): land the dissolve in flight first, so
        // its cover is the one the next dissolve fades over.
        landDissolve()

        if (url.isNullOrBlank()) {
            albumArtImage.setImageDrawable(null)
            albumGlyphText.visibility = View.VISIBLE
            washImage.setImageDrawable(null)
            applyAccent(DEFAULT_ACCENT)
            return
        }

        val dissolving = TrackTransition.shouldDissolve(
            hasOutgoingArt = albumArtImage.drawable != null,
            isActive = state.visualState() == VisualState.ACTIVE,
        )
        // From here the words on screen belong to the OUTGOING track until the dissolve's midpoint,
        // but never past the deadline: a cover that never loads must not hold them forever.
        wordsHeld = dissolving
        if (dissolving) {
            handler.postDelayed(
                wordHoldDeadline,
                TrackTransition.wordsLandByMs(shell.currentCrossfadeSeconds),
            )
        }
        val target = if (dissolving) albumArtIncoming else albumArtImage

        val req = ++artworkRequestId
        target.load(url) {
            // Coil's own fade would race ours; when we dissolve, the dissolve owns the fade.
            crossfade(!dissolving)
            allowHardware(false)
            listener(
                onError = { _, _ ->
                    albumGlyphText.visibility = View.VISIBLE
                    washImage.setImageDrawable(null)
                    applyAccent(DEFAULT_ACCENT)
                    // No incoming cover to blend into: the new track's words go on now.
                    landDissolve()
                },
                onSuccess = { _, result ->
                    albumGlyphText.visibility = View.GONE
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap == null) {
                        // Unreadable cover — never leave the words parked on the old track.
                        landDissolve()
                        return@listener
                    }
                    // Palette runs its pixel histogram on a worker thread and calls back on the
                    // main thread; the remaining accent math + 48px downscale are cheap to apply here.
                    Palette.from(bitmap).generate { palette ->
                        if (req != artworkRequestId) return@generate
                        if (view == null || viewLifecycleOwner.lifecycle.currentState < Lifecycle.State.STARTED) return@generate
                        val artwork = ArtworkProcessor.fromPalette(palette, bitmap, DEFAULT_ACCENT)
                        if (dissolving) {
                            startDissolve(artwork)
                        } else {
                            washImage.setImageBitmap(artwork.wash)
                            applyAccent(artwork.accent)
                        }
                    }
                }
            )
        }
    }

    /** The title/artist pair — or, while a dissolve owns them, the words it will swap in at its
     *  midpoint. Called after [renderAlbumArt], which is what arms the hold. */
    private fun renderTrackWords(state: ReceiverDashboardState) {
        if (wordsHeld) {
            heldWords = state.trackTitle to state.trackArtist
            return
        }
        titleText.text = state.trackTitle
        artistText.text = state.trackArtist
    }

    /**
     * The on-screen half of the crossfade, on one clock for exactly as long as the native player
     * overlaps the two tracks: the incoming cover fades up over the outgoing one, the palette wash
     * cross-fades under it, the accent tweens between the two covers' colours, and the title and
     * artist trade over the middle third. See [TrackTransition] for the timings.
     */
    private fun startDissolve(artwork: Artwork) {
        val duration = TrackTransition.durationMs(shell.currentCrossfadeSeconds)
        val fromAccent = AccentHolder.accent
        val toAccent = artwork.accent
        pendingAccent = toAccent
        pendingWash = artwork.wash

        // The wash cross-fades at the DRAWABLE level, so the bloom keeps owning the view's own
        // alpha (it fades the whole wash in and out on the idle⇄active edge).
        val outgoingWash = washImage.drawable ?: ColorDrawable(Color.TRANSPARENT)
        val washFade = TransitionDrawable(
            arrayOf(outgoingWash, BitmapDrawable(resources, artwork.wash))
        ).apply { isCrossFadeEnabled = true }
        washImage.setImageDrawable(washFade)
        washFade.startTransition(duration.toInt())

        // The words are only ours to animate if the deadline has not already put them on screen.
        val choreographWords = wordsHeld
        albumArtIncoming.alpha = 0f
        albumArtIncoming.visibility = View.VISIBLE
        dissolve = ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            // The choreography lives in TrackTransition; the animator only supplies the clock.
            interpolator = LinearInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                albumArtIncoming.alpha = TrackTransition.incomingAlpha(fraction)
                transitionDim.alpha = TrackTransition.dimAlpha(fraction)
                applyAccent(accentBlend.evaluate(fraction, fromAccent, toAccent) as Int)
                if (!choreographWords) return@addUpdateListener
                val wordAlpha = if (TrackTransition.wordsSwapped(fraction)) {
                    releaseWords()
                    TrackTransition.incomingWordAlpha(fraction)
                } else {
                    TrackTransition.outgoingWordAlpha(fraction)
                }
                titleText.alpha = wordAlpha
                artistText.alpha = wordAlpha
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = landDissolve()
            })
            start()
        }
    }

    /**
     * Lands the dissolve on its final frame: the incoming cover becomes the card's cover, the new
     * track's words and colours are the ones on screen, and every animated alpha is back at rest.
     * Idempotent, so it doubles as the cancel path — view teardown, the screensaver covering us, a
     * second track boundary, a cover that failed to load.
     */
    private fun landDissolve() {
        val running = dissolve
        dissolve = null
        running?.removeAllListeners()
        running?.cancel()

        if (!::albumArtIncoming.isInitialized) return
        albumArtIncoming.dispose()
        albumArtIncoming.drawable?.let { incoming ->
            albumArtImage.setImageDrawable(incoming)
            albumArtIncoming.setImageDrawable(null)
        }
        albumArtIncoming.alpha = 0f
        albumArtIncoming.visibility = View.INVISIBLE
        transitionDim.alpha = 0f

        pendingWash?.let { washImage.setImageBitmap(it) }
        pendingWash = null
        pendingAccent?.let { applyAccent(it) }
        pendingAccent = null

        releaseWords()
        titleText.alpha = 1f
        artistText.alpha = 1f
    }

    /** The parked words belong to the incoming track from here on. */
    private fun releaseWords() {
        handler.removeCallbacks(wordHoldDeadline)
        wordsHeld = false
        heldWords?.let { (title, artist) ->
            titleText.text = title
            artistText.text = artist
        }
        heldWords = null
    }

    /** Tints the accent-driven chrome: progress fill, play button, eyebrow. */
    private fun applyAccent(color: Int) {
        AccentHolder.accent = color
        progressBar.fillColor = color
        playPauseButton.backgroundTintList = ColorStateList.valueOf(color)
        eyebrowText.setTextColor(color)
    }

    private fun schedulePlaybackClockTick(state: ReceiverDashboardState) {
        handler.removeCallbacks(playbackClockTick)
        if (state.isPlaybackClockRunning) {
            handler.postDelayed(playbackClockTick, 1_000L)
        }
    }

    /**
     * Draws the store's position unless the scrub model says a gesture or an unconfirmed seek
     * owns the bar right now — then its preview wins. Either way this is a repaint, not a layout.
     */
    private fun renderProgress(state: ReceiverDashboardState, anchorGeneration: Long) {
        scrub.durationMs = state.durationMs
        val shownMs = if (scrub.acceptSnapshot(anchorGeneration)) {
            state.elapsedMs
        } else {
            scrub.previewMs ?: state.elapsedMs
        }
        renderPosition(shownMs)
    }

    /** The bar fill and the elapsed label for [elapsedMs] (duration from the scrub model). */
    private fun renderPosition(elapsedMs: Long) {
        elapsedText.text = ReceiverDashboardState.formatDuration(elapsedMs)
        progressBar.progress = scrub.msToRatio(elapsedMs)
    }

    /**
     * Executes what the scrub model asked for, then redraws the bar and label from its preview.
     * The seek goes to the native core; the store confirms it later through a re-anchored
     * PLAYING/PAUSED snapshot (a new [PlaybackAnchor.generation]), which releases the model's
     * optimistic hold in [renderProgress].
     */
    private fun runScrubEffect(effect: ProgressScrubModel.Effect) {
        when (effect) {
            is ProgressScrubModel.Effect.Seek ->
                NativeBridge.seek(effect.positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            is ProgressScrubModel.Effect.ArmCommit -> {
                handler.removeCallbacks(stepCommit)
                handler.postDelayed(stepCommit, effect.delayMs)
            }
            ProgressScrubModel.Effect.None -> Unit
        }
        renderPosition(scrub.previewMs ?: store.liveElapsedMs())
    }

    /** Maps receiver state to a (status label, dot color) pair for the now-playing header. */
    private fun statusInfo(state: ReceiverDashboardState): Pair<String, Int> = when {
        state.status == STATUS_PLAYING -> "Playing" to DOT_GREEN
        state.sessionUser != null -> (if (state.status == "Paused") "Paused" else "Connected") to DOT_AMBER
        state.status == "Starting" || state.status == "Restarting" -> "Starting" to DOT_GREY
        state.status == "Error" || state.status == "Unavailable" -> "Offline" to DOT_RED
        state.status == "Off" -> "Off" to DOT_GREY
        // Up and discoverable with no controller (Waiting/Stopped, no session): a calm
        // "Listening" in green, matching the clock face — not an alarming "Offline" red.
        else -> "Listening" to DOT_GREEN
    }

    // ---- Settings: clock format (driven by the shell-owned Screensaver panel) ----

    /**
     * Applies the clock-format override and re-renders the clock immediately (no restart). Called
     * by the shell's Screensaver settings panel; the shell owns persistence of `time_format_24h`,
     * so this only updates the live render. Relocated from the old in-fragment settings sheet.
     */
    override fun applyTimeFormat(is24Hour: Boolean) {
        is24HourClock = is24Hour
        updateClock()
    }

    // ---- Window insets (host-forwarded) ------------------------------------

    /**
     * The shell forwards the window insets here ([InsetAware]). The background layers
     * (mesh/wash/scrim/grain) fill the window edge-to-edge; only the foreground contentLayer is
     * padded by the system-bar insets + a base margin, so there's no inset border. When fullscreen
     * hides the bars the insets collapse to the base margin.
     */
    override fun onInsets(insets: WindowInsetsCompat) {
        val basePad = (BASE_PAD_DP * resources.displayMetrics.density).toInt()
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        contentLayer.setPadding(basePad + bars.left, basePad + bars.top, basePad + bars.right, basePad + bars.bottom)
    }

    private companion object {
        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_TIME_FORMAT_24H = "time_format_24h"
        private const val DEFAULT_DEVICE_NAME = "Rusty Speaker"

        private const val STATUS_PLAYING = "Playing"
        private const val BASE_PAD_DP = 22

        // Default accent (matches @color/accent_fallback).
        private val DEFAULT_ACCENT = 0xFF1DB954.toInt()

        // Status-dot palette.
        private val DOT_GREEN = 0xFF1DB954.toInt()
        private val DOT_AMBER = 0xFFE3B341.toInt()
        private val DOT_GREY = 0xFF8B949E.toInt()
        private val DOT_RED = 0xFFF85149.toInt()
    }
}
