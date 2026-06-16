package dev.rusty.app

import android.Manifest
import android.app.Dialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.palette.graphics.Palette
import coil.load
import coil.transform.CircleCropTransformation
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import java.util.Locale
import java.util.TimeZone

/**
 * The app's home screen: an immersive, appliance-style Now Playing view with
 * transport controls, a connection status dot, and in-place Settings/Info bottom
 * sheets. Owns starting the foreground receiver service (formerly the dashboard's
 * job) and the notification-permission flow.
 */
class NowPlayingActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences

    // Header
    private lateinit var statusDot: View
    private lateinit var statusName: TextView
    private lateinit var identitySuffix: TextView
    private lateinit var settingsButton: ImageButton
    private lateinit var infoButton: ImageButton

    // Ambient / idle
    private lateinit var meshView: AmbientMeshView
    private lateinit var washImage: ImageView
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
    private lateinit var progressFillView: View
    private lateinit var progressFill: GradientDrawable
    private lateinit var albumArtCard: MaterialCardView
    private lateinit var playingInfo: View
    private lateinit var albumArtImage: ImageView
    private lateinit var albumGlyphText: TextView
    private lateinit var rootView: View
    private lateinit var contentLayer: View

    // Transport
    private lateinit var prevButton: ImageButton
    private lateinit var playPauseButton: ImageButton
    private lateinit var nextButton: ImageButton

    private lateinit var bloom: BloomController

    private var deviceName = DEFAULT_DEVICE_NAME
    private var bitrateKbps = DEFAULT_BITRATE_KBPS
    private var dashboardState = ReceiverDashboardState.waiting(DEFAULT_DEVICE_NAME)
    private var loadedCoverUrl: String? = null
    private var fullscreenEnabled = false

    private var settingsDialog: Dialog? = null
    private var firstRender = true

    // Last visual state we moved D-pad focus for, so we only re-home focus on an idle⇄active
    // edge (not on every per-second render, which would yank focus away from the user).
    private var lastFocusVisual: VisualState? = null
    /** Why the ambient clock is up (manual peek vs. pause drift), and the tap rule to dismiss it. */
    private val clockFace = ClockFaceState()
    private var infoDialog: Dialog? = null

    private lateinit var insetsController: WindowInsetsControllerCompat

    private val handler = Handler(Looper.getMainLooper())

    /** Running build's versionName (e.g. "1.1.0"), read once from the package manager. */
    private val appVersionName: String by lazy {
        try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private val playbackClockTick = object : Runnable {
        override fun run() {
            if (dashboardState.isPlaybackClockRunning) {
                renderDashboardState(dashboardState.advanceElapsedBy(1_000L))
            }
        }
    }
    private val autoHideBarsTick = Runnable {
        if (fullscreenEnabled) hideSystemBars()
    }

    // After a sustained pause we drift back to the ambient clock rather than holding the
    // now-playing face indefinitely. Armed on entering Paused, cancelled on any other state.
    private var pauseTimerArmed = false
    private val pauseToClockTick = Runnable {
        clockFace.onPauseTimeout()
        renderDashboardState(dashboardState)
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

    private val receiverEventReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ReceiverDashboardBroadcast.ACTION_STATUS -> handleStatusBroadcast(intent)
                ReceiverDashboardBroadcast.ACTION_PLAYBACK -> handlePlaybackBroadcast(intent)
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (isGranted) {
                startSpotifyService(deviceName)
            } else {
                renderDashboardState(
                    dashboardState.copy(
                        status = "Permission needed",
                        serviceLine = "Service: notification permission denied"
                    )
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_now_playing)

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        deviceName = prefs.getString(KEY_DEVICE_NAME, DEFAULT_DEVICE_NAME) ?: DEFAULT_DEVICE_NAME
        bitrateKbps = prefs.getInt(KEY_BITRATE_KBPS, DEFAULT_BITRATE_KBPS)
            .takeIf { it in SUPPORTED_BITRATES_KBPS }
            ?: DEFAULT_BITRATE_KBPS
        fullscreenEnabled = prefs.getBoolean(KEY_FULLSCREEN, false)
        is24HourClock = prefs.getBoolean(
            KEY_TIME_FORMAT_24H,
            android.text.format.DateFormat.is24HourFormat(this)
        )

        bindViews()
        setupFullscreen()

        // Seed from the shared snapshot so a live session (e.g. the screen woke up
        // mid-playback) is preserved instead of resetting to a cold "starting".
        val cached = DashboardStateHolder.current
        dashboardState = if (cached.sessionUser != null || cached.coverArtUrl != null) {
            cached.copy(receiverName = deviceName)
        } else {
            ReceiverDashboardState.starting(deviceName)
        }
        renderDashboardState(dashboardState)
        wireInteractions()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ReceiverDashboardBroadcast.ACTION_STATUS)
            addAction(ReceiverDashboardBroadcast.ACTION_PLAYBACK)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiverEventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiverEventReceiver, filter)
        }
        registerReceiver(clockTickReceiver, IntentFilter(Intent.ACTION_TIME_TICK))
        updateClock()
        bloom.onVisible()   // starts the mesh only if currently idle
        // Catch up to anything that changed while this screen was stopped.
        renderDashboardState(DashboardStateHolder.current)

        if (!serviceStartRequested) {
            serviceStartRequested = true
            checkPermissionsAndStartService()
        }
        if (fullscreenEnabled) {
            hideSystemBars()
            scheduleAutoHide()
        }
    }

    override fun onStop() {
        handler.removeCallbacks(playbackClockTick)
        handler.removeCallbacks(autoHideBarsTick)
        handler.removeCallbacks(pauseToClockTick)
        pauseTimerArmed = false
        unregisterReceiver(receiverEventReceiver)
        unregisterReceiver(clockTickReceiver)
        bloom.onHidden()    // pauses the mesh while off-screen
        super.onStop()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        // Re-hide the system bars a few seconds after any touch while in fullscreen
        // (the system reveals them transiently on a swipe).
        if (fullscreenEnabled) scheduleAutoHide()
    }

    private fun bindViews() {
        rootView = findViewById(R.id.nowPlayingRoot)
        contentLayer = findViewById(R.id.contentLayer)
        statusDot = findViewById(R.id.viewStatusDot)
        statusName = findViewById(R.id.tvStatusName)
        identitySuffix = findViewById(R.id.tvIdentitySuffix)
        settingsButton = findViewById(R.id.btnSettings)
        infoButton = findViewById(R.id.btnInfo)

        meshView = findViewById(R.id.viewAmbientMesh)
        washImage = findViewById(R.id.ivWash)
        scrimView = findViewById(R.id.viewScrim)
        clockText = findViewById(R.id.tvClock)
        idleGroup = findViewById(R.id.idleGroup)
        clockDateText = findViewById(R.id.tvClockDate)
        idleStatusText = findViewById(R.id.tvIdleStatus)

        eyebrowText = findViewById(R.id.tvEyebrow)
        titleText = findViewById(R.id.tvFullTitle)
        artistText = findViewById(R.id.tvFullArtist)
        elapsedText = findViewById(R.id.tvFullElapsed)
        durationText = findViewById(R.id.tvFullDuration)
        progressFillView = findViewById(R.id.viewFullProgressFill)
        progressFill = (progressFillView.background as GradientDrawable).mutate() as GradientDrawable
        albumArtCard = findViewById(R.id.albumArtCard)
        playingInfo = findViewById(R.id.playingInfo)
        albumArtImage = findViewById(R.id.ivFullAlbumArt)
        albumGlyphText = findViewById(R.id.tvFullAlbumGlyph)

        prevButton = findViewById(R.id.btnPrev)
        playPauseButton = findViewById(R.id.btnPlayPause)
        nextButton = findViewById(R.id.btnNext)

        bloom = BloomController(
            root = contentLayer,
            clock = clockText,
            idleViews = listOf(idleGroup),
            activeViews = listOf(identitySuffix, albumArtCard, playingInfo),
            mesh = meshView,
            wash = washImage,
            scrim = scrimView
        )
    }

    private fun wireInteractions() {
        settingsButton.setOnClickListener { showSettingsSheet() }
        infoButton.setOnClickListener { showInfoSheet() }
        prevButton.setOnClickListener { NativeBridge.previousTrack() }
        nextButton.setOnClickListener { NativeBridge.nextTrack() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        clockText.setOnClickListener { toggleClockOverride() }
        albumArtCard.setOnClickListener { openLyrics() }

        // The album-art card is the only way into the lyrics screen, so it must be reachable and
        // visibly focusable by a D-pad. MaterialCardView manages its own foreground (ripple), so a
        // generic focus drawable won't stick — toggle the card's own stroke instead, matching its
        // rounded shape and the brand-green ring used elsewhere.
        val focusStroke = (2 * resources.displayMetrics.density).toInt()
        val focusStrokeColor = ContextCompat.getColor(this, R.color.accent_fallback)
        albumArtCard.setOnFocusChangeListener { _, hasFocus ->
            albumArtCard.strokeColor = focusStrokeColor
            albumArtCard.strokeWidth = if (hasFocus) focusStroke else 0
        }

        // The clock animates down to ~0.22 scale in the corner, so its foreground ring shrinks to
        // near-invisible there — recolor the digits to the brand green on focus instead, a cue that
        // reads clearly at any scale (and still works when the clock is full-size in overlay mode).
        val clockInk = ContextCompat.getColor(this, R.color.ink)
        val clockFocused = ContextCompat.getColor(this, R.color.accent_fallback)
        clockText.setOnFocusChangeListener { _, hasFocus ->
            clockText.setTextColor(if (hasFocus) clockFocused else clockInk)
        }
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
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (TvRemote.dispatchTransportKey(
                event,
                onPlayPause = { togglePlayPause() },
                onNext = { NativeBridge.nextTrack() },
                onPrevious = { NativeBridge.previousTrack() },
            )
        ) return true
        if (routeClockFocus(event)) return true
        return super.dispatchKeyEvent(event)
    }

    /**
     * The clock is animated into the top-right corner via scale + translation, so its layout rect
     * stays centered and the framework's focus search (even an explicit `nextFocus`) won't land on
     * it. Route it by hand: D-pad UP from any transport button moves focus onto the clock; DOWN off
     * it returns to play/pause. Center is left native, so OK on the focused clock toggles the
     * clock-face overlay (and OK again, on the now-large clock, returns to now-playing).
     */
    private fun routeClockFocus(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount != 0) return false
        if (rootView.isInTouchMode || !clockText.isFocusable) return false
        val focusedId = currentFocus?.id
        val transportVisible = playingInfo.visibility == View.VISIBLE
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // UP lands on the (corner) clock from the transport row on the active face, and
                // from settings/info while the clock-overlay is showing (transport hidden, so the
                // framework would otherwise lose focus trying to reach the transform-moved clock).
                val fromTransport = focusedId == R.id.btnPrev ||
                    focusedId == R.id.btnPlayPause || focusedId == R.id.btnNext
                val fromChromeInOverlay = !transportVisible &&
                    (focusedId == R.id.btnSettings || focusedId == R.id.btnInfo)
                if (fromTransport || fromChromeInOverlay) return clockText.requestFocus()
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
        startActivity(Intent(this, LyricsActivity::class.java))
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.lyrics_slide_up_in, R.anim.now_playing_recede_out)
    }

    // ---- Broadcast handling -------------------------------------------------

    private fun handleStatusBroadcast(intent: Intent) {
        val lifecycle = ReceiverDashboardStatusEvent.Lifecycle.fromWireName(
            intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_LIFECYCLE)
        ) ?: return
        val event = ReceiverDashboardStatusEvent(
            receiverName = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_RECEIVER_NAME) ?: deviceName,
            lifecycle = lifecycle,
            message = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_MESSAGE),
            sessionUser = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_USER),
            sessionDisplayName = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_DISPLAY_NAME),
            sessionAvatarUrl = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_AVATAR_URL)
        )
        renderDashboardState(event.toDashboardState(dashboardState))
    }

    private fun handlePlaybackBroadcast(intent: Intent) {
        val playbackState = ReceiverDashboardPlaybackEvent.PlaybackState.fromWireName(
            intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_PLAYBACK_STATE)
        ) ?: return
        val event = ReceiverDashboardPlaybackEvent(
            receiverName = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_RECEIVER_NAME) ?: deviceName,
            playbackState = playbackState,
            trackTitle = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_TRACK_TITLE),
            trackArtist = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_TRACK_ARTIST),
            coverArtUrl = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_COVER_URL),
            trackId = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_TRACK_ID),
            sessionUser = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_USER),
            sessionDisplayName = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_DISPLAY_NAME),
            sessionAvatarUrl = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_SESSION_AVATAR_URL),
            elapsedMs = intent.getLongExtra(ReceiverDashboardBroadcast.EXTRA_ELAPSED_MS, 0L),
            durationMs = intent.getLongExtra(ReceiverDashboardBroadcast.EXTRA_DURATION_MS, 0L),
            queueTitle = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_QUEUE_TITLE),
            queueArtist = intent.getStringExtra(ReceiverDashboardBroadcast.EXTRA_QUEUE_ARTIST),
            queueUnavailable = intent.getBooleanExtra(ReceiverDashboardBroadcast.EXTRA_QUEUE_UNAVAILABLE, true)
        )
        renderDashboardState(event.toDashboardState(dashboardState))
    }

    // ---- Rendering ----------------------------------------------------------

    private fun renderDashboardState(state: ReceiverDashboardState) {
        dashboardState = state
        DashboardStateHolder.current = state
        deviceName = state.receiverName

        statusName.text = state.receiverName
        titleText.text = state.trackTitle
        artistText.text = state.trackArtist
        elapsedText.text = state.elapsedLabel
        durationText.text = state.durationLabel

        val realVisual = state.visualState()
        if (realVisual == VisualState.IDLE) clockFace.reset()
        managePauseTimer(state)
        val visual = if (clockFace.showingClock) VisualState.IDLE else realVisual
        if (visual == VisualState.IDLE) {
            val (label, colorRes) = state.idleStatus()
            idleStatusText.text = label
            statusDot.backgroundTintList =
                ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
        } else {
            val (_, dotColor) = statusInfo(state)
            statusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        }

        playPauseButton.setImageResource(
            if (state.status == STATUS_PLAYING) R.drawable.ic_pause else R.drawable.ic_play
        )
        renderControlsEnabled(state)
        renderAlbumArt(state)
        renderProgress(state)
        schedulePlaybackClockTick(state)
        rootView.keepScreenOn = state.isPlaybackClockRunning

        bloom.apply(visual, animate = !firstRender)
        firstRender = false

        // The clock toggles the playback clock-face overlay — a playing-only action — so it joins
        // the D-pad focus order whenever playback is active (using realVisual, not the override-
        // adjusted visual, so it stays reachable to toggle the overlay back OFF). Avoids a stray
        // focus ring on the genuinely-idle clock.
        clockText.isFocusable = realVisual == VisualState.ACTIVE
        homeFocusFor(visual)

        infoDialog?.takeIf { it.isShowing }?.let { bindInfoSheet(it, state) }
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
        val target = when {
            visual == VisualState.ACTIVE -> playPauseButton
            // Clock-overlay mode (playing, but showing the big clock): keep focus on the clock so
            // OK toggles it straight back off — the transport is hidden, so there's nowhere else.
            clockFace.showingClock -> clockText
            else -> settingsButton
        }
        // Queue after bloom's posted show/hide so the target is visible (focusable) when we ask.
        rootView.post { target.requestFocus() }
    }

    /** Gives a freshly-opened sheet's first control D-pad focus (no-op when opened by touch). */
    private fun requestInitialFocus(target: View) {
        if (target.isInTouchMode) return
        target.post { target.requestFocus() }
    }

    /**
     * Arms a one-shot timer when playback enters Paused; once it fires we treat the screen as
     * idle and drift to the clock. Any non-paused state cancels it and clears the timeout.
     */
    private fun managePauseTimer(state: ReceiverDashboardState) {
        if (state.status == "Paused") {
            if (!pauseTimerArmed) {
                pauseTimerArmed = true
                handler.postDelayed(pauseToClockTick, PAUSE_TO_CLOCK_MS)
            }
        } else {
            cancelPauseTimer()
        }
    }

    private fun cancelPauseTimer() {
        handler.removeCallbacks(pauseToClockTick)
        pauseTimerArmed = false
        clockFace.clearPausedDrift()
    }

    /**
     * Tap the clock to peek the ambient clock face during playback; tap again to return. A tap
     * that wakes the screen from the automatic pause drift also restarts the drift countdown so
     * it can drift again after another idle stretch.
     */
    private fun toggleClockOverride() {
        if (dashboardState.visualState() != VisualState.ACTIVE) return
        if (clockFace.onTap()) {
            handler.removeCallbacks(pauseToClockTick)
            pauseTimerArmed = false   // re-render's managePauseTimer re-arms a fresh countdown
        }
        renderDashboardState(dashboardState)
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

    /** Loads cover art (Coil), then derives the accent + wash via ArtworkProcessor. */
    private fun renderAlbumArt(state: ReceiverDashboardState) {
        val url = state.coverArtUrl
        if (url == loadedCoverUrl) return
        loadedCoverUrl = url

        if (url.isNullOrBlank()) {
            albumArtImage.setImageDrawable(null)
            albumGlyphText.visibility = View.VISIBLE
            washImage.setImageDrawable(null)
            applyAccent(DEFAULT_ACCENT)
            return
        }

        albumArtImage.load(url) {
            crossfade(true)
            allowHardware(false)
            listener(
                onError = { _, _ ->
                    albumGlyphText.visibility = View.VISIBLE
                    washImage.setImageDrawable(null)
                    applyAccent(DEFAULT_ACCENT)
                },
                onSuccess = { _, result ->
                    albumGlyphText.visibility = View.GONE
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@listener
                    // Palette runs its pixel histogram on a worker thread and calls back on the
                    // main thread; the remaining accent math + 48px downscale are cheap to apply here.
                    Palette.from(bitmap).generate { palette ->
                        if (url != loadedCoverUrl) return@generate
                        val artwork = ArtworkProcessor.fromPalette(palette, bitmap, DEFAULT_ACCENT)
                        washImage.setImageBitmap(artwork.wash)
                        applyAccent(artwork.accent)
                    }
                }
            )
        }
    }

    /** Tints the accent-driven chrome: progress fill, play button, eyebrow. */
    private fun applyAccent(color: Int) {
        AccentHolder.accent = color
        progressFill.setColor(color)
        playPauseButton.backgroundTintList = ColorStateList.valueOf(color)
        eyebrowText.setTextColor(color)
    }

    private fun schedulePlaybackClockTick(state: ReceiverDashboardState) {
        handler.removeCallbacks(playbackClockTick)
        if (state.isPlaybackClockRunning) {
            handler.postDelayed(playbackClockTick, 1_000L)
        }
    }

    private fun renderProgress(state: ReceiverDashboardState) {
        val ratio = if (state.durationMs > 0L) {
            (state.elapsedMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        progressFillView.post {
            val parentWidth = (progressFillView.parent as? View)?.width ?: 0
            progressFillView.layoutParams = progressFillView.layoutParams.apply {
                width = (parentWidth * ratio).toInt().coerceAtLeast(0)
            }
        }
    }

    /** Maps receiver state to a (status label, dot color) pair for the header and info card. */
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

    // ---- Settings sheet -----------------------------------------------------

    private fun showSettingsSheet() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_settings, null)
        val dialog = createCardDialog(view)

        val nameValue = view.findViewById<TextView>(R.id.tvReceiverNameValue)
        val changeButton = view.findViewById<MaterialButton>(R.id.btnChangeName)
        val editRow = view.findViewById<View>(R.id.rowReceiverNameEdit)
        val nameInput = view.findViewById<TextInputEditText>(R.id.etReceiverName)
        val saveButton = view.findViewById<MaterialButton>(R.id.btnSaveName)
        val bitrateSlider = view.findViewById<Slider>(R.id.sliderBitrate)
        val bitrateValue = view.findViewById<TextView>(R.id.tvBitrateValue)
        val fullscreenSwitch = view.findViewById<SwitchMaterial>(R.id.switchFullscreen)
        val timeFormatSwitch = view.findViewById<SwitchMaterial>(R.id.switchTimeFormat)
        val feedback = view.findViewById<TextView>(R.id.tvSettingsFeedback)
        val serviceStatusValue = view.findViewById<TextView>(R.id.tvReceiverStatusValue)
        val toggleServiceButton = view.findViewById<MaterialButton>(R.id.btnToggleService)

        nameValue.text = deviceName
        nameInput.setText(deviceName)
        bitrateSlider.value = bitrateToIndex(bitrateKbps)
        bitrateValue.text = bitrateLabel(bitrateKbps)
        fullscreenSwitch.isChecked = fullscreenEnabled
        timeFormatSwitch.isChecked = is24HourClock

        fun renderServiceToggle() {
            val isOff = dashboardState.status == "Off"
            toggleServiceButton.text = if (isOff) "Start" else "Stop"
            serviceStatusValue.text =
                if (isOff) "Off" else "Running · listening for Spotify"
        }
        renderServiceToggle()

        fun hideNameKeyboard() {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(nameInput.windowToken, 0)
            nameInput.clearFocus()
        }

        // Reveal the editor AND immediately drop the user into typing: focus the field,
        // place the caret at the end, and pop the soft keyboard. So tapping "Change" goes
        // straight to input — then Done/Enter or Save commits, no extra tap to open the IME.
        fun showNameKeyboard() {
            nameInput.requestFocus()
            nameInput.setSelection(nameInput.text?.length ?: 0)
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(nameInput, InputMethodManager.SHOW_IMPLICIT)
        }

        changeButton.setOnClickListener {
            val reveal = editRow.visibility != View.VISIBLE
            editRow.visibility = if (reveal) View.VISIBLE else View.GONE
            if (reveal) {
                nameInput.setText(deviceName)
                // Post so the row is laid out/visible before we request focus + IME.
                nameInput.post { showNameKeyboard() }
            } else {
                hideNameKeyboard()
            }
        }

        // Commit the typed name. Shared by the Save button and the keyboard's Done/Enter
        // action so either path renames AND dismisses the keyboard. The empty case keeps
        // the keyboard up so the user can type a name; the others close the editor.
        fun commitName() {
            val newName = nameInput.text?.toString()?.trim().orEmpty()
            when {
                newName.isEmpty() ->
                    showFeedback(feedback, "Enter a receiver name first.", FEEDBACK_NEUTRAL)
                newName == deviceName -> {
                    hideNameKeyboard()
                    showFeedback(feedback, "No change — already “$deviceName”.", FEEDBACK_NEUTRAL)
                }
                else -> {
                    applyRename(newName, feedback)
                    nameValue.text = newName
                    editRow.visibility = View.GONE
                    hideNameKeyboard()
                }
            }
        }

        saveButton.setOnClickListener { commitName() }
        toggleServiceButton.setOnClickListener {
            if (dashboardState.status == "Off") {
                // Re-start: reuses the permission-gated start path. Bypasses the
                // once-per-process auto-start guard, which is intentional — the user
                // explicitly asked to start it again.
                checkPermissionsAndStartService()
            } else {
                // stopService routes through SpotifyService.onDestroy (clean teardown,
                // publishes OFF). Render OFF immediately so the sheet/header update
                // without waiting for the broadcast round-trip.
                stopService(Intent(this, SpotifyService::class.java))
                renderDashboardState(ReceiverDashboardState.off(deviceName))
            }
            renderServiceToggle()
        }
        // Commit on the IME "Done" action (soft keyboard) and on a hardware/Bluetooth
        // Enter key, which arrives as a KEYCODE_ENTER event with an unspecified action id
        // rather than IME_ACTION_DONE. Gate on ACTION_DOWN so it fires once per press.
        nameInput.setOnEditorActionListener { _, actionId, event ->
            val enterKeyDown = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (actionId == EditorInfo.IME_ACTION_DONE || enterKeyDown) {
                commitName()
                true
            } else {
                false
            }
        }

        bitrateSlider.addOnChangeListener { _, value, _ ->
            bitrateValue.text = bitrateLabel(indexToBitrate(value))
        }
        bitrateSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val selected = indexToBitrate(slider.value)
                if (selected != bitrateKbps) applyBitrate(selected, feedback)
            }
        })

        fullscreenSwitch.setOnCheckedChangeListener { _, isChecked -> setFullscreen(isChecked) }
        timeFormatSwitch.setOnCheckedChangeListener { _, isChecked -> setTimeFormat(isChecked) }

        dialog.setOnDismissListener {
            settingsDialog = null
            if (fullscreenEnabled) hideSystemBars()
        }
        settingsDialog = dialog
        dialog.show()
        requestInitialFocus(toggleServiceButton)
    }

    /**
     * Renames the receiver.
     *
     * While a phone is connected, librespot 0.8 cannot rename the live Connect session in
     * place — the session's name is fixed when it's created and the device reports
     * `supports_rename = false`, so re-advertising mDNS alone never updates the name the
     * controlling phone sees. We therefore reconnect the native session under the new name
     * via the same start-intent path bitrate changes use: a brief interruption, after which
     * the new name shows everywhere, including on the controlling phone.
     *
     * While idle (no session), we re-advertise mDNS in place — instant and non-disruptive.
     */
    private fun applyRename(newName: String, feedback: TextView) {
        val sessionActive = dashboardState.sessionUser != null
        deviceName = newName
        prefs.edit().putString(KEY_DEVICE_NAME, newName).apply()
        renderDashboardState(dashboardState.copy(receiverName = newName))
        if (sessionActive) {
            // Reconnect under the new name. Do NOT call onReceiverRenamed() here: it
            // pre-stamps the service's nativeStartedConfig with the new name, which would
            // trip the duplicate-start guard and suppress the very restart we need. The
            // service's onStartCommand sets currentDeviceName/nativeStartedConfig itself.
            sendServiceIntent(newName)
            showFeedback(feedback, "✓ Renamed to “$newName” — reconnecting…", FEEDBACK_SUCCESS)
        } else {
            NativeBridge.renameDevice(newName)
            SpotifyService.onReceiverRenamed(newName)
            showFeedback(feedback, "✓ Renamed to “$newName” — no restart.", FEEDBACK_SUCCESS)
        }
    }

    /**
     * Bitrate is bound when the native player is created, so changing it requires recreating
     * the native session. Re-delivering the start intent lets the native layer replace the
     * receiver, fully tearing the old one (and its mDNS responder) down before starting the
     * new one — two live libmdns responders panic on teardown (SIGABRT). The Activity and
     * foreground service stay alive; only the native session cycles.
     */
    private fun applyBitrate(newBitrateKbps: Int, feedback: TextView) {
        bitrateKbps = newBitrateKbps
        prefs.edit().putInt(KEY_BITRATE_KBPS, newBitrateKbps).apply()
        sendServiceIntent(deviceName)
        showFeedback(feedback, "✓ Switching to ${bitrateLabel(newBitrateKbps)}…", FEEDBACK_SUCCESS)
    }

    private fun setFullscreen(enabled: Boolean) {
        fullscreenEnabled = enabled
        prefs.edit().putBoolean(KEY_FULLSCREEN, enabled).apply()
        if (enabled) {
            hideSystemBars()
            scheduleAutoHide()
        } else {
            handler.removeCallbacks(autoHideBarsTick)
            showSystemBars()
        }
    }

    /** Persists the clock-format override and re-renders the clock immediately (no restart). */
    private fun setTimeFormat(is24Hour: Boolean) {
        is24HourClock = is24Hour
        prefs.edit().putBoolean(KEY_TIME_FORMAT_24H, is24Hour).apply()
        updateClock()
    }

    private fun showFeedback(view: TextView, message: String, color: Int) {
        view.text = message
        view.setTextColor(color)
        view.visibility = View.VISIBLE
    }

    // ---- Info sheet ---------------------------------------------------------

    private fun showInfoSheet() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_info, null)
        val dialog = createCardDialog(view)
        bindInfoSheet(dialog, dashboardState)

        // About & updates row: shows the running version, opens the About sheet, and lights
        // an "Update" badge if a newer release is found. The check runs off the main thread
        // and is cached, so it's cheap on reopen.
        val updateBadge = view.findViewById<TextView>(R.id.tvUpdateBadge)
        val aboutRow = view.findViewById<View>(R.id.rowAbout)
        view.findViewById<TextView>(R.id.tvAboutValue).text = "Version $appVersionName"
        aboutRow.setOnClickListener { showAboutSheet() }
        Thread {
            val check = UpdateRepository.check(appVersionName)
            handler.post {
                if (dialog.isShowing &&
                    check.status == UpdateRepository.UpdateStatus.UPDATE_AVAILABLE) {
                    updateBadge.visibility = View.VISIBLE
                }
            }
        }.start()

        dialog.setOnDismissListener {
            infoDialog = null
            if (fullscreenEnabled) hideSystemBars()
        }
        infoDialog = dialog
        dialog.show()
        requestInitialFocus(aboutRow)
    }

    // ---- About / updates sheet ---------------------------------------------

    private fun showAboutSheet() {
        val view = layoutInflater.inflate(R.layout.bottom_sheet_about, null)
        val dialog = createCardDialog(view)

        val versionLine = view.findViewById<TextView>(R.id.tvAboutVersion)
        val banner = view.findViewById<View>(R.id.cardUpdateBanner)
        val bannerText = view.findViewById<TextView>(R.id.tvUpdateBannerText)
        val statusLine = view.findViewById<TextView>(R.id.tvUpdateStatus)
        val whatsNewTitle = view.findViewById<TextView>(R.id.tvWhatsNewTitle)
        val whatsNew = view.findViewById<TextView>(R.id.tvWhatsNew)
        val downloadButton = view.findViewById<MaterialButton>(R.id.btnDownload)
        val sourceRow = view.findViewById<View>(R.id.rowSource)

        versionLine.text = "Version $appVersionName"
        sourceRow.setOnClickListener { openUrl(UpdateRepository.REPO_URL) }

        // Run the (cached) check off the main thread, then render the result.
        Thread {
            val check = UpdateRepository.check(appVersionName)
            handler.post {
                if (!dialog.isShowing) return@post
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
                        downloadButton.setOnClickListener { openUrl(latest.releaseUrl) }
                    }
                    UpdateRepository.UpdateStatus.UP_TO_DATE ->
                        statusLine.text = "You're on the latest version."
                    UpdateRepository.UpdateStatus.ERROR ->
                        statusLine.text = "Couldn't check for updates. Tap “Source & releases” to check manually."
                }
            }
        }.start()

        dialog.setOnDismissListener {
            if (fullscreenEnabled) hideSystemBars()
        }
        dialog.show()
        // "Source & releases" is always present; the Download button only appears once an update
        // is found, so the source row is the reliable initial focus target.
        requestInitialFocus(sourceRow)
    }

    /** Opens [url] in a browser, ignoring the (unlikely) no-browser case rather than crashing. */
    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            // No browser/handler available — nothing actionable to do.
        }
    }

    private fun bindInfoSheet(dialog: Dialog, state: ReceiverDashboardState) {
        val (statusLabel, dotColor) = statusInfo(state)
        dialog.findViewById<View>(R.id.viewInfoDot)?.backgroundTintList = ColorStateList.valueOf(dotColor)
        dialog.findViewById<TextView>(R.id.tvInfoStatus)?.text = statusLabel
        dialog.findViewById<LinearLayout>(R.id.llInfoListeners)?.let { bindListeners(it, state.listeners) }
        dialog.findViewById<TextView>(R.id.tvInfoDiscovery)?.text = state.discoveryLine
        dialog.findViewById<TextView>(R.id.tvInfoService)?.text = state.serviceLine
        dialog.findViewById<TextView>(R.id.tvInfoAudio)?.text = state.audioLine
        dialog.findViewById<TextView>(R.id.tvInfoQuality)?.text = "Quality: ${bitrateLabel(bitrateKbps)}"
    }

    /** Renders the session section as listener rows (one today; forward-compatible for Jam). */
    private fun bindListeners(container: LinearLayout, listeners: List<ReceiverDashboardState.SessionListener>) {
        if (container.tag == listeners) return
        container.tag = listeners
        container.removeAllViews()
        if (listeners.isEmpty()) {
            val tv = TextView(this).apply {
                text = ReceiverDashboardState.NO_SESSION_LINE
                setTextColor(ContextCompat.getColor(this@NowPlayingActivity, R.color.muted))
                textSize = 15f
                typeface = ResourcesCompat.getFont(this@NowPlayingActivity, R.font.hanken_regular)
                setPadding(0, (8 * resources.displayMetrics.density).toInt(), 0, 0)
            }
            container.addView(tv)
            return
        }
        for (listener in listeners) {
            val row = layoutInflater.inflate(R.layout.item_session_listener, container, false)
            row.findViewById<TextView>(R.id.tvListenerName).text = listener.name
            row.findViewById<TextView>(R.id.tvListenerSubtitle).text = listener.subtitle
            val avatar = row.findViewById<ImageView>(R.id.ivListenerAvatar)
            val url = listener.avatarUrl
            if (!url.isNullOrBlank()) {
                avatar.load(url) {
                    crossfade(true)
                    transformations(CircleCropTransformation())
                    error(R.drawable.bg_avatar_placeholder)
                }
            } else {
                avatar.setImageResource(R.drawable.bg_avatar_placeholder)
            }
            container.addView(row)
        }
    }

    /** Builds a centered, rounded popup-card dialog hosting [view]. */
    private fun createCardDialog(view: View): Dialog {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setContentView(view)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            val width = (resources.displayMetrics.widthPixels * 0.72f).toInt()
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    // ---- Fullscreen / immersive --------------------------------------------

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // The background layers (mesh/wash/scrim/grain) fill the window edge-to-edge; only the
        // foreground contentLayer is padded by the system-bar insets + a base margin, so there's
        // no inset border. When fullscreen hides the bars the insets collapse to the base margin.
        val basePad = (BASE_PAD_DP * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            contentLayer.setPadding(basePad + bars.left, basePad + bars.top, basePad + bars.right, basePad + bars.bottom)
            insets
        }
        insetsController = WindowCompat.getInsetsController(window, rootView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        if (fullscreenEnabled) hideSystemBars()
    }

    private fun hideSystemBars() {
        insetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showSystemBars() {
        insetsController.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHideBarsTick)
        handler.postDelayed(autoHideBarsTick, AUTO_HIDE_MS)
    }

    // ---- Service start ------------------------------------------------------

    private fun checkPermissionsAndStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startSpotifyService(deviceName)
            } else {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startSpotifyService(deviceName)
        }
    }

    private fun startSpotifyService(name: String) {
        renderDashboardState(ReceiverDashboardState.starting(name))
        sendServiceIntent(name)
    }

    private fun sendServiceIntent(name: String) {
        val intent = Intent(this, SpotifyService::class.java).apply {
            putExtra("DEVICE_NAME", name)
            putExtra("BITRATE_KBPS", bitrateKbps)
        }
        startForegroundService(intent)
    }

    private fun bitrateLabel(value: Int): String = when (value) {
        96 -> "96 kbps · fastest"
        160 -> "160 kbps · balanced"
        320 -> "320 kbps · highest"
        else -> "$value kbps"
    }

    private fun bitrateToIndex(kbps: Int): Float = when (kbps) {
        96 -> 0f
        320 -> 2f
        else -> 1f
    }

    private fun indexToBitrate(index: Float): Int = when (index.toInt()) {
        0 -> 96
        2 -> 320
        else -> 160
    }

    private companion object {
        // Starting the foreground receiver service is a once-per-PROCESS concern, not
        // once-per-Activity. A rotation recreates the Activity; if this were an instance
        // field it would reset to false on every recreate and re-run startSpotifyService(),
        // which renders the "Starting…" state and clobbers a live now-playing face. Static
        // so it survives recreation — the running service is left alone and the seeded
        // DashboardStateHolder snapshot stays on screen across orientation changes.
        private var serviceStartRequested = false

        private const val PREFS_NAME = "spotify_receiver_prefs"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_BITRATE_KBPS = "bitrate_kbps"
        private const val KEY_FULLSCREEN = "fullscreen_enabled"
        private const val KEY_TIME_FORMAT_24H = "time_format_24h"
        private const val DEFAULT_DEVICE_NAME = "Android Speaker"
        private const val DEFAULT_BITRATE_KBPS = 160
        private val SUPPORTED_BITRATES_KBPS = setOf(96, 160, 320)

        private const val STATUS_PLAYING = "Playing"
        private const val AUTO_HIDE_MS = 4_000L
        private const val PAUSE_TO_CLOCK_MS = 120_000L
        private const val BASE_PAD_DP = 22

        // Default accent (matches @color/accent_fallback).
        private val DEFAULT_ACCENT = 0xFF1DB954.toInt()

        // Status-dot palette.
        private val DOT_GREEN = 0xFF1DB954.toInt()
        private val DOT_AMBER = 0xFFE3B341.toInt()
        private val DOT_GREY = 0xFF8B949E.toInt()
        private val DOT_RED = 0xFFF85149.toInt()

        // Settings feedback text colors.
        private val FEEDBACK_SUCCESS = 0xFF38EF7D.toInt()
        private val FEEDBACK_NEUTRAL = 0xFF8B949E.toInt()
    }
}
