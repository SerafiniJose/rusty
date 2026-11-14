package dev.rusty.app

import android.content.Context
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
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
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var duration: TextView
    private lateinit var chrome: View
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
        progress = root.findViewById(R.id.ssWatchProgress)
        elapsed = root.findViewById(R.id.ssWatchElapsed)
        duration = root.findViewById(R.id.ssWatchDuration)
        chrome = root.findViewById(R.id.ssChrome)

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
    }

    // Stop resolving during the exit crossfade; onHidden tears the theme down.
    override fun onExitStarted() {
        pollJob?.cancel()
        pollJob = null
        canvasJob?.cancel()
        canvasJob = null
    }

    override fun onHidden() {
        pollJob?.cancel()
        pollJob = null
        canvasJob?.cancel()
        canvasJob = null
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
        syncCanvas(track)

        if (track == null) {
            showEmpty(state)
            return
        }

        empty.visibility = View.GONE
        header.visibility = View.VISIBLE
        trackView.visibility = View.VISIBLE

        val device = track.deviceName?.takeIf { it.isNotBlank() }
        val where = when {
            local != null -> device ?: state.receiverName
            remoteStatus == RemoteStatus.ERROR -> device?.let { "LAST SEEN ON $it" } ?: "LAST SEEN ON SPOTIFY"
            !track.isPlaying -> device?.let { "PAUSED ON $it" } ?: "PAUSED ON SPOTIFY"
            track.isEpisode -> device?.let { "PODCAST ON $it" } ?: "PODCAST ON SPOTIFY"
            else -> device?.let { "PLAYING ON $it" } ?: "PLAYING ON SPOTIFY"
        }
        eyebrow.text = where.uppercase(locale)

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
    }

    private fun showEmpty(state: ReceiverDashboardState) {
        empty.visibility = View.VISIBLE
        header.visibility = View.GONE
        trackView.visibility = View.GONE
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
        if (!visible) launcher.collapse()
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
    }

    override fun refreshLauncher() = launcher.refresh()

    private enum class RemoteStatus { WAITING, PLAYING, NOTHING, ERROR }

    private companion object {
        const val PREFS_NAME = "spotify_receiver_prefs"
        const val POLL_INTERVAL_MS = 10_000L
        const val NO_TOKEN_RETRY_MS = 20_000L

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
