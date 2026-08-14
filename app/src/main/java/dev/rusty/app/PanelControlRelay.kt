package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * What the shell can do about panels, as the remote-control API needs it. Implemented by
 * [HomeActivity]; every method runs on the main thread (see [PanelControlRelay.requestPanel]).
 */
interface PanelControlHost {
    /**
     * Puts [id] on screen: a feature switch for the three features, `screensaver.show()` for
     * [ControlPanelId.LOCKSCREEN]. Switching to a feature while the saver is up must also dismiss
     * it, or the command would appear to do nothing.
     */
    fun showPanel(id: ControlPanelId)

    /** Re-reads the persisted lockscreen theme and live-swaps a mounted saver. */
    fun onLockscreenThemeChanged()

    /**
     * Sends Rusty's task to the back, revealing whatever is behind it. Reachable only while a
     * host is attached — which is exactly when the app is in the foreground, i.e. exactly when
     * this means anything.
     */
    fun sendToBackground()
}

/**
 * App-scoped seam between the remote-control API and the feature shell — the panel counterpart of
 * [SlideshowConfigRelay], and the reason `POST /api/panel` can exist at all.
 *
 * A control request arrives on an HTTP pool thread inside [ControlService], which holds no Activity
 * reference and may be running with no Activity at all (started on boot). The shell, meanwhile, is
 * the only thing that can commit a fragment transaction. This object is where they meet: the
 * Activity registers itself as the [PanelControlHost] for exactly as long as it can safely act,
 * and the service asks through here.
 *
 * ## The attach window is onResume..onPause, deliberately
 * [FeatureNavigator.switchTo] uses `commitNow`, which throws once the Activity has saved its state.
 * [HomeActivity] therefore attaches in `onResume` and detaches in `onPause` — the same window, for
 * the same reason, as [PlaybackTakeoverCoordinator]'s page consumer. Outside it there is no host,
 * [requestPanel] answers false, and the API reports 409 rather than crashing the app from an HTTP
 * thread. This is also why the snapshot's `panel.active` is null when detached: with no host there
 * is nothing that can take a switch, and saying so is more useful than naming a panel the caller
 * cannot change.
 *
 * ## Why `current` is pushed, not pulled
 * The snapshot is built on a pool thread, and "which fragment is showing / is the saver up" lives
 * in Activity-thread state that must not be read from off it. So the shell PUSHES its panel through
 * [publishCurrent] on every edge (feature switch, saver show, saver exit) and the pool thread only
 * ever reads one field under the lock. The value is cleared on detach so a destroyed Activity can
 * never leave a stale panel being reported as live.
 *
 * ## Threading
 * All state is guarded by [lock]; host callbacks are invoked OUTSIDE it, on the caller's thread —
 * the same discipline as [SlideshowConfigRelay] and [ScreenControlModel]. Callers that need the
 * main thread post there themselves: [ControlService] does, because [PanelControlHost] touches
 * fragments and Views.
 */
object PanelControlRelay {

    private val lock = Any()

    private var host: PanelControlHost? = null
    private var current: ControlPanelId? = null

    /**
     * Registers [h] as the shell that can take panel commands, and seeds the panel it is showing.
     * A second attach replaces the first: during an Activity recreation the incoming instance's
     * `onResume` can run before the outgoing one's `onPause`, and the newer window is the right
     * one to command.
     */
    fun attachHost(h: PanelControlHost, showing: ControlPanelId) {
        synchronized(lock) {
            host = h
            current = showing
        }
    }

    /**
     * Unregisters [h]. A no-op unless [h] is still the attached host — during the overlapping
     * recreation above, the outgoing Activity's `onPause` must not tear down the incoming one's
     * registration (the same identity check [ScreenControlModel.detachRenderer] makes by removing
     * a specific instance).
     */
    fun detachHost(h: PanelControlHost) {
        synchronized(lock) {
            if (host !== h) return
            host = null
            current = null
        }
    }

    /** Records the panel now on screen. Called by the shell on every edge that changes it. */
    fun publishCurrent(id: ControlPanelId) {
        synchronized(lock) {
            // Ignored while detached: an edge fired between onPause and onDestroy would otherwise
            // resurrect `current` and make the API report a live panel with no host to command.
            if (host == null) return
            current = id
        }
    }

    /** The panel on screen, or null when no shell is attached to be showing one. */
    fun current(): ControlPanelId? = synchronized(lock) { current }

    /** Whether a shell is attached — i.e. whether [requestPanel] can do anything. */
    fun hasHost(): Boolean = synchronized(lock) { host != null }

    /**
     * Asks the attached shell to show [id]; returns false when there is none (the API's 409).
     *
     * True means ACCEPTED, not applied: the host is invoked synchronously on the caller's thread,
     * so the caller must already be on the main thread, and the resulting panel is only observable
     * through the next [current] read after the host has actually switched.
     */
    fun requestPanel(id: ControlPanelId): Boolean {
        val target = synchronized(lock) { host } ?: return false
        target.showPanel(id)
        return true
    }

    /**
     * Asks the attached shell to send itself to the back; returns false when there is none,
     * which for this direction simply means Rusty is already not in front (the caller treats
     * that as a satisfied request rather than a failure).
     *
     * Invoked synchronously on the caller's thread, so the caller must already be on the main
     * thread — the host moves an Activity task.
     */
    fun requestBackground(): Boolean {
        val target = synchronized(lock) { host } ?: return false
        target.sendToBackground()
        return true
    }

    /**
     * Tells the attached shell that the persisted lockscreen theme changed. Unlike [requestPanel]
     * this is a notification, not a request: the theme is already saved, so a device with no
     * window simply picks it up the next time a saver mounts, and the API reports success either
     * way (returning "failed" for a preference that genuinely was written would be a lie).
     */
    fun notifyLockscreenThemeChanged() {
        val target = synchronized(lock) { host } ?: return
        target.onLockscreenThemeChanged()
    }

    /** Test-only: keeps JVM tests of this process-wide singleton independent of each other. */
    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            host = null
            current = null
        }
    }
}
