package dev.rusty.app

import android.content.Context
import android.graphics.Color
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import com.google.android.material.slider.Slider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.TimeZone

/**
 * "Watch Spotify" screensaver: when this receiver is not the active Connect device, show what the
 * linked account is playing somewhere else. It polls `GET /v1/me/player` (the same Web API token
 * the lyrics/Canvas paths use) while mounted, and falls back to the big clock when the account is
 * idle, the token is unavailable, or the network is down.
 *
 * Local receiver playback wins over the API when it is active: the dashboard already has the
 * authoritative track, artwork, and progress, and the API round-trip would only add latency.
 * Between polls the progress bar advances locally from the last snapshot. The interval is ten
 * seconds rather than five: the Web API started answering 429 to a five-second cadence, and a
 * clock that is a few seconds behind is much better than one that stops updating altogether.
 *
 * Tapping the card (or pressing any key on a remote) opens the detail view: a bigger cover, a
 * seek slider, transport buttons and a lyrics panel for the track that is playing *elsewhere*.
 * Those controls go through the Web API (`/me/player/pause` and friends) rather than the native
 * transport, because this receiver is not the device holding the playback — except when local
 * playback is what the card is showing, where the native transport is the correct target.
 */
class WatchTheme : ScreensaverTheme {
    private lateinit var root: View
    private lateinit var art: ImageView
    private lateinit var artCardImage: ImageView
    private lateinit var canvasPlayer: CanvasPlayerView
    private lateinit var scrim: ClockScrimView
    private lateinit var header: View
    private lateinit var headerClock: TextView
    private lateinit var headerDate: TextView
    private lateinit var empty: View
    private lateinit var emptyClock: TextView
    private lateinit var emptyDate: TextView
    private lateinit var emptyStatus: TextView
    private lateinit var trackView: View
    private lateinit var eyebrow: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var lyricLine: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var chrome: View
    private lateinit var card: View
    private lateinit var detail: View
    private lateinit var detailArt: ImageView
    private lateinit var detailEyebrow: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailArtist: TextView
    private lateinit var detailAlbum: TextView
    private lateinit var detailElapsed: TextView
    private lateinit var detailDuration: TextView
    private lateinit var detailStatus: TextView
    private lateinit var detailSeek: Slider
    private lateinit var detailPlay: ImageButton
    private lateinit var lyricsPanel: View
    private lateinit var lyricsScroll: ScrollView
    private lateinit var lyricsLines: LinearLayout
    private lateinit var lyricsMessage: TextView
    private lateinit var launcher: FeatureLauncher
    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var tokenProvider: SpotifyTokenProvider
    private val store by lazy { RustyApp.from(root.context) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null
    private var remote: WatchNowPlaying? = null
    private var remoteStatus = RemoteStatus.WAITING
    private var loadedArtUrl: String? = null
    private var appliedScrimPercent = -1
    private var lastState: ReceiverDashboardState? = null
    private var lastIs24Hour = false
    private var autoAdvanced = false
    private var canvasLayer: CanvasLayer? = null
    private var canvasJob: Job? = null
    private var canvasKey: String? = null

    /** Detail view state. [shownTrack] is whatever the card is currently rendering. */
    private var detailOpen = false
    private var lyricsOpen = false
    private var shownTrack: WatchNowPlaying? = null
    private var localPlaying = false
    private var chromeAllowed = true
    private var seeking = false
    private var detailArtUrl: String? = null
    private var transportJob: Job? = null
    private var lyricsJob: Job? = null
    private var lyricTickJob: Job? = null
    private var lyricsTrackId: String? = null
    private var lyricsResult: LyricsResult? = null
    private var lyricViews: List<TextView> = emptyList()
    private var lyricStarts: List<Long> = emptyList()
    private var lyricActive = -1

    /** Last synced line painted on the card; "\u0000" forces the next update through. */
    private var cardLyric = "\u0000"

    override fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View {
        root = LayoutInflater.from(context).inflate(R.layout.screensaver_watch, parent, false)
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        tokenProvider = androidSpotifyTokenProvider(context)

        art = root.findViewById(R.id.ssWatchArt)
        artCardImage = root.findViewById(R.id.ssWatchArtCardImage)
        canvasPlayer = root.findViewById(R.id.ssWatchCanvas)
        canvasPlayer.setFill(true)
        canvasLayer = CanvasLayer(canvasPlayer)
        scrim = root.findViewById(R.id.ssWatchScrim)
        header = root.findViewById(R.id.ssWatchHeader)
        headerClock = root.findViewById(R.id.ssWatchHeaderClock)
        headerDate = root.findViewById(R.id.ssWatchHeaderDate)
        empty = root.findViewById(R.id.ssWatchEmpty)
        emptyClock = root.findViewById(R.id.ssWatchClock)
        emptyDate = root.findViewById(R.id.ssWatchDate)
        emptyStatus = root.findViewById(R.id.ssWatchEmptyStatus)
        trackView = root.findViewById(R.id.ssWatchTrack)
        eyebrow = root.findViewById(R.id.ssWatchEyebrow)
        title = root.findViewById(R.id.ssWatchTitle)
        artist = root.findViewById(R.id.ssWatchArtist)
        album = root.findViewById(R.id.ssWatchAlbum)
        lyricLine = root.findViewById(R.id.ssWatchLyric)
        progress = root.findViewById(R.id.ssWatchProgress)
        elapsed = root.findViewById(R.id.ssWatchElapsed)
        duration = root.findViewById(R.id.ssWatchDuration)
        chrome = root.findViewById(R.id.ssChrome)
        card = root.findViewById(R.id.ssWatchTrack)
        detail = root.findViewById(R.id.ssWatchDetail)
        detailArt = root.findViewById(R.id.ssDetailArt)
        detailEyebrow = root.findViewById(R.id.ssDetailEyebrow)
        detailTitle = root.findViewById(R.id.ssDetailTitle)
        detailArtist = root.findViewById(R.id.ssDetailArtist)
        detailAlbum = root.findViewById(R.id.ssDetailAlbum)
        detailElapsed = root.findViewById(R.id.ssDetailElapsed)
        detailDuration = root.findViewById(R.id.ssDetailDuration)
        detailStatus = root.findViewById(R.id.ssDetailStatus)
        detailSeek = root.findViewById(R.id.ssDetailSeek)
        detailPlay = root.findViewById(R.id.ssDetailPlay)
        lyricsPanel = root.findViewById(R.id.ssDetailLyricsPanel)
        lyricsScroll = root.findViewById(R.id.ssDetailLyricsScroll)
        lyricsLines = root.findViewById(R.id.ssDetailLyricsLines)
        lyricsMessage = root.findViewById(R.id.ssDetailLyricsStatus)

        // The card is the whole entry point: touch users tap it, D-pad users press a key and the
        // wake gesture opens the same view (see onWakeGesture).
        card.setOnClickListener { openDetail() }
        // Tapping the sheet's own background (anywhere but a control) closes it again.
        detail.setOnClickListener { closeDetail() }
        detailSeek.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {
                seeking = true
            }

            override fun onStopTrackingTouch(slider: Slider) {
                seeking = false
                val track = shownTrack ?: return
                if (track.durationMs <= 0L) return
                seekTo((slider.value / 1000.0 * track.durationMs).toLong())
            }
        })
        detailSeek.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val track = shownTrack ?: return@addOnChangeListener
            if (track.durationMs > 0L) {
                detailElapsed.text = ReceiverDashboardState.formatDuration(
                    (value / 1000.0 * track.durationMs).toLong()
                )
            }
        }
        detailPlay.setOnClickListener { togglePlayPause() }
        root.findViewById<ImageButton>(R.id.ssDetailPrev).setOnClickListener { skip(previous = true) }
        root.findViewById<ImageButton>(R.id.ssDetailNext).setOnClickListener { skip(previous = false) }
        root.findViewById<ImageButton>(R.id.ssDetailClose).setOnClickListener { closeDetail() }
        root.findViewById<com.google.android.material.button.MaterialButton>(R.id.ssDetailLyrics)
            .setOnClickListener { toggleLyrics() }
        root.findViewById<ImageButton>(R.id.ssDetailLyricsClose)
            .setOnClickListener { closeLyrics() }
        lyricsPanel.setOnClickListener { closeLyrics() }
        lyricsScroll.setOnClickListener { closeLyrics() }

        root.findViewById<ImageButton>(R.id.ssBtnSettings).setOnClickListener { host.openSettings() }
        root.findViewById<ImageButton>(R.id.ssBtnInfo).apply {
            setOnClickListener { host.openInfo() }
            UpdateDot.attach(this)
        }
        launcher = FeatureLauncher(
            toggle = root.findViewById(R.id.ssBtnLauncher),
            menu = root.findViewById<LinearLayout>(R.id.ssLauncherMenu),
            scrim = root.findViewById(R.id.ssLauncherScrim),
            activeTint = ContextCompat.getColor(context, R.color.accent_fallback),
            inactiveTint = ContextCompat.getColor(context, R.color.ink),
            itemLayoutRes = R.layout.view_launcher_item,
            minEntriesToShow = 2,
        ) { host.launcherEntries() }
        launcher.refresh()
        applyScrim()
        return root
    }

    override fun bind(state: ReceiverDashboardState, is24Hour: Boolean) {
        lastState = state
        lastIs24Hour = is24Hour
        applyScrim()
        render(state, is24Hour)
    }

    override fun onShown() {
        applyScrim()
        startPolling()
        startLyricTick()
    }

    // Stop resolving during the exit crossfade; onHidden tears the theme down.
    override fun onExitStarted() {
        pollJob?.cancel()
        pollJob = null
        canvasJob?.cancel()
        canvasJob = null
        transportJob?.cancel()
        transportJob = null
        lyricsJob?.cancel()
        lyricsJob = null
        lyricTickJob?.cancel()
        lyricTickJob = null
    }

    override fun onHidden() {
        pollJob?.cancel()
        pollJob = null
        canvasJob?.cancel()
        canvasJob = null
        transportJob?.cancel()
        transportJob = null
        lyricsJob?.cancel()
        lyricsJob = null
        lyricTickJob?.cancel()
        lyricTickJob = null
        canvasLayer?.reset()
        canvasPlayer.release()
        canvasKey = null
    }

    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            var forceRefresh = false
            while (isActive) {
                // Local playback wins over the API (see class doc), so polling while the receiver
                // is casting only burns the endpoint's small rate-limit budget for a snapshot the
                // card never shows.
                if (lastState?.visualState() == VisualState.ACTIVE) {
                    delay(POLL_INTERVAL_MS)
                    continue
                }
                val token = tokenProvider.token(forceRefresh)
                if (token == null) {
                    // No live Connect session and no linked account: the saver still shows the
                    // clock, but stop hammering the native token path every few seconds.
                    remoteStatus = if (remote == null) RemoteStatus.WAITING else RemoteStatus.ERROR
                    rerender()
                    delay(NO_TOKEN_RETRY_MS)
                    forceRefresh = false
                    continue
                }
                var backoffMs = POLL_INTERVAL_MS
                when (val result = withContext(Dispatchers.IO) {
                    SpotifyWatchRepository.fetch(token)
                }) {
                    is WatchResult.Playing -> {
                        remote = result.track
                        remoteStatus = RemoteStatus.PLAYING
                        forceRefresh = false
                    }
                    WatchResult.Nothing -> {
                        remote = null
                        remoteStatus = RemoteStatus.NOTHING
                        forceRefresh = false
                    }
                    WatchResult.Unauthorized -> {
                        // The cached token was rejected. Force one rotation and retry immediately
                        // rather than showing a false "nothing playing" for a full poll interval.
                        forceRefresh = true
                        delay(500)
                        continue
                    }
                    is WatchResult.RateLimited -> {
                        // Keep the last snapshot (its progress keeps advancing locally) and wait
                        // out the server's Retry-After rather than tripping the limit again.
                        backoffMs = result.retryAfterMs?.coerceAtLeast(MIN_RATE_LIMIT_WAIT_MS)
                            ?: RATE_LIMIT_RETRY_MS
                        forceRefresh = false
                    }
                    WatchResult.Error -> {
                        // Keep the last successful snapshot on screen (briefly), marked as stale.
                        remoteStatus = RemoteStatus.ERROR
                        forceRefresh = false
                    }
                }
                rerender()
                delay(backoffMs)
            }
        }
    }

    private fun rerender() {
        lastState?.let { render(it, lastIs24Hour) }
    }

    private fun render(state: ReceiverDashboardState, is24Hour: Boolean) {
        val now = System.currentTimeMillis()
        autoAdvanced = state.autoAdvanced
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        val clockText = ClockFormat.time(now, is24Hour, locale, zone)
        val dateText = ClockFormat.date(now, locale, zone)
        emptyClock.text = clockText
        emptyDate.text = dateText
        headerClock.text = clockText
        headerDate.text = dateText

        // The store's state carries the position frozen at the last playback event; the anchor is
        // what knows how long ago that was. Without this the card's progress bar never advanced.
        val local = if (state.visualState() == VisualState.ACTIVE) {
            state.copy(elapsedMs = store.liveElapsedMs()).toWatchTrack(now)
        } else {
            null
        }
        val remoteTrack = remote?.takeIf {
            remoteStatus == RemoteStatus.PLAYING ||
                (remoteStatus == RemoteStatus.ERROR && now - it.receivedAtMs <= STALE_AFTER_MS)
        }
        val track = local ?: remoteTrack
        shownTrack = track
        localPlaying = local != null
        syncCanvas(track)

        if (track == null) {
            showEmpty(state)
            return
        }

        empty.visibility = View.GONE
        header.visibility = View.VISIBLE
        trackView.visibility = View.VISIBLE

        eyebrow.text = whereText(state, local != null, track).uppercase(locale)

        title.text = track.title
        artist.text = track.artist
        val albumName = track.album?.takeIf { it.isNotBlank() }
        album.text = albumName.orEmpty()
        album.visibility = if (albumName == null) View.GONE else View.VISIBLE

        val total = track.durationMs
        val played = track.elapsedAt(now)
        progress.progress = if (total > 0L) {
            ((played.toDouble() / total) * 1000.0).toInt().coerceIn(0, 1000)
        } else {
            0
        }
        elapsed.text = ReceiverDashboardState.formatDuration(played)
        duration.text = ReceiverDashboardState.formatDuration(total)
        loadArt(track.artUrl)
        ensureLyrics(track)
        updateCardLyric(played)
        renderDetail(state, track, now)
    }

    /** The "PLAYING ON …" eyebrow, shared by the card and the detail view. */
    private fun whereText(
        state: ReceiverDashboardState,
        isLocal: Boolean,
        track: WatchNowPlaying,
    ): String {
        val device = track.deviceName?.takeIf { it.isNotBlank() }
        return when {
            isLocal -> device ?: state.receiverName
            remoteStatus == RemoteStatus.ERROR ->
                device?.let { "LAST SEEN ON $it" } ?: "LAST SEEN ON SPOTIFY"
            !track.isPlaying -> device?.let { "PAUSED ON $it" } ?: "PAUSED ON SPOTIFY"
            track.isEpisode -> device?.let { "PODCAST ON $it" } ?: "PODCAST ON SPOTIFY"
            else -> device?.let { "PLAYING ON $it" } ?: "PLAYING ON SPOTIFY"
        }
    }

    private fun showEmpty(state: ReceiverDashboardState) {
        shownTrack = null
        localPlaying = false
        if (detailOpen) closeDetail()
        empty.visibility = View.VISIBLE
        header.visibility = View.GONE
        trackView.visibility = View.GONE
        clearCardLyric()
        emptyStatus.text = when (remoteStatus) {
            RemoteStatus.NOTHING -> "Nothing playing on Spotify"
            RemoteStatus.ERROR -> "Spotify is unreachable"
            RemoteStatus.WAITING -> if (state.visualState() == VisualState.ACTIVE) {
                state.idleStatus().first
            } else {
                "Waiting for Spotify"
            }
            RemoteStatus.PLAYING -> "Waiting for Spotify"
        }
        loadArt(null)
    }

    /**
     * Resolve the current track's Canvas and drive the same [CanvasLayer] choreography as the
     * Canvas theme. The API poll is every few seconds, but the key guard means each track resolves
     * once; pause/empty tears the video down so the album-art fallback stays a still image.
     */
    private fun syncCanvas(track: WatchNowPlaying?) {
        val key = track
            ?.takeIf { it.isPlaying && !it.isEpisode }
            ?.trackId
            ?.takeIf { it.isNotBlank() }
        if (key == canvasKey) return
        canvasKey = key
        canvasJob?.cancel()
        canvasJob = null
        if (key == null) {
            canvasLayer?.apply(CanvasState.None, 0L)
            return
        }
        // The previous loop holds/dips while the next URL resolves, exactly like the live screen.
        canvasLayer?.apply(CanvasState.Loading, transitionMs())
        canvasJob = scope.launch {
            val token = tokenProvider.token(false)
            if (token == null) {
                if (key == canvasKey) canvasLayer?.apply(CanvasState.None, transitionMs())
                return@launch
            }
            var fetch = withContext(Dispatchers.IO) { CanvasRepository.shared.fetch(key, token) }
            if (fetch is CanvasFetch.Unauthorized) {
                val fresh = tokenProvider.token(true)
                if (fresh != null) {
                    fetch = withContext(Dispatchers.IO) { CanvasRepository.shared.fetch(key, fresh) }
                }
            }
            if (key != canvasKey) return@launch
            val state = when (fetch) {
                is CanvasFetch.Success -> when (val result = fetch.result) {
                    is CanvasResult.Found -> CanvasState.Found(result.url)
                    CanvasResult.None -> CanvasState.None
                }
                CanvasFetch.Unauthorized, CanvasFetch.Error -> CanvasState.None
            }
            canvasLayer?.apply(state, transitionMs())
        }
    }

    /** The audio crossfade's length, read from the same pref as the Canvas theme. */
    private fun transitionMs(): Long =
        TrackTransition.durationMs(prefs.let { CrossfadeSettings.seconds(it) }, autoAdvanced)

    // ---- Detail view --------------------------------------------------------

    private fun renderDetail(state: ReceiverDashboardState, track: WatchNowPlaying, now: Long) {
        if (!detailOpen) return
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        detailEyebrow.text = whereText(state, localPlaying, track).uppercase(locale)
        detailTitle.text = track.title
        detailArtist.text = track.artist
        val albumName = track.album?.takeIf { it.isNotBlank() }
        detailAlbum.text = albumName.orEmpty()
        detailAlbum.visibility = if (albumName == null) View.GONE else View.VISIBLE
        detailPlay.setImageResource(if (track.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        detailDuration.text = ReceiverDashboardState.formatDuration(track.durationMs)
        val played = track.elapsedAt(now)
        // Leave the slider alone while a finger or D-pad is on it; the label follows the drag.
        if (!seeking) {
            detailElapsed.text = ReceiverDashboardState.formatDuration(played)
            // stepSize is 1, so the value must land on a whole step or BaseSlider throws.
            detailSeek.value = if (track.durationMs > 0L) {
                Math.round((played.toDouble() / track.durationMs) * 1000.0).toFloat()
                    .coerceIn(0f, 1000f)
            } else {
                0f
            }
        }
        loadDetailArt(track.artUrl)
        if (lyricsOpen) {
            // A track change while the panel is open should fetch the new lyrics, not leave the
            // previous song's lines scrolling against the new position.
            val trackId = track.trackId
            if (trackId != null && trackId != lyricsTrackId) {
                fetchLyrics(trackId)
            } else {
                updateLyricsHighlight(played)
            }
        }
    }

    private fun loadDetailArt(url: String?) {
        if (url == detailArtUrl) return
        detailArtUrl = url
        if (url.isNullOrBlank()) {
            detailArt.setImageDrawable(null)
        } else {
            detailArt.load(url) { crossfade(true) }
        }
    }

    private fun openDetail() {
        if (detailOpen) return
        val track = shownTrack ?: return
        val state = lastState ?: return
        detailOpen = true
        detail.visibility = View.VISIBLE
        applyChromeVisibility()
        detailStatus.text = ""
        renderDetail(state, track, System.currentTimeMillis())
    }

    private fun closeDetail() {
        if (!detailOpen) return
        closeLyrics()
        detailOpen = false
        detail.visibility = View.GONE
        applyChromeVisibility()
    }

    /**
     * A tap anywhere (or any key, via the controller's wake path) opens the detail view when the
     * card is showing a track that is playing elsewhere. Local playback returns false so the saver
     * blooms out to the real now-playing screen instead — that screen already owns the transport
     * and the lyrics entry point, and it is what a wake means there.
     */
    override fun onWakeGesture(): Boolean {
        if (detailOpen) {
            closeDetail()
            return true
        }
        if (localPlaying) return false
        if (shownTrack == null) return false
        openDetail()
        return true
    }

    /** While the detail view is up its buttons are the remote surface: D-pad skips and pauses. */
    override fun ownsRemote(): Boolean = detailOpen

    override fun onNavKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> skip(previous = true)
            KeyEvent.KEYCODE_DPAD_RIGHT -> skip(previous = false)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> togglePlayPause()
            else -> return // the shell enforces the key set
        }
    }

    // ---- Transport ----------------------------------------------------------

    private fun togglePlayPause() {
        val track = shownTrack ?: return
        if (localPlaying) {
            if (track.isPlaying) NativeBridge.pause() else NativeBridge.play()
            return
        }
        sendTransport(if (track.isPlaying) WatchTransport.PAUSE else WatchTransport.PLAY)
    }

    private fun skip(previous: Boolean) {
        if (localPlaying) {
            if (previous) NativeBridge.previousTrack() else NativeBridge.nextTrack()
            return
        }
        sendTransport(if (previous) WatchTransport.PREVIOUS else WatchTransport.NEXT)
    }

    private fun seekTo(positionMs: Long) {
        val track = shownTrack ?: return
        val clamped = if (track.durationMs > 0L) {
            positionMs.coerceIn(0L, track.durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        if (localPlaying) {
            NativeBridge.seek(clamped.toInt())
            return
        }
        runTransport({ token -> SpotifyWatchRepository.seek(token, clamped) }) {
            applySeek(clamped)
        }
    }

    private fun sendTransport(action: WatchTransport) {
        runTransport({ token -> SpotifyWatchRepository.transport(token, action) }) {
            applyTransport(action)
        }
    }

    /**
     * Runs one Web API transport call off the main thread, rotating the token once on 401, and
     * maps the outcome to a one-line hint under the buttons. 403 is the "token predates the
     * modify scope" case and gets its own wording because retrying cannot fix it.
     */
    private fun runTransport(call: (String) -> TransportResult, onDone: () -> Unit) {
        detailStatus.text = ""
        transportJob?.cancel()
        transportJob = scope.launch {
            val token = tokenProvider.token(false)
            if (token == null) {
                detailStatus.text = "Link Spotify to control playback"
                return@launch
            }
            var result = withContext(Dispatchers.IO) { call(token) }
            if (result == TransportResult.Unauthorized) {
                val fresh = tokenProvider.token(true)
                if (fresh != null) result = withContext(Dispatchers.IO) { call(fresh) }
            }
            detailStatus.text = when (result) {
                TransportResult.Done -> {
                    onDone()
                    ""
                }
                TransportResult.Unauthorized -> "Spotify needs re-linking"
                TransportResult.Forbidden -> "Re-link Spotify to control playback"
                TransportResult.Error -> "Couldn't reach Spotify"
            }
            rerender()
        }
    }

    /**
     * The API answers 204 with no body, so the button state is updated optimistically rather than
     * waiting for the next poll: pause flips the icon immediately, and a skip keeps the old card
     * for at most one poll interval.
     */
    private fun applyTransport(action: WatchTransport) {
        val current = remote ?: return
        val now = System.currentTimeMillis()
        remote = when (action) {
            WatchTransport.PAUSE -> current.copy(
                isPlaying = false,
                progressMs = current.elapsedAt(now),
                receivedAtMs = now,
            )
            WatchTransport.PLAY -> current.copy(
                isPlaying = true,
                progressMs = current.elapsedAt(now),
                receivedAtMs = now,
            )
            WatchTransport.NEXT, WatchTransport.PREVIOUS -> current
        }
    }

    private fun applySeek(positionMs: Long) {
        val current = remote ?: return
        remote = current.copy(
            progressMs = positionMs,
            receivedAtMs = System.currentTimeMillis(),
        )
    }

    // ---- Lyrics -------------------------------------------------------------

    /**
     * The card and the detail sheet show the same track's lyrics, so one fetch feeds both: the card
     * paints the current line, the sheet paints every line. Fetched on the track change rather than
     * on the sheet opening, because the card line has to be ready before anyone taps.
     */
    private fun ensureLyrics(track: WatchNowPlaying) {
        val trackId = track.trackId ?: return
        if (trackId != lyricsTrackId) fetchLyrics(trackId)
    }

    /** Recomputes the card's single line; a no-op for unsynced, absent or stale lyrics. */
    private fun updateCardLyric(playedMs: Long) {
        val result = lyricsResult
        val line = if (result?.kind == LyricsKind.SYNCED && lyricsTrackId == shownTrack?.trackId) {
            val index = LyricSync.activeIndex(lyricStarts, playedMs)
            result.lines.getOrNull(index)?.words?.trim()?.ifBlank { "♪" }
        } else {
            null
        }
        val text = line ?: ""
        if (text == cardLyric) return
        cardLyric = text
        lyricLine.text = text
    }

    private fun clearCardLyric() {
        cardLyric = "\u0000"
        lyricLine.text = ""
    }

    /**
     * Lyric lines change on the second, not on the poll: the sheet used to repaint on the
     * screensaver's 1 Hz bind, which left the highlight up to a second behind the audio. This ticks
     * at the same 200 ms the standalone lyrics screen uses.
     */
    private fun startLyricTick() {
        if (lyricTickJob?.isActive == true) return
        lyricTickJob = scope.launch {
            while (isActive) {
                val track = shownTrack
                if (track != null) {
                    val played = track.elapsedAt(System.currentTimeMillis())
                    updateCardLyric(played)
                    if (lyricsOpen) updateLyricsHighlight(played)
                }
                delay(LYRIC_TICK_MS)
            }
        }
    }

    private fun toggleLyrics() {
        if (lyricsOpen) closeLyrics() else openLyrics()
    }

    private fun openLyrics() {
        lyricsOpen = true
        lyricsPanel.visibility = View.VISIBLE
        val track = shownTrack
        val trackId = track?.trackId
        if (track == null || trackId == null) {
            showLyricsMessage("No lyrics for this track")
            return
        }
        val result = lyricsResult
        if (trackId == lyricsTrackId && result != null) {
            // The card already fetched this track: paint the sheet from the same result.
            if (lyricViews.isEmpty()) buildLyrics(result)
            updateLyricsHighlight(track.elapsedAt(System.currentTimeMillis()))
            return
        }
        if (trackId == lyricsTrackId && lyricsJob?.isActive == true) {
            showLyricsMessage("Loading lyrics…")
            return
        }
        fetchLyrics(trackId)
    }

    private fun closeLyrics() {
        lyricsOpen = false
        lyricsPanel.visibility = View.GONE
    }

    private fun showLyricsMessage(message: String) {
        lyricsMessage.text = message
        lyricsMessage.visibility = View.VISIBLE
        lyricsLines.visibility = View.GONE
    }

    /**
     * Lyrics come from the same color-lyrics endpoint the standalone lyrics screen uses, keyed by
     * the track id the Web API hands back for the remote track. The response is cached per track
     * for the process lifetime by [LyricsRepository], so reopening the panel is free.
     */
    private fun fetchLyrics(trackId: String) {
        lyricsJob?.cancel()
        lyricsTrackId = trackId
        lyricsResult = null
        lyricsLines.removeAllViews()
        lyricViews = emptyList()
        lyricStarts = emptyList()
        lyricActive = -1
        clearCardLyric()
        if (lyricsOpen) showLyricsMessage("Loading lyrics…")
        lyricsJob = scope.launch {
            val token = tokenProvider.token(false)
            if (token == null) {
                if (lyricsOpen) showLyricsMessage("Link Spotify to see lyrics")
                return@launch
            }
            // color-lyrics only accepts a native-session token, so a remote track (the normal case
            // for this face) goes straight to LRCLIB instead of spending a guaranteed 403 first.
            var result = if (localPlaying) {
                withContext(Dispatchers.IO) { LyricsRepository.fetch(trackId, token) }
            } else {
                LyricsResult(LyricsKind.NONE)
            }
            if (result.kind == LyricsKind.NONE || result.kind == LyricsKind.ERROR) {
                val info = shownTrack
                if (info != null) {
                    result = withContext(Dispatchers.IO) {
                        LrclibLyrics.fetch(info.artist, info.title, info.album, info.durationMs)
                    }
                }
            }
            if (trackId != lyricsTrackId) return@launch
            lyricsResult = result
            // The card reads the starts even when the sheet is closed; buildLyrics is only for it.
            lyricStarts = result.lines.map { it.startMs }
            if (lyricsOpen) {
                when (result.kind) {
                    LyricsKind.SYNCED, LyricsKind.UNSYNCED -> buildLyrics(result)
                    LyricsKind.NONE -> showLyricsMessage("No lyrics for this track")
                    LyricsKind.ERROR -> showLyricsMessage("Couldn't load lyrics")
                }
            }
            updateCardLyric(shownTrack?.elapsedAt(System.currentTimeMillis()) ?: 0L)
        }
    }

    private fun buildLyrics(result: LyricsResult) {
        lyricsMessage.visibility = View.GONE
        lyricsLines.visibility = View.VISIBLE
        lyricsLines.removeAllViews()
        val dim = ContextCompat.getColor(root.context, R.color.muted_dim)
        val typeface = androidx.core.content.res.ResourcesCompat.getFont(root.context, R.font.hanken_medium)
        val views = ArrayList<TextView>(result.lines.size)
        for (line in result.lines) {
            val lineView = TextView(root.context).apply {
                text = line.words.ifBlank { "♪" }
                setTextColor(dim)
                textSize = 24f
                this.typeface = typeface
                setPadding(0, 12, 0, 12)
                setLineSpacing(dp(5f).toFloat(), 1f)
            }
            lyricsLines.addView(lineView)
            views.add(lineView)
        }
        lyricViews = views
        lyricStarts = result.lines.map { it.startMs }
        lyricActive = -1
        if (result.kind == LyricsKind.UNSYNCED) {
            val ink = ContextCompat.getColor(root.context, R.color.ink)
            views.forEach { it.setTextColor(ink) }
        }
        updateLyricsHighlight(shownTrack?.elapsedAt(System.currentTimeMillis()) ?: 0L)
    }

    /** Highlight + centre the active synced line; a no-op for unsynced or absent lyrics. */
    private fun updateLyricsHighlight(positionMs: Long) {
        val result = lyricsResult ?: return
        if (result.kind != LyricsKind.SYNCED) return
        val index = LyricSync.activeIndex(lyricStarts, positionMs)
        if (index == lyricActive) return
        val ink = ContextCompat.getColor(root.context, R.color.ink)
        val dim = ContextCompat.getColor(root.context, R.color.muted_dim)
        if (lyricActive in lyricViews.indices) lyricViews[lyricActive].setTextColor(dim)
        lyricActive = index
        if (index !in lyricViews.indices) return
        val lineView = lyricViews[index]
        lineView.setTextColor(ink)
        val target = (lineView.top - lyricsScroll.height / 3).coerceAtLeast(0)
        lyricsScroll.smoothScrollTo(0, target)
    }

    private fun dp(value: Float): Int =
        (value * root.resources.displayMetrics.density).toInt()

    private fun ReceiverDashboardState.toWatchTrack(now: Long): WatchNowPlaying = WatchNowPlaying(
        trackId = trackId?.takeIf { it.isNotBlank() },
        title = trackTitle.ifBlank { "Unknown track" },
        artist = trackArtist.ifBlank { "Unknown artist" },
        album = null,
        artUrl = coverArtUrl?.takeIf { it.isNotBlank() },
        progressMs = elapsedMs.coerceAtLeast(0L),
        durationMs = durationMs.coerceAtLeast(0L),
        isPlaying = status == "Playing",
        deviceName = sessionDisplayName?.takeIf { it.isNotBlank() } ?: receiverName,
        deviceType = null,
        isEpisode = false,
        receivedAtMs = now,
    )

    private fun loadArt(url: String?) {
        if (url == loadedArtUrl) return
        loadedArtUrl = url
        if (url.isNullOrBlank()) {
            art.setImageDrawable(null)
            artCardImage.setImageDrawable(null)
            return
        }
        art.load(url) { crossfade(true) }
        artCardImage.load(url) { crossfade(true) }
    }

    private fun applyScrim() {
        val percent = maxOf(ClockAppearance.scrimPercent(prefs), WATCH_MIN_SCRIM_PERCENT)
        if (percent == appliedScrimPercent) return
        appliedScrimPercent = percent
        scrim.configure(
            color = Color.BLACK,
            strength = percent / 100f,
            centerYFraction = 0.5f,
            radiusFraction = 0.9f,
        )
    }

    override fun setChromeVisible(visible: Boolean) {
        chromeAllowed = visible
        if (!visible) launcher.collapse()
        applyChromeVisibility()
    }

    private fun applyChromeVisibility() {
        chrome.visibility = if (chromeAllowed && !detailOpen) View.VISIBLE else View.GONE
    }

    override fun refreshLauncher() = launcher.refresh()

    private enum class RemoteStatus { WAITING, PLAYING, NOTHING, ERROR }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
        const val POLL_INTERVAL_MS = 10_000L
        const val NO_TOKEN_RETRY_MS = 20_000L

        /** Same cadence as the standalone lyrics screen, so a line lands when it is sung. */
        const val LYRIC_TICK_MS = 200L

        /** Backoff when Spotify answers 429 without a usable `Retry-After`. */
        const val RATE_LIMIT_RETRY_MS = 30_000L

        /** Never retry a 429 sooner than this, even if the header says `0`. */
        const val MIN_RATE_LIMIT_WAIT_MS = 10_000L

        /** A stale snapshot is better than a blank card for a few minutes, but not forever. */
        const val STALE_AFTER_MS = 15 * 60 * 1_000L

        /** Even if the clock scrim is dialled down, the artwork needs some separation from type. */
        const val WATCH_MIN_SCRIM_PERCENT = 55
    }
}
