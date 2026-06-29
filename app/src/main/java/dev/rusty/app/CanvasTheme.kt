package dev.rusty.app

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.Locale
import java.util.TimeZone

/**
 * Full-bleed Canvas screensaver theme. Plays the current track's Canvas loop behind a subtle
 * clock/track overlay; when the track has no Canvas it falls back to the blurred album-art wash +
 * clock, so the saver still looks intentional. Independent of the now-playing toggle: selecting the
 * theme is the opt-in, so its controller is always enabled.
 */
class CanvasTheme : ScreensaverTheme {
    private lateinit var root: View
    private lateinit var wash: ImageView
    private lateinit var canvasPlayer: CanvasPlayerView
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var status: TextView
    private lateinit var chrome: View
    private lateinit var launcher: FeatureLauncher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CanvasController? = null
    private var canvasActive = false
    private var loadedWashUrl: String? = null

    override fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View {
        root = LayoutInflater.from(context).inflate(R.layout.screensaver_canvas, parent, false)
        wash = root.findViewById(R.id.ssCanvasWash)
        canvasPlayer = root.findViewById(R.id.ssCanvasPlayer)
        canvasPlayer.setFill(true)
        clock = root.findViewById(R.id.ssClock)
        date = root.findViewById(R.id.ssDate)
        status = root.findViewById(R.id.ssStatus)
        chrome = root.findViewById(R.id.ssChrome)
        root.findViewById<ImageButton>(R.id.ssBtnSettings).setOnClickListener { host.openSettings() }
        root.findViewById<ImageButton>(R.id.ssBtnInfo).setOnClickListener { host.openInfo() }
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

        val store = RustyApp.from(context)
        controller = CanvasController(
            store = store,
            fetcher = CanvasRepository.shared,
            tokenProvider = androidSpotifyTokenProvider(context)::token,
            isEnabled = { true }, // selecting the theme is the opt-in; independent of the now-playing toggle
            scope = scope,
        ).also { it.addListener { state -> renderCanvas(state) } }
        return root
    }

    override fun bind(state: ReceiverDashboardState, is24Hour: Boolean) {
        val now = System.currentTimeMillis()
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        clock.text = ClockFormat.time(now, is24Hour, locale, zone)
        date.text = ClockFormat.date(now, locale, zone)
        status.text = state.idleStatus().first
        // Keep the blurred wash populated from the cover so the no-Canvas fallback looks intentional.
        loadWash(state.coverArtUrl)
    }

    private fun loadWash(url: String?) {
        if (url == loadedWashUrl) return
        loadedWashUrl = url
        if (url.isNullOrBlank()) { wash.setImageDrawable(null); return }
        // Same Coil call style as SpotifyFragment.renderAlbumArt (centerCrop backdrop).
        wash.load(url) { crossfade(true) }
    }

    private fun renderCanvas(state: CanvasState) {
        when (state) {
            is CanvasState.Found -> {
                canvasPlayer.play(state.url)
                if (!canvasActive) {
                    canvasActive = true
                    canvasPlayer.visibility = View.VISIBLE
                    canvasPlayer.animate().alpha(1f).setDuration(300L).start()
                }
            }
            CanvasState.Loading, CanvasState.None -> {
                if (canvasActive) {
                    canvasActive = false
                    canvasPlayer.animate().alpha(0f).setDuration(300L).withEndAction {
                        canvasPlayer.visibility = View.GONE
                        canvasPlayer.clear()
                    }.start()
                }
            }
        }
    }

    override fun onShown() { controller?.start() }

    override fun onHidden() {
        controller?.stop()
        canvasPlayer.animate().cancel()
        canvasPlayer.release()
        canvasActive = false
    }

    override fun setChromeVisible(visible: Boolean) {
        if (!visible) launcher.collapse()
        chrome.visibility = if (visible) View.VISIBLE else View.GONE
    }
}
