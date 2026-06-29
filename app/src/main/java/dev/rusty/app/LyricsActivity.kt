package dev.rusty.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.palette.graphics.Palette
import coil.dispose
import coil.load
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Full-screen, playback-synced lyrics. Opened by tapping the album art. Reads the current track id
 * + a native-minted token, fetches color-lyrics, and highlights/auto-scrolls the active line using
 * an anchored playback clock fed by the same broadcasts the now-playing screen uses.
 *
 * Tap anywhere to dismiss. The background is a solid, darkened color (so the always-white lyrics
 * stay readable) pulled from the lyrics API, falling back to a per-track artwork accent this screen
 * derives itself — so it refreshes on every track change without depending on the now-playing screen.
 */
class LyricsActivity : AppCompatActivity() {

    private lateinit var root: View
    private lateinit var scroll: ScrollView
    private lateinit var container: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var trackText: TextView
    private lateinit var providerText: TextView
    private lateinit var coverProbe: ImageView

    private lateinit var bricolageBold: Typeface
    private lateinit var hankenRegular: Typeface

    private val handler = Handler(Looper.getMainLooper())

    private var result: LyricsResult? = null
    private var lineViews: List<TextView> = emptyList()
    private var startTimes: List<Long> = emptyList()
    private var activeIndex = -1

    // Anchored playback clock.
    private var anchorElapsedMs = 0L
    private var anchorRealtime = 0L
    private var playing = false

    private var currentTrackId: String? = null
    private var currentCoverUrl: String? = null
    private var fetchInProgress = false
    private var awaitingToken = false

    // Monotonic id incremented before each Coil artwork load; the Palette callback captures the
    // value at request time and bails out if a newer request has superseded it or the activity is
    // no longer at least STARTED.
    private var artworkRequestId = 0

    // Background: a per-track artwork accent this screen derives itself (so it is never stale),
    // overridden by the lyrics API's own background color when the response carries one.
    private var artworkAccent: Int? = null
    private var hasApiBg = false
    private var bgColor = darkenForReadability(AccentHolder.accent)
    private val highlightColor = Color.WHITE
    private val dimColor = ColorUtils.setAlphaComponent(Color.WHITE, 120)

    // Single tap anywhere dismisses; a drag still scrolls the lyrics (onSingleTapUp never fires
    // for scroll gestures). In an error state the tap retries instead of closing.
    private val tapDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (statusRetry && statusText.visibility == View.VISIBLE) startFetch() else finish()
                return true
            }
        })
    }

    private val tick = object : Runnable {
        override fun run() {
            updateActiveLine()
            if (playing && result?.kind == LyricsKind.SYNCED) handler.postDelayed(this, 200L)
        }
    }

    private val tokenTimeout = Runnable {
        if (awaitingToken) {
            awaitingToken = false
            showStatus("Couldn't load lyrics. $retryHint.", retry = true)
        }
    }

    private val store: ReceiverStateStore by lazy { RustyApp.from(this) }

    // Playback now comes from the store (Task 12): the same close/re-anchor/reload/play-pause
    // logic the old ACTION_PLAYBACK receiver ran, decided by the pure LyricsPlaybackReaction.
    private val storeListener = ReceiverStateStore.Listener { snapshot -> onSnapshot(snapshot) }

    // The token signal is unrelated to playback state, so it stays a broadcast (Task 12 only
    // migrates status/playback consumption to the store).
    private val tokenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ReceiverDashboardBroadcast.ACTION_TOKEN && awaitingToken) {
                awaitingToken = false
                handler.removeCallbacks(tokenTimeout)
                startFetch()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lyrics)
        root = findViewById(R.id.lyricsRoot)
        scroll = findViewById(R.id.lyricsScroll)
        container = findViewById(R.id.lyricsContainer)
        statusText = findViewById(R.id.tvLyricsStatus)
        trackText = findViewById(R.id.tvLyricsTrack)
        providerText = findViewById(R.id.tvLyricsProvider)
        coverProbe = findViewById(R.id.lyricsCoverProbe)

        bricolageBold = ResourcesCompat.getFont(this, R.font.bricolage_bold) ?: Typeface.DEFAULT_BOLD
        hankenRegular = ResourcesCompat.getFont(this, R.font.hanken_regular) ?: Typeface.DEFAULT

        setupFullscreen()
        applyBackground()

        // Seed from the store snapshot. The position comes from the ground-truth anchor (not the
        // possibly-stale snapshot elapsed) so a re-opened screen stays in sync with the song.
        val state = store.snapshot.state
        currentTrackId = state.trackId
        currentCoverUrl = state.coverArtUrl
        trackText.text = listOf(state.trackTitle, state.trackArtist)
            .filter { it.isNotBlank() }.joinToString("  ·  ")
        anchorElapsedMs = store.liveElapsedMs()
        anchorRealtime = SystemClock.elapsedRealtime()
        playing = store.snapshot.anchor.playing
        loadArtworkAccent(currentCoverUrl)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        tapDetector.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    /**
     * Remote support: transport keys control playback from the lyrics view, and OK/Enter mirrors a
     * screen tap — retrying in the error state, otherwise dismissing — so the touch-only retry path
     * is reachable with a D-pad. BACK already closes the screen via the framework default.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (TvRemote.dispatchTransportKey(
                event,
                onPlayPause = { if (playing) NativeBridge.pause() else NativeBridge.play() },
                onNext = { NativeBridge.nextTrack() },
                onPrevious = { NativeBridge.previousTrack() },
            )
        ) return true
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                    if (statusRetry && statusText.visibility == View.VISIBLE) startFetch() else finish()
                }
                return true // consume down AND up so the key never falls through
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** TVs have no soft keyboard prompt to "tap"; phrase the retry hint for the active input. */
    private val retryHint: String
        get() = if (packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
            "Press OK to retry" else "Tap to retry"

    override fun finish() {
        super.finish()
        // Slide the lyrics back down as the now-playing screen returns.
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.now_playing_return_in, R.anim.lyrics_slide_down_out)
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ReceiverDashboardBroadcast.ACTION_TOKEN)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(tokenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(tokenReceiver, filter)
        }
        // addListener delivers the current snapshot immediately; onSnapshot's reaction re-anchors
        // from it (the seed in onCreate already set currentTrackId, so this won't spuriously reload).
        store.addListener(storeListener)
        startFetch()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(tokenTimeout)
        store.removeListener(storeListener)
        unregisterReceiver(tokenReceiver)
        super.onStop()
    }

    // ---- Fetch ----

    private var statusRetry = false

    private fun startFetch() {
        val trackId = currentTrackId
        if (trackId.isNullOrBlank()) { showStatus("No track playing"); return }
        if (fetchInProgress) return
        val token = TokenStore.accessToken?.takeIf { TokenStore.isValid() }
        if (token == null) {
            awaitingToken = true
            showStatus("Loading lyrics…")
            NativeBridge.requestAccessToken()
            handler.postDelayed(tokenTimeout, 5000L)
            return
        }
        fetchInProgress = true
        showStatus("Loading lyrics…")
        val requested = trackId
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) { LyricsRepository.fetch(requested, token) }
            fetchInProgress = false
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            if (requested != currentTrackId) return@launch   // stale (track changed mid-fetch)
            onLyricsLoaded(res)
        }
    }

    private fun onLyricsLoaded(res: LyricsResult) {
        result = res
        applyBackgroundFor(res)

        when (res.kind) {
            LyricsKind.SYNCED, LyricsKind.UNSYNCED -> {
                hideStatus()
                providerText.text = res.provider?.let { "Lyrics provided by ${it.replaceFirstChar { c -> c.uppercase() }}" } ?: ""
                renderLines(res)
            }
            LyricsKind.NONE -> showStatus("No lyrics available for this track")
            LyricsKind.ERROR -> showStatus("Couldn't load lyrics. $retryHint.", retry = true)
        }
    }

    // ---- Rendering ----

    private fun renderLines(res: LyricsResult) {
        container.removeAllViews()
        startTimes = res.lines.map { it.startMs }
        val pad = dp(10f)
        val views = ArrayList<TextView>(res.lines.size)
        for (line in res.lines) {
            val tv = TextView(this).apply {
                text = line.words.ifBlank { "♪" }
                textSize = 26f
                setTextColor(if (res.kind == LyricsKind.SYNCED) dimColor else highlightColor)
                typeface = hankenRegular
                setPadding(0, pad, 0, pad)
                setLineSpacing(0f, 1.1f)
            }
            container.addView(tv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            views.add(tv)
        }
        lineViews = views
        // Top/bottom breathing room so first/last lines can sit centered.
        scroll.post {
            val half = scroll.height / 2
            container.setPadding(0, half, 0, half)
            scroll.scrollTo(0, 0)
            if (result?.kind == LyricsKind.SYNCED) {
                activeIndex = -1
                handler.removeCallbacks(tick)
                handler.post(tick)
            }
        }
    }

    private fun updateActiveLine() {
        if (result?.kind != LyricsKind.SYNCED || lineViews.isEmpty()) return
        val idx = LyricSync.activeIndex(startTimes, positionMs())
        if (idx == activeIndex) return
        val old = activeIndex
        activeIndex = idx
        if (old in lineViews.indices) styleLine(lineViews[old], active = false)
        if (idx in lineViews.indices) {
            styleLine(lineViews[idx], active = true)
            scrollToCenter(lineViews[idx])
        }
    }

    private fun styleLine(tv: TextView, active: Boolean) {
        tv.setTextColor(if (active) highlightColor else dimColor)
        tv.typeface = if (active) bricolageBold else hankenRegular
    }

    private fun scrollToCenter(view: View) {
        val target = view.top - (scroll.height - view.height) / 2
        scroll.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    private fun positionMs(): Long =
        if (playing) anchorElapsedMs + (SystemClock.elapsedRealtime() - anchorRealtime) else anchorElapsedMs

    // ---- Live updates ----

    /**
     * Reacts to a store snapshot exactly as the old ACTION_PLAYBACK receiver did, with the
     * close/re-anchor/reload/play-pause split decided by the pure [LyricsPlaybackReaction].
     */
    private fun onSnapshot(snapshot: ReceiverSnapshot) {
        val decision = LyricsPlaybackReaction.decide(snapshot, currentTrackId)
        if (decision.close) { finish(); return }

        // Re-anchor the clock from the ground-truth live position (extrapolated), not the
        // possibly-stale snapshot elapsed, so the lyrics stay in sync with the song.
        anchorElapsedMs = store.liveElapsedMs()
        anchorRealtime = SystemClock.elapsedRealtime()
        playing = decision.playing

        val state = snapshot.state
        // Track changed → reload lyrics and refresh the background for the new track.
        if (decision.reloadTrack) {
            val trackId = state.trackId
            val cover = state.coverArtUrl
            handler.removeCallbacks(tokenTimeout)
            fetchInProgress = false
            awaitingToken = false
            currentTrackId = trackId
            currentCoverUrl = cover
            result = null
            activeIndex = -1
            container.removeAllViews()
            lineViews = emptyList()
            // Clear the previous track's color immediately so it never lingers; the new
            // artwork accent / API color replaces it as each resolves.
            artworkAccent = null
            hasApiBg = false
            bgColor = darkenForReadability(AccentHolder.accent)
            applyBackground()
            loadArtworkAccent(cover)
            trackText.text = listOf(state.trackTitle, state.trackArtist)
                .filter { it.isNotBlank() }.joinToString("  ·  ")
            startFetch()
            return
        }
        // Same track: keep ticking if synced + playing.
        if (playing && result?.kind == LyricsKind.SYNCED) {
            handler.removeCallbacks(tick); handler.post(tick)
        } else {
            handler.removeCallbacks(tick)
            updateActiveLine()
        }
    }

    // ---- Status + chrome ----

    private fun showStatus(message: String, retry: Boolean = false) {
        statusRetry = retry
        statusText.text = message
        statusText.visibility = View.VISIBLE
        scroll.visibility = View.INVISIBLE
    }

    private fun hideStatus() {
        statusRetry = false
        statusText.visibility = View.GONE
        scroll.visibility = View.VISIBLE
    }

    private fun applyBackground() {
        root.setBackgroundColor(forceOpaque(bgColor))
        scroll.setBackgroundColor(forceOpaque(bgColor))
    }

    /** Background priority: lyrics API color → this track's artwork accent → last-known accent. All darkened. */
    private fun applyBackgroundFor(res: LyricsResult) {
        val apiBg = res.bgColor
        if (apiBg != null) {
            hasApiBg = true
            bgColor = darkenForReadability(apiBg)
        } else {
            hasApiBg = false
            bgColor = darkenForReadability(artworkAccent ?: AccentHolder.accent)
        }
        applyBackground()
    }

    /** Derives this track's accent straight from its cover so the fallback color is never stale. */
    private fun loadArtworkAccent(url: String?) {
        if (url.isNullOrBlank()) return
        val requestedFor = currentTrackId
        val req = ++artworkRequestId
        coverProbe.load(url) {
            allowHardware(false)
            size(160)
            listener(onSuccess = { _, result ->
                val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@listener
                Palette.from(bitmap).generate { palette ->
                    // Bail if a newer artwork request has superseded this one or the activity is gone.
                    if (req != artworkRequestId) return@generate
                    if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@generate
                    if (requestedFor != currentTrackId) return@generate   // track moved on
                    val raw = palette?.vibrantSwatch?.rgb
                        ?: palette?.darkVibrantSwatch?.rgb
                        ?: palette?.dominantSwatch?.rgb
                        ?: return@generate
                    artworkAccent = raw
                    // Only recolor if we are not already showing the API's own background color.
                    if (!hasApiBg) {
                        bgColor = darkenForReadability(raw)
                        applyBackground()
                    }
                }
            })
        }
    }

    override fun onDestroy() {
        coverProbe.dispose()
        artworkRequestId++   // invalidate any in-flight Palette callback
        super.onDestroy()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val content = findViewById<View>(R.id.lyricsContent)
        val basePadV = dp(20f)
        val basePadH = dp(28f)
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            content.setPadding(basePadH + bars.left, basePadV + bars.top, basePadH + bars.right, basePadV + bars.bottom)
            insets
        }
        val fullscreen = getSharedPreferences("spotify_receiver_prefs", MODE_PRIVATE)
            .getBoolean("fullscreen_enabled", false)
        if (KeepScreenOnSettings.isEnabled(getSharedPreferences("spotify_receiver_prefs", MODE_PRIVATE))) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        val controller = WindowCompat.getInsetsController(window, root)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun forceOpaque(color: Int): Int = color or 0xFF000000.toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    /** Blends a color toward black until it is dark enough for white lyrics to stay legible. */
    private fun darkenForReadability(color: Int): Int {
        var c = color or 0xFF000000.toInt()
        var guard = 0
        while (AccentPicker.luminance(c) > MAX_BG_LUMINANCE && guard < 24) {
            c = ColorUtils.blendARGB(c, Color.BLACK, 0.12f)
            guard++
        }
        return c
    }

    companion object {
        /** Keeps white-on-background contrast comfortably above ~4.5:1 for the large lyric text. */
        private const val MAX_BG_LUMINANCE = 0.15
    }
}
