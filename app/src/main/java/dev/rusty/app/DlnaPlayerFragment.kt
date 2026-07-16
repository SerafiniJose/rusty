package dev.rusty.app

import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import coil.dispose
import coil.load
import com.google.android.material.button.MaterialButton
import dev.rusty.app.renderer.DidlParser
import dev.rusty.app.renderer.DlnaScreen
import dev.rusty.app.renderer.RendererTransport
import dev.rusty.app.renderer.RendererUiRuntime
import dev.rusty.app.renderer.RendererUiSnapshot
import dev.rusty.app.renderer.RendererRuntimeHolder
import dev.rusty.app.renderer.dlnaScreenFor

/**
 * The DLNA Player now-playing screen. Observes [RendererUiRuntime] on the main thread and renders a
 * status x transport state machine (see [DlnaScreen]). Depends only on the facade so it is testable
 * with a fake runtime.
 *
 * Visual twin of the Spotify Now Playing face: ambient mesh + grain background, split
 * cover/info layout, round icon transport controls. DLNA has no next/previous (the sender
 * owns the queue), so the transport row is stop + play/pause only.
 */
class DlnaPlayerFragment : Fragment(), InsetAware, FocusRestorable {

    /** Test seam: inject a fake runtime before the fragment starts observing. */
    internal var runtimeOverride: RendererUiRuntime? = null
    private val runtime: RendererUiRuntime get() = runtimeOverride ?: RendererRuntimeHolder

    private val ticker = Handler(Looper.getMainLooper())
    private val tickRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            ticker.postDelayed(this, 500L)
        }
    }

    private val listener: (RendererUiSnapshot) -> Unit = { render(it) }

    // The exact runtime instance the listener is currently registered on. Captured at add-time and
    // used again at remove-time so a test that swaps runtimeOverride in between onStart and onStop
    // (launchFragmentInContainer resolves onStart before the test can inject its fake) still detaches
    // from the SAME instance it attached to — otherwise the listener would leak on the untouched
    // instance (RendererRuntimeHolder is a process-static singleton) instead of being removed.
    private var activeRuntime: RendererUiRuntime? = null

    private var dragging = false
    private var lastMediaKey: String? = null

    // views
    private var contentLayer: View? = null
    private var mesh: AmbientMeshView? = null
    private var identityRow: View? = null
    private var identityName: TextView? = null
    private var identityDot: View? = null
    private var statusBlock: View? = null
    private var statusTitle: TextView? = null
    private var statusSubtitle: TextView? = null
    private var startButton: MaterialButton? = null
    private var nowPlaying: View? = null
    private var artCard: View? = null
    private var albumArt: ImageView? = null
    private var artGlyph: View? = null
    private var eyebrow: TextView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var albumView: TextView? = null
    private var progress: SeekBar? = null
    private var elapsedView: TextView? = null
    private var durationView: TextView? = null
    private var playPause: ImageButton? = null
    private var stopButton: ImageButton? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_dlna_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        contentLayer = view.findViewById(R.id.dlnaContentLayer)
        mesh = view.findViewById(R.id.dlnaMesh)
        identityRow = view.findViewById(R.id.dlnaIdentityRow)
        identityName = view.findViewById(R.id.dlnaIdentityName)
        identityDot = view.findViewById(R.id.dlnaIdentityDot)
        statusBlock = view.findViewById(R.id.dlnaStatusBlock)
        statusTitle = view.findViewById(R.id.dlnaStatusTitle)
        statusSubtitle = view.findViewById(R.id.dlnaStatusSubtitle)
        startButton = view.findViewById(R.id.dlnaStartButton)
        nowPlaying = view.findViewById(R.id.dlnaNowPlaying)
        artCard = view.findViewById(R.id.dlnaAlbumArtCard)
        albumArt = view.findViewById(R.id.dlnaAlbumArt)
        artGlyph = view.findViewById(R.id.dlnaArtGlyph)
        eyebrow = view.findViewById(R.id.dlnaEyebrow)
        titleView = view.findViewById(R.id.dlnaTitle)
        artistView = view.findViewById(R.id.dlnaArtist)
        albumView = view.findViewById(R.id.dlnaAlbum)
        progress = view.findViewById(R.id.dlnaProgress)
        elapsedView = view.findViewById(R.id.dlnaElapsed)
        durationView = view.findViewById(R.id.dlnaDuration)
        playPause = view.findViewById(R.id.dlnaPlayPause)
        stopButton = view.findViewById(R.id.dlnaStop)

        // Reset per-view state so a hide->show fragment recreation re-renders from scratch instead
        // of the parse-once guard suppressing a re-parse into the fresh (empty) widgets.
        lastMediaKey = null
        dragging = false

        startButton?.setOnClickListener { (activity as? HomeActivity)?.startDlnaPlayer() }
        playPause?.setOnClickListener {
            val playing = runtime.current().state?.transport == RendererTransport.PLAYING
            if (playing) runtime.pause() else runtime.play()
        }
        stopButton?.setOnClickListener { runtime.stop() }
        progress?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, value: Int, fromUser: Boolean) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                dragging = false
                val duration = runtime.current().state?.durationMs ?: 0L
                runtime.seek(seekBar.progress.toLong().coerceIn(0L, duration.coerceAtLeast(0L)))
            }
        })
    }

    override fun onStart() {
        super.onStart()
        val r = runtime
        activeRuntime = r
        r.addListener(listener)
        ticker.post(tickRunnable)
        mesh?.start()
    }

    override fun onStop() {
        activeRuntime?.removeListener(listener)
        activeRuntime = null
        ticker.removeCallbacks(tickRunnable)
        mesh?.stop()
        super.onStop()
    }

    override fun onDestroyView() {
        albumArt?.dispose()
        ticker.removeCallbacks(tickRunnable)
        contentLayer = null
        mesh = null
        identityRow = null
        identityName = null
        identityDot = null
        statusBlock = null
        statusTitle = null
        statusSubtitle = null
        startButton = null
        nowPlaying = null
        artCard = null
        albumArt = null
        artGlyph = null
        eyebrow = null
        titleView = null
        artistView = null
        albumView = null
        progress = null
        elapsedView = null
        durationView = null
        playPause = null
        stopButton = null
        lastMediaKey = null
        super.onDestroyView()
    }

    /** Test hook: re-attach to whatever [runtime] now resolves to (after injecting a fake via
     *  [runtimeOverride]) and re-render immediately. Detaches from [activeRuntime] — the instance
     *  actually holding the listener — not from a freshly-resolved (and possibly different) one. */
    @androidx.annotation.VisibleForTesting
    internal fun rebindForTest() {
        activeRuntime?.removeListener(listener)
        val r = runtime
        activeRuntime = r
        r.addListener(listener)
    }

    private fun render(snapshot: RendererUiSnapshot) {
        if (view == null) return
        when (val screen = dlnaScreenFor(snapshot)) {
            DlnaScreen.STOPPED -> showStatus("Renderer stopped", "Start it to receive audio.", showStart = true)
            DlnaScreen.STARTING -> showStatus("Starting…", "", showStart = false)
            DlnaScreen.FAILED -> showStatus("Couldn't start", "Tap Start to try again.", showStart = true)
            DlnaScreen.READY -> showStatus(
                "Ready to cast — ${snapshot.identity.deviceName}",
                snapshot.identity.lanAddress?.let { "Discoverable at $it" } ?: "Discoverable on your network.",
                showStart = false,
            )
            DlnaScreen.BUFFERING, DlnaScreen.NOW_PLAYING, DlnaScreen.PLAYBACK_ERROR ->
                showNowPlaying(snapshot, screen)
        }
    }

    private fun showStatus(title: String, subtitle: String, showStart: Boolean) {
        statusBlock?.visibility = View.VISIBLE
        nowPlaying?.visibility = View.GONE
        artCard?.visibility = View.GONE
        identityRow?.visibility = View.GONE
        statusTitle?.text = title
        statusSubtitle?.text = subtitle
        startButton?.visibility = if (showStart) View.VISIBLE else View.GONE
        lastMediaKey = null
    }

    private fun showNowPlaying(snapshot: RendererUiSnapshot, screen: DlnaScreen) {
        statusBlock?.visibility = View.GONE
        nowPlaying?.visibility = View.VISIBLE
        artCard?.visibility = View.VISIBLE
        identityRow?.visibility = View.VISIBLE
        identityName?.text = snapshot.identity.deviceName
        identityDot?.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), R.color.accent_fallback))
        eyebrow?.text = when (screen) {
            DlnaScreen.BUFFERING -> "BUFFERING…"
            DlnaScreen.PLAYBACK_ERROR -> "PLAYBACK ERROR"
            else -> "NOW PLAYING"
        }
        val state = snapshot.state ?: return

        val mediaKey = state.media?.metadata
        if (mediaKey != lastMediaKey) {
            lastMediaKey = mediaKey
            val meta = DidlParser.parse(mediaKey)
            titleView?.text = meta.title ?: "Unknown title"
            artistView?.text = meta.artist.orEmpty()
            albumView?.text = meta.album.orEmpty()
            albumArt?.dispose()
            val art = meta.albumArtUri
            if (art != null) {
                // Glyph stays visible while loading and on failure; Coil clears it on success.
                artGlyph?.visibility = View.VISIBLE
                albumArt?.load(art) {
                    listener(
                        onSuccess = { _, _ -> artGlyph?.visibility = View.GONE },
                        onError = { _, _ -> artGlyph?.visibility = View.VISIBLE },
                    )
                }
            } else {
                albumArt?.setImageDrawable(null)
                artGlyph?.visibility = View.VISIBLE
            }
        }

        val actions = state.currentTransportActions()
        val playing = state.transport == RendererTransport.PLAYING
        playPause?.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playPause?.contentDescription = if (playing) "Pause" else "Play"
        val playPauseEnabled = actions.contains("Play") || actions.contains("Pause")
        playPause?.isEnabled = playPauseEnabled
        playPause?.alpha = if (playPauseEnabled) 1f else 0.4f
        val stopEnabled = actions.contains("Stop")
        stopButton?.isEnabled = stopEnabled
        stopButton?.alpha = if (stopEnabled) 1f else 0.4f

        val duration = state.durationMs
        val seekEnabled = actions.contains("Seek") && duration != null && duration > 0
        progress?.isEnabled = seekEnabled
        progress?.max = (duration ?: 0L).toInt().coerceAtLeast(0)
        durationView?.text = ReceiverDashboardState.formatDuration(duration ?: 0L)
        // One-shot position render so paused/buffering states show the frozen position too
        // (the ticker only advances while PLAYING).
        if (!dragging) {
            runtime.positionMs()?.let {
                progress?.progress = it.toInt()
                elapsedView?.text = ReceiverDashboardState.formatDuration(it)
            }
        }
        updateProgress()
    }

    private fun updateProgress() {
        if (dragging) return
        val np = nowPlaying ?: return
        if (np.visibility != View.VISIBLE) return
        val snapshot = runtime.current()
        if (dlnaScreenFor(snapshot) != DlnaScreen.NOW_PLAYING) return
        if (snapshot.state?.transport != RendererTransport.PLAYING) return
        runtime.positionMs()?.let {
            progress?.progress = it.toInt()
            elapsedView?.text = ReceiverDashboardState.formatDuration(it)
        }
    }

    override fun onInsets(insets: WindowInsetsCompat) {
        // Pad the content layer, not the root, so the mesh/grain stay full-bleed.
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        contentLayer?.setPadding(bars.left, bars.top, bars.right, bars.bottom)
    }

    override fun restoreFocus() {
        if (view?.isInTouchMode == true) return
        val target: View? = if (startButton?.visibility == View.VISIBLE) startButton else playPause
        target?.requestFocus()
    }
}
