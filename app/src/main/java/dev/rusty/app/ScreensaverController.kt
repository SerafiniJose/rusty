package dev.rusty.app

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout

/**
 * Shell-owned screensaver = the idle/sleep layer over the active feature. It auto-shows when the
 * dashboard is idle (the active→idle edge, cold launch, idle-timeout), or on a manual clock tap,
 * and on wake it crossfades out while the destination fragment replays its bloom. State comes from
 * the observable [ReceiverStateStore]; a 1 Hz tick keeps the wall clock moving while showing.
 *
 * Wake is per-theme: a tap/key off the chrome buttons calls [ScreensaverTheme.onWakeGesture];
 * if the theme consumes it (e.g. OLED freeze+reveal) we stay, otherwise we exit with the bloom.
 */
class ScreensaverController(
    private val overlay: FrameLayout,
    private val prefs: SharedPreferences,
    private val host: ScreensaverHost,
    private val reassertImmersive: () -> Unit,
    private val exitTarget: () -> ScreensaverExitTarget?,
    // True when the foreground feature is the Spotify receiver. The screensaver IS Spotify's idle
    // face only then; over a non-receiver feature (HA) it is a pure input-idle-timeout sleep layer.
    private val isReceiverForeground: () -> Boolean,
    // Process-wide single source of truth.
    private val store: ReceiverStateStore,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var resumed = false
    private var activeTheme: ScreensaverTheme? = null
    private var showReason: ScreensaverShowReason? = null
    private var prevVisual: VisualState = VisualState.IDLE
    private var exiting = false

    var isShowing: Boolean = false
        private set

    private val idleRunnable = Runnable { show() }

    private val tickRunnable = object : Runnable {
        override fun run() {
            activeTheme?.bind(store.snapshot.state, is24Hour())
            handler.postDelayed(this, 1_000L)
        }
    }

    // Persistent (registered across the whole resumed window, not only while showing) so the
    // active→idle edge can auto-show even when nothing is currently up. The store delivers on the
    // main thread already, but the handler.post is kept so re-entrant edges stay serialized.
    private val stateListener = ReceiverStateStore.Listener { snapshot ->
        val state = snapshot.state
        handler.post {
            val next = state.visualState()
            val action = ScreensaverTransitions.onVisualEdge(isShowing, showReason, prevVisual, next)
            prevVisual = next
            when (action) {
                // Spotify state edges only drive the saver when Spotify is foreground. Over HA the
                // input-idle timer is the only trigger (and a remote track-start must not yank you out).
                ScreensaverEdgeAction.SHOW_AUTO_IDLE -> if (isReceiverForeground()) show()
                ScreensaverEdgeAction.EXIT_TO_DASHBOARD -> if (isReceiverForeground()) exitToDashboard()
                ScreensaverEdgeAction.NONE -> {}
            }
            if (isShowing) activeTheme?.bind(state, is24Hour())
        }
    }

    // ---- Lifecycle (called from the activity) -------------------------------

    fun onResume() {
        resumed = true
        prevVisual = store.snapshot.state.visualState()
        store.addListener(stateListener) // immediate delivery sees no edge (prev==next)
        if (isShowing && !exiting) {
            activeTheme?.onShown()
            handler.post(tickRunnable)
        } else if (!isShowing && isReceiverForeground() &&
            store.snapshot.state.visualState() == VisualState.IDLE
        ) {
            show() // resumed into an idle Spotify dashboard → the screensaver is the idle face
        } else {
            resetIdleTimer() // non-receiver foreground (or active): arm the input-idle sleep layer
        }
    }

    fun onPause() {
        resumed = false
        handler.removeCallbacks(idleRunnable)
        store.removeListener(stateListener)
        if (isShowing) {
            handler.removeCallbacks(tickRunnable)
            activeTheme?.onHidden()
        }
    }

    /**
     * Recompute the saver for a just-switched foreground feature (called by the shell after the new
     * fragment is committed). Switching into an idle Spotify dashboard shows the idle face at once;
     * switching into a non-receiver feature (or active Spotify) arms the input-idle sleep-layer timer.
     * No-op while paused or already showing (a switch can't happen while the overlay covers input).
     */
    fun onForegroundFeatureChanged() {
        if (!resumed || isShowing) return
        if (isReceiverForeground() && store.snapshot.state.visualState() == VisualState.IDLE) {
            show()
        } else {
            resetIdleTimer()
        }
    }

    /** Re-arms the idle countdown from the latest input. No-op while showing or paused. */
    fun resetIdleTimer() {
        handler.removeCallbacks(idleRunnable)
        if (!resumed || isShowing) return
        currentTimeout().timeoutMs?.let { handler.postDelayed(idleRunnable, it) }
    }

    // ---- Show / wake / exit -------------------------------------------------

    fun show() {
        if (isShowing || !resumed) return
        handler.removeCallbacks(idleRunnable)
        exiting = false
        mountTheme()
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.isClickable = true
        overlay.isFocusable = true
        overlay.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_DOWN) onWakeGesture()
            true
        }
        overlay.animate().alpha(1f).setDuration(CROSSFADE_MS).start()
        // Reason = why we're up: idle dashboard → AUTO_IDLE (track start blooms us out);
        // active dashboard → AMBIENT (a peek; a track change must not yank the user out).
        // Over a non-receiver feature the saver is always AMBIENT (a sleep layer that never
        // auto-blooms); only an idle Spotify dashboard makes it the AUTO_IDLE idle face.
        showReason = if (isReceiverForeground() &&
            store.snapshot.state.visualState() == VisualState.IDLE
        ) {
            ScreensaverShowReason.AUTO_IDLE
        } else {
            ScreensaverShowReason.AMBIENT
        }
        isShowing = true
        handler.post(tickRunnable)
    }

    /**
     * Live-swap to the currently-selected theme while the saver is showing — e.g. the user picks
     * a different theme in settings while idle, with the saver visible underneath. An instant swap
     * (no crossfade) reads clearly as "the theme changed". No-op when not showing: the next [show]
     * reads the new pref anyway.
     */
    fun onThemeChanged() {
        if (!isShowing || exiting) return
        mountTheme()
    }

    /** Inflate the currently-selected theme into the overlay, replacing any previous one. */
    private fun mountTheme() {
        activeTheme?.onHidden()
        val theme = themeFor(currentThemeId())
        overlay.removeAllViews()
        overlay.addView(theme.createView(overlay.context, overlay, host))
        activeTheme = theme
        theme.setChromeVisible(isReceiverForeground()) // hide Settings/Info when a sleep layer over HA
        theme.onShown()
        theme.bind(store.snapshot.state, is24Hour())
    }

    /** A key while showing routes through the same wake path as a touch. */
    fun onWakeKey() = onWakeGesture()

    /**
     * Switch-from-saver: the shell has already committed the new foreground feature underneath us;
     * crossfade the saver out to reveal it. No bloom — the destination (e.g. HA) isn't a
     * [ScreensaverExitTarget], so [exitToDashboard] just fades the overlay away.
     */
    fun dismissToForeground() = exitToDashboard()

    private fun onWakeGesture() {
        if (!isShowing || exiting) return
        // Sleep layer over a non-receiver feature: any input crossfades straight back to it
        // (no theme two-stage; exitTarget is null for HA, so no bloom — just the crossfade).
        if (!isReceiverForeground()) {
            exitToDashboard()
            return
        }
        if (activeTheme?.onWakeGesture() == true) return // theme consumed it (e.g. OLED freeze+reveal)
        // A manual wake blooms out only when there's an active dashboard to reveal. At idle the
        // screensaver IS the idle face — staying put avoids dropping the user onto the redundant
        // (and, for OLED, jarringly bright) Spotify idle face underneath. A real track-start still
        // auto-blooms via the state listener's EXIT_TO_DASHBOARD edge.
        if (store.snapshot.state.visualState() == VisualState.ACTIVE) exitToDashboard()
    }

    private fun exitToDashboard() {
        if (!isShowing || exiting) return
        exiting = true
        handler.removeCallbacks(tickRunnable)
        // fragment snaps to idle + replays the bloom; a mesh-less theme (OLED) suppresses the
        // dashboard's mesh so its colors don't flash in over the dark exit.
        exitTarget()?.onReturnFromScreensaver(activeTheme?.rendersAmbientMesh ?: true)
        overlay.animate().alpha(0f).setDuration(CROSSFADE_MS).withEndAction { teardown() }.start()
    }

    private fun teardown() {
        handler.removeCallbacks(tickRunnable)
        activeTheme?.onHidden()
        activeTheme = null
        showReason = null
        overlay.setOnTouchListener(null)
        overlay.removeAllViews()
        overlay.visibility = View.GONE
        overlay.alpha = 1f
        overlay.isClickable = false
        overlay.isFocusable = false
        isShowing = false
        exiting = false
        reassertImmersive()
        resetIdleTimer()
    }

    /**
     * Permanently tears down the screensaver. Idempotent — safe to call twice (handler
     * removeCallbacks is a no-op if the runnable is not queued; removeAllViews on an empty
     * container is harmless; removeListener on an unregistered listener is a no-op).
     *
     * Call from [HomeActivity.onDestroy] so that no in-flight handler callbacks survive the
     * Activity death and so the [ReceiverStateStore] listener does not hold a reference to
     * the destroyed overlay.
     */
    fun dispose() {
        handler.removeCallbacks(idleRunnable)
        handler.removeCallbacks(tickRunnable)
        store.removeListener(stateListener)
        overlay.animate().cancel()
        activeTheme?.onHidden()
        activeTheme = null
        overlay.removeAllViews()
        isShowing = false
        exiting = false
        resumed = false
    }

    // ---- Helpers ------------------------------------------------------------

    private fun themeFor(id: ScreensaverThemeId): ScreensaverTheme = when (id) {
        ScreensaverThemeId.CLOCK -> ClockTheme()
        ScreensaverThemeId.OLED -> OledTheme()
        ScreensaverThemeId.CANVAS -> CanvasTheme()
    }

    private fun currentThemeId(): ScreensaverThemeId =
        ScreensaverThemeId.fromPrefValue(prefs.getString(KEY_THEME, null))

    private fun currentTimeout(): ScreensaverTimeout =
        ScreensaverTimeout.fromPrefSeconds(prefs.getInt(KEY_TIMEOUT_SECONDS, ScreensaverTimeout.DEFAULT.prefSeconds))

    private fun is24Hour(): Boolean =
        prefs.getBoolean(KEY_TIME_FORMAT_24H, DateFormat.is24HourFormat(overlay.context))

    companion object {
        const val KEY_THEME = "screensaver_theme"
        const val KEY_TIMEOUT_SECONDS = "screensaver_timeout_seconds"
        private const val KEY_TIME_FORMAT_24H = "time_format_24h"
        private const val CROSSFADE_MS = 250L
    }
}
