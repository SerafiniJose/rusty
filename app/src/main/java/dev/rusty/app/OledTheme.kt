package dev.rusty.app

import android.content.Context
import android.os.SystemClock
import android.text.TextUtils
import android.view.Choreographer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.TimeZone
import kotlin.random.Random

/**
 * Burn-in / power-safe minimal theme: true-black background, dimmed white clock/date, no mesh,
 * no bright art. The text slowly "breathes" — fading up from black to the dim peak and back down
 * ([OledBurnIn.breatheAlpha]) — and at each trough, while invisible, it teleports to a fresh
 * random spot inside a safe inset so no pixels stay lit in one place. When playing it adds one
 * dim `title — artist` line. The breathe is driven by a [Choreographer] frame loop (immune to the
 * animator-duration-scale=0 case that makes an infinite ValueAnimator spin).
 */
class OledTheme : ScreensaverTheme {
    private lateinit var root: View
    private lateinit var group: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var track: TextView
    private lateinit var chrome: View
    private lateinit var launcher: FeatureLauncher
    private var awake = false
    private var sleepLayer = false
    private var insetPx = 0f

    // True black, no mesh — so the exit bloom suppresses the dashboard's mesh (no color flash).
    override val rendersAmbientMesh: Boolean get() = false

    private val choreographer = Choreographer.getInstance()
    private var startMs = 0L
    private var lastPhase = 0f

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val now = SystemClock.uptimeMillis()
            if (startMs == 0L) startMs = now
            val phase = ((now - startMs) % OledBurnIn.PERIOD_MS).toFloat() / OledBurnIn.PERIOD_MS
            group.alpha = OledBurnIn.breatheAlpha(phase)
            // A wrap (phase rolled back toward 0) lands at the trough where the text is invisible —
            // teleport there so the jump is never seen.
            if (phase < lastPhase) reposition()
            lastPhase = phase
            choreographer.postFrameCallback(this)
        }
    }

    override fun createView(context: Context, parent: ViewGroup, host: ScreensaverHost): View {
        root = LayoutInflater.from(context).inflate(R.layout.screensaver_oled, parent, false)
        group = root.findViewById(R.id.ssOledGroup)
        clock = root.findViewById(R.id.ssOledClock)
        date = root.findViewById(R.id.ssOledDate)
        track = root.findViewById(R.id.ssOledTrack)
        chrome = root.findViewById(R.id.ssOledChrome)
        root.findViewById<ImageButton>(R.id.ssOledBtnSettings).setOnClickListener { host.openSettings() }
        root.findViewById<ImageButton>(R.id.ssOledBtnInfo).setOnClickListener { host.openInfo() }
        launcher = FeatureLauncher(
            toggle = root.findViewById(R.id.ssOledBtnLauncher),
            menu = root.findViewById<LinearLayout>(R.id.ssOledLauncherMenu),
            scrim = root.findViewById(R.id.ssOledLauncherScrim),
            activeTint = ContextCompat.getColor(context, R.color.accent_fallback),
            inactiveTint = ContextCompat.getColor(context, R.color.ink),
            itemLayoutRes = R.layout.view_launcher_item,
            minEntriesToShow = 2,
        ) { host.launcherEntries() }
        launcher.refresh()
        insetPx = 32f * context.resources.displayMetrics.density
        // Whenever the block resizes (e.g. a long track line wraps to two lines), pull it back
        // inside the safe area so a wider group jittered near an edge can't spill off-screen.
        group.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> clampToSafeArea() }
        return root
    }

    override fun bind(state: ReceiverDashboardState, is24Hour: Boolean) {
        val now = System.currentTimeMillis()
        val locale = root.resources.configuration.locales[0] ?: Locale.getDefault()
        val zone = TimeZone.getDefault()
        clock.text = ClockFormat.time(now, is24Hour, locale, zone)
        date.text = ClockFormat.date(now, locale, zone)

        // The status line is always present: the idle/ready message when nothing is playing
        // ("Ready for Spotify"), or the wrapped `title — artist` during an active peek.
        track.visibility = View.VISIBLE
        if (state.visualState() == VisualState.ACTIVE) {
            bindTrack(state.trackTitle, state.trackArtist)
        } else {
            track.maxWidth = Int.MAX_VALUE // the short idle line needs no wrap clamp
            track.text = state.idleStatus().first
        }
        // Brightness + position are owned by the breathe loop; bind only refreshes the text.
    }

    /**
     * Render the now-playing line. Keeps `title — artist` on one line when it fits the safe width,
     * otherwise stacks title over artist (two lines) — each line ellipsized so neither overflows.
     */
    private fun bindTrack(title: String, artist: String) {
        // Wrap when the line exceeds a comfortable fraction of the screen — well before it would
        // span edge to edge — so a long `title — artist` stacks instead of stretching across.
        val avail = root.width * TRACK_MAX_WIDTH_FRACTION
        if (avail > 0f) track.maxWidth = avail.toInt()
        val oneLine = "$title — $artist"
        track.text = if (avail <= 0f || track.paint.measureText(oneLine) <= avail) {
            oneLine
        } else {
            val t = TextUtils.ellipsize(title, track.paint, avail, TextUtils.TruncateAt.END)
            val a = TextUtils.ellipsize(artist, track.paint, avail, TextUtils.TruncateAt.END)
            "$t\n$a"
        }
    }

    /**
     * First gesture: freeze the breathe/drift, bring the text to full dim-peak at center, and
     * reveal the chrome so it can be aimed at (consumed → return true). Second gesture: let the
     * controller bloom out (return false).
     */
    override fun onWakeGesture(): Boolean {
        if (sleepLayer) return false
        if (awake) return false
        awake = true
        choreographer.removeFrameCallback(frameCallback)
        group.animate().translationX(0f).translationY(0f).alpha(OledBurnIn.breatheAlpha(0.5f))
            .setDuration(180L).start()
        chrome.visibility = View.VISIBLE
        return true
    }

    override fun onShown() {
        // Idempotent: the controller re-invokes onShown via onResume while already showing.
        awake = false
        if (::chrome.isInitialized) chrome.visibility = View.GONE
        choreographer.removeFrameCallback(frameCallback)
        startMs = 0L
        lastPhase = 0f
        group.alpha = 0f
        group.post { reposition() } // defer past first layout so root/group sizes are known
        choreographer.postFrameCallback(frameCallback)
    }

    override fun onHidden() {
        choreographer.removeFrameCallback(frameCallback)
    }

    override fun setChromeVisible(visible: Boolean) {
        sleepLayer = !visible
        if (!visible && ::launcher.isInitialized) launcher.collapse()  // don't leave pills floating
        if (!visible && ::chrome.isInitialized) chrome.visibility = View.GONE
    }

    /** Teleport the center-anchored text to a fresh random point inside the safe inset. */
    private fun reposition() {
        val maxDX = OledBurnIn.maxTravel(root.width, group.width, insetPx)
        val maxDY = OledBurnIn.maxTravel(root.height, group.height, insetPx)
        group.translationX = (Random.nextFloat() * 2f - 1f) * maxDX
        group.translationY = (Random.nextFloat() * 2f - 1f) * maxDY
    }

    /** Pull the current offset back within the safe travel for the group's present size. */
    private fun clampToSafeArea() {
        val maxDX = OledBurnIn.maxTravel(root.width, group.width, insetPx)
        val maxDY = OledBurnIn.maxTravel(root.height, group.height, insetPx)
        group.translationX = group.translationX.coerceIn(-maxDX, maxDX)
        group.translationY = group.translationY.coerceIn(-maxDY, maxDY)
    }

    private companion object {
        /** Track line wraps to two lines once wider than this fraction of the screen width. */
        const val TRACK_MAX_WIDTH_FRACTION = 0.7f
    }
}
