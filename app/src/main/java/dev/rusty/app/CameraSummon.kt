package dev.rusty.app

import androidx.annotation.VisibleForTesting

/**
 * What the screen was showing just before a camera was summoned, i.e. what a dismiss has to put
 * back.
 *
 * [panelId] is a [ControlPanelId.wire] value, or [BACKGROUND] when Rusty was not in front at all
 * (a summon that woke the device). [screensaverWasActive] is recorded separately from
 * `panelId == "lockscreen"` because the two are the same fact only today: the shell reports
 * LOCKSCREEN while the saver covers a feature, and if that ever changes the restore must still
 * know to bring the saver back rather than leave the device awake on some panel forever.
 */
data class SummonCapture(val panelId: String, val screensaverWasActive: Boolean) {
    companion object {
        /**
         * [panelId] for "Rusty was not on screen". Deliberately NOT a [ControlPanelId] wire value
         * (`ControlPanelId.fromWire("")` is null), so a restore can never mistake it for a panel.
         */
        const val BACKGROUND = ""
    }
}

/** One step of a summon or a restore, produced by [CameraSummonPlan] and executed by the shell
 *  half in `ControlService`. */
sealed interface SummonCmd {
    /** Put [cameraId]'s live view on screen (waking/foregrounding first — the executor's job). */
    data class ShowLive(val cameraId: String) : SummonCmd

    /** Put [capture] back: its panel, and its screensaver if one was up. */
    data class Restore(val capture: SummonCapture) : SummonCmd

    /**
     * Put the camera GRID on screen — the camera feature showing no live view.
     *
     * Deliberately not a [Restore] with a "camera" capture: a restore puts back what a summon
     * covered, whereas this is a destination the user asked for outright. It is what
     * `POST /api/camera/grid` produces, and unlike a restore it never touches any other panel.
     */
    object ShowGrid : SummonCmd

    /** Explicitly "do nothing". Never produced by [CameraSummonPlan] (which returns an empty list
     *  instead) but part of the command vocabulary, so an executor handed a command list can
     *  always be total; executing it is a no-op. */
    object None : SummonCmd
}

/**
 * The summon coordinator: pure state, no Android, no shell.
 *
 * Its whole job is answering "what does dismiss put back". The rule is that the FIRST view
 * captures and nothing after it does — a summon that switches from one camera to another (the
 * remote's next/prev, or a second automation trigger) must not capture the camera screen as the
 * thing to restore, which would turn dismiss into a no-op. Restoring happens at most once per
 * summon: dismiss, BACK, a deleted camera and a disabled feature all funnel into the same
 * clear-and-restore, and whichever arrives first wins; the rest see an inactive plan and produce
 * nothing.
 *
 * Thread-safety: a view/dismiss arrives on an HTTP pool thread while an external exit arrives on
 * the main thread, so every entry point is synchronized on this instance. The commands are
 * returned to the caller (not executed here), so no lock is ever held across shell work.
 */
class CameraSummonPlan {

    private var capture: SummonCapture? = null

    /** Whether a summon is in force, i.e. whether there is something to restore. */
    val active: Boolean
        @Synchronized get() = capture != null

    /**
     * A remote view of [cameraId]. [current] is what is on screen right now, captured only when
     * this is the first view of a summon.
     */
    @Synchronized
    fun onView(cameraId: String, current: SummonCapture): List<SummonCmd> {
        if (capture == null) capture = current
        return listOf(SummonCmd.ShowLive(cameraId))
    }

    /** A remote dismiss. Idempotent: an empty list when nothing is summoned. */
    @Synchronized
    fun onDismiss(): List<SummonCmd> {
        val original = capture ?: return emptyList()
        capture = null
        return listOf(SummonCmd.Restore(original))
    }

    /**
     * The user (BACK from the live view) or the device (camera deleted, feature switched off) left
     * the summoned view. Identical to [onDismiss]: the summon is over either way, and the panel it
     * covered still has to come back.
     */
    @Synchronized
    fun onExternalExit(): List<SummonCmd> = onDismiss()

    /**
     * A remote request for the camera grid.
     *
     * ABANDONS any capture rather than restoring it, and does so BEFORE the executor can act: the
     * grid is reached through `CameraFragment.showGridNow()`, whose `showGrid()` notifies
     * [onExternalExit] — and a capture still held at that instant would convert the request into a
     * restore of the pre-summon panel, bouncing the device straight back off the grid it was just
     * asked for. Dropping the capture here makes that echo a no-op.
     *
     * The trade is deliberate: after this, a later dismiss has nothing to put back (409), because
     * the user has said they want to be in the camera feature. Getting elsewhere is what the
     * remote's panel switch is for.
     */
    @Synchronized
    fun onGrid(): List<SummonCmd> {
        capture = null
        return listOf(SummonCmd.ShowGrid)
    }
}

/**
 * What the camera screen can do for the remote-control API, as the summon needs it. Implemented by
 * [CameraFragment]; every method runs on the main thread (see [CameraControlRelay]).
 */
interface CameraControlHost {
    /**
     * Shows [cameraId]'s live view and reports whether it LANDED. False means the call was dropped
     * — the fragment isn't resumed yet, or its camera list hasn't loaded — which is precisely the
     * cold-summon case the executor retries.
     */
    fun showLiveNow(cameraId: String): Boolean

    /** Returns to the snapshot grid, tearing down live playback. Idempotent. */
    fun showGridNow()

    /** The latest snapshot JPEG for [cameraId], or null when none has landed. */
    fun snapshotJpeg(cameraId: String): ByteArray?

    /** camera id -> wire state ("live" | "ok" | "stale" | "unreachable" | "none"). Cameras with no
     *  entry are reported as "none". */
    fun cameraStates(): Map<String, String>
}

/**
 * App-scoped seam between the remote-control API and the camera screen — [PanelControlRelay]'s
 * camera-shaped twin, and for the same reason: a summon arrives on an HTTP pool thread inside
 * `ControlService`, which holds no fragment reference, while only the mounted [CameraFragment] can
 * open a live view or hand out a decoded frame.
 *
 * The attach window is `onStart`..`onStop`, which is exactly when the fragment has a camera list
 * and a running snapshot loop. Outside it there is no host: a snapshot request answers "no frame"
 * and a summon's [showLiveNow] retry simply keeps waiting for the fragment the panel switch is
 * bringing up.
 *
 * [notifyLiveExited] is the external-exit hook. [CameraFragment.showGrid] is the ONE place the
 * live view is ever left (BACK, a deleted camera, the feature being switched off all call it), so
 * notifying from there covers every exit with a single call site — and its own idempotence
 * (`if (mode == GRID) return`) keeps a restore that itself returns to the grid from echoing back.
 *
 * Threading: state under [lock]; callbacks are invoked outside it, on the caller's thread.
 */
object CameraControlRelay {

    private val lock = Any()

    private var host: CameraControlHost? = null
    private var exitListener: (() -> Unit)? = null
    private var exitListenerOwner: Any? = null

    /** A second attach replaces the first (an Activity recreation overlaps the two fragments),
     *  mirroring [PanelControlRelay.attachHost]. */
    fun attachHost(h: CameraControlHost) {
        synchronized(lock) { host = h }
    }

    /** No-op unless [h] is still the attached host — the outgoing fragment of an overlapping
     *  recreation must not tear down the incoming one's registration. */
    fun detachHost(h: CameraControlHost) {
        synchronized(lock) { if (host === h) host = null }
    }

    /** The mounted camera screen, or null when there is none. */
    fun host(): CameraControlHost? = synchronized(lock) { host }

    /**
     * Registers [owner]'s exit listener for the life of that owner's runtime. Identity-tracked the
     * same way [detachHost] is: a restarted control service constructs a new runtime before the
     * outgoing one's `release()` runs, so [clearExitListener] must not be able to unregister the
     * newcomer's listener.
     */
    fun setExitListener(owner: Any, l: () -> Unit) {
        synchronized(lock) {
            exitListener = l
            exitListenerOwner = owner
        }
    }

    /** Unregisters [owner]'s listener; a no-op unless [owner] still owns the registration. */
    fun clearExitListener(owner: Any) {
        synchronized(lock) {
            if (exitListenerOwner !== owner) return
            exitListener = null
            exitListenerOwner = null
        }
    }

    /** Called by the camera screen when a live view is left for any reason. */
    fun notifyLiveExited() {
        val listener = synchronized(lock) { exitListener } ?: return
        listener()
    }

    /** Test-only: keeps JVM tests of this process-wide singleton independent of each other. */
    @VisibleForTesting
    fun resetForTest() {
        synchronized(lock) {
            host = null
            exitListener = null
            exitListenerOwner = null
        }
    }
}
