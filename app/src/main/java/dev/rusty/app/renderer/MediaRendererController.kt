package dev.rusty.app.renderer

import android.content.Context
import android.content.Intent
import android.util.Log

enum class RendererStatus { STOPPED, STARTING, RUNNING, FAILED }

/** Status and address are ONE value. A null URL under RUNNING means "running, but no routable
 *  network address right now" — the 0.0.0.0 fallback is never shown as an address. */
data class RendererStatusSnapshot(val status: RendererStatus, val descriptionUrl: String?)

/**
 * In-memory, process-static publisher of what the renderer is ACTUALLY doing.
 *
 * Deliberately not persisted: a URL written to disk could outlive a dead service and point at
 * nothing. If the OS kills the process, the publisher and its listeners die with it.
 *
 * The SERVICE is the sole source of RUNNING. [MediaRendererController] may only move the status
 * to STARTING (from a non-running state), FAILED, or STOPPED when no live service can do it.
 */
object RendererStatusPublisher {

    private val listeners = mutableSetOf<(RendererStatusSnapshot) -> Unit>()
    private var snapshot = RendererStatusSnapshot(RendererStatus.STOPPED, null)

    /** Posts to the main thread in production; tests inject an inline dispatcher. */
    private var dispatch: (Runnable) -> Unit = { r ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(r)
    }

    fun setDispatcher(d: (Runnable) -> Unit) = synchronized(this) { dispatch = d }

    fun current(): RendererStatusSnapshot = synchronized(this) { snapshot }

    /** Registration REPLAYS the current value: the service is normally already running long
     *  before the settings panel is opened, so a change-only listener would stay blank forever. */
    fun addListener(l: (RendererStatusSnapshot) -> Unit) {
        val (d, snap) = synchronized(this) {
            listeners.add(l)
            dispatch to snapshot
        }
        d(Runnable { if (synchronized(this) { l in listeners }) l(snap) })
    }

    fun removeListener(l: (RendererStatusSnapshot) -> Unit) = synchronized(this) {
        listeners.remove(l)
        Unit
    }

    fun publish(next: RendererStatusSnapshot) {
        val (d, targets) = synchronized(this) {
            snapshot = next
            dispatch to listeners.toList()
        }
        targets.forEach { l ->
            // Re-check membership INSIDE the dispatched runnable: a listener removed between
            // publish() and the main thread draining the queue must not be called.
            d(Runnable { if (synchronized(this) { l in listeners }) l(next) })
        }
    }

    /** Start path. No-op when already RUNNING — HomeActivity re-syncs on every app start and the
     *  already-alive service will not run onCreate again to clear a spurious STARTING. */
    fun publishStartingUnlessRunning() {
        if (current().status == RendererStatus.RUNNING) return
        publish(RendererStatusSnapshot(RendererStatus.STARTING, null))
    }

    fun publishFailed() = publish(RendererStatusSnapshot(RendererStatus.FAILED, null))

    /** Stop path. stopService() is a request — a RUNNING service publishes STOPPED itself from
     *  onDestroy. The two states with no live service to do that are FAILED (sticky) and STARTING
     *  (a rapid Start→Stop can cancel the pending creation before onCreate ever runs). */
    fun publishStoppedIfInactive() {
        val s = current().status
        if (s == RendererStatus.FAILED || s == RendererStatus.STARTING) {
            publish(RendererStatusSnapshot(RendererStatus.STOPPED, null))
        }
    }
}

/**
 * Starts or stops [MediaRendererService] to match the persisted "Expose as media player" toggle.
 * Called from [dev.rusty.app.HomeActivity.onCreate], the settings toggle, and [dev.rusty.app.BootReceiver].
 *
 * Known limitation: apps targeting Android 15+ may NOT launch a mediaPlayback foreground service
 * from BOOT_COMPLETED — startForegroundService throws ForegroundServiceStartNotAllowedException.
 * We catch + log it, mirroring the Spotify boot path's known limitation exactly.
 */
object MediaRendererController {
    const val KEY_RENDERER_ENABLED = "dlna_renderer_enabled"

    /**
     * `DlnaPlayerFeature.KEY_ENABLED`, spelled out rather than imported: this package has no other
     * dependency on `dev.rusty.app` and is worth keeping that way. `MediaRendererControllerTest`
     * asserts the two constants stay equal, so the duplication cannot drift silently.
     */
    const val KEY_FEATURE_ENABLED = "dlna_feature_enabled"

    private const val TAG = "MediaRendererController"
    private const val PREFS_NAME = "spotify_receiver_prefs"

    /**
     * Pure decision — unit-testable: should the service run given prefs state?
     *
     * The DLNA Player FEATURE toggle owns the service. [KEY_RENDERER_ENABLED]'s own Start/Stop button
     * lives in the DLNA Player settings tab, and that tab disappears with the feature — so a run-state
     * left on behind a disabled feature is an unreachable service, not a headless mode. Gating here
     * rather than only at the toggle's call site makes the rule hold for the BOOT_COMPLETED sync too,
     * including for installs that already drifted into that state.
     */
    fun shouldRun(enabled: Boolean, featureEnabled: Boolean): Boolean = enabled && featureEnabled

    /** Writes the desired-state pref and syncs the service to it. The settings Start/Stop button
     *  and the notification Stop action both funnel through here. */
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_RENDERER_ENABLED, enabled)
            .apply()
        syncFromPrefs(context)
    }

    /**
     * Renames the DLNA player. Persists name + CONFIGID in one transaction, then — only if the
     * service is actually running — asks it to re-announce. Renaming while stopped writes prefs
     * only and must NOT start the service (the next start seeds from prefs and bumps BOOTID).
     */
    fun rename(context: Context, raw: String): RenameResult {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val result = RendererPrefs.rename(SharedPrefsRendererStore(prefs), raw)
        if (result is RenameResult.Renamed) {
            MediaRendererService.instance?.applyRename(result.name, result.configId)
        }
        return result
    }

    /** Start or stop [MediaRendererService] to match the persisted prefs state. */
    fun syncFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(KEY_RENDERER_ENABLED, false)
        val featureEnabled = prefs.getBoolean(KEY_FEATURE_ENABLED, false)
        val serviceIntent = Intent(context, MediaRendererService::class.java)
        if (shouldRun(enabled, featureEnabled)) {
            runCatching { context.startForegroundService(serviceIntent) }
                .onSuccess { RendererStatusPublisher.publishStartingUnlessRunning() }
                .onFailure { e ->
                    Log.w(TAG, "renderer start skipped (FGS boot restriction on Android 15+?)", e)
                    RendererStatusPublisher.publishFailed()
                }
        } else {
            context.stopService(serviceIntent)
            RendererStatusPublisher.publishStoppedIfInactive()
        }
    }
}
