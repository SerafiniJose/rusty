package dev.rusty.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

/**
 * Orchestrates the foreground receiver service lifecycle (start/stop/rename/bitrate).
 *
 * Extracted from [HomeActivity] as part of Phase 4 decomposition.
 *
 * ## Start gate
 * [ensureStarted] starts the service ONLY when the typed [ReceiverServiceState] is one of
 * `{STOPPED, FAILED, UNKNOWN}` — i.e., not already `STARTING` or `RUNNING`. This is the
 * authoritative gate; callers must NOT add a second guard.
 *
 * ## In-flight permission flag
 * [permissionRequestInFlight] is a **per-instance** field. It replaces the former
 * `HomeActivity.serviceStartRequested` static, which leaked across Activity instances and
 * caused the permission-retry bug: if the user denied notification permission, then granted
 * it from Settings and returned, the static `true` suppressed the retry — the receiver
 * never started. The per-instance flag resets naturally when the Activity is recreated
 * (e.g. after the user leaves Settings), so the retry fires correctly.
 *
 * ## Decision function
 * The pure, Android-free [shouldStart] predicate encodes the start gate so it can be unit-
 * tested without a device.
 */
class ReceiverController(
    private val context: Context,
    private val store: ReceiverStateStore,
    private val permissionLauncher: ActivityResultLauncher<String>,
    private val getDeviceName: () -> String,
    private val getBitrateKbps: () -> Int,
    private val onStateChanged: () -> Unit,
) {
    /**
     * True while a system notification-permission dialog is in flight. Per-instance — resets on
     * Activity recreation, which is the correct behaviour when returning from the Settings screen.
     */
    var permissionRequestInFlight: Boolean = false
        private set

    /**
     * Requests the foreground receiver service to start, subject to the typed start gate.
     *
     * - If the service is already `STARTING` or `RUNNING`, this is a no-op.
     * - On API 33+ (Tiramisu), notification permission is requested first; the service start is
     *   deferred to the permission-grant callback ([onPermissionResult]).
     * - [permissionRequestInFlight] is set to true before the permission dialog is shown and
     *   cleared in [onPermissionResult], preventing duplicate dialogs across rapid calls.
     */
    fun ensureStarted() {
        val serviceState = store.snapshot.service
        if (!shouldStart(serviceState, inFlight = permissionRequestInFlight)) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED
            ) {
                startService(getDeviceName())
            } else {
                permissionRequestInFlight = true
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            startService(getDeviceName())
        }
    }

    /**
     * Called by [HomeActivity]'s permission result callback. Starts the service on grant;
     * on denial, stamps the store with PERMISSION_DENIED so the UI shows the amber hint.
     * Clears [permissionRequestInFlight] in both cases.
     */
    fun onPermissionResult(isGranted: Boolean) {
        permissionRequestInFlight = false
        val name = getDeviceName()
        if (isGranted) {
            startService(name)
        } else {
            store.dispatch(
                ReceiverEvent.Status(
                    ReceiverDashboardStatusEvent(
                        receiverName = name,
                        lifecycle = ReceiverDashboardStatusEvent.Lifecycle.PERMISSION_DENIED,
                    )
                ),
                service = ReceiverServiceState.STOPPED,
            )
            onStateChanged()
        }
    }

    /** Sends the stop intent to [SpotifyService] and stamps the store as OFF/STOPPED. */
    fun stopReceiver() {
        context.stopService(Intent(context, SpotifyService::class.java))
        store.dispatch(
            ReceiverEvent.Status(
                ReceiverDashboardStatusEvent(
                    receiverName = getDeviceName(),
                    lifecycle = ReceiverDashboardStatusEvent.Lifecycle.OFF,
                )
            ),
            service = ReceiverServiceState.STOPPED,
        )
        onStateChanged()
    }

    /**
     * Applies a rename: persists via the prefs-write in [HomeActivity], then either reconnects
     * (session active) or renames in-place (idle). The caller is responsible for updating its own
     * `deviceName` field and persisting to prefs before calling this; this method only drives the
     * native layer and store dispatch.
     */
    fun applyReceiverName(newName: String, sessionActive: Boolean) {
        store.dispatch(ReceiverEvent.Rename(newName))
        onStateChanged()
        if (sessionActive) {
            sendServiceIntent(newName)
        } else {
            NativeBridge.renameDevice(newName)
            SpotifyService.onReceiverRenamed(newName)
        }
    }

    /**
     * Applies a bitrate change by re-delivering the start intent so the native session cycles.
     * The caller is responsible for persisting the new bitrate to prefs before calling this.
     */
    fun applyBitrate(newName: String) {
        sendServiceIntent(newName)
    }

    // ---- internals ----------------------------------------------------------

    private fun startService(name: String) {
        store.dispatch(
            ReceiverEvent.Status(
                ReceiverDashboardStatusEvent(
                    receiverName = name,
                    lifecycle = ReceiverDashboardStatusEvent.Lifecycle.FOREGROUND,
                )
            ),
            service = ReceiverServiceState.STARTING,
        )
        onStateChanged()
        sendServiceIntent(name)
    }

    private fun sendServiceIntent(name: String) {
        val intent = Intent(context, SpotifyService::class.java).apply {
            putExtra("DEVICE_NAME", name)
            putExtra("BITRATE_KBPS", getBitrateKbps())
        }
        context.startForegroundService(intent)
    }

    companion object {
        /**
         * Pure, Android-free start gate.
         *
         * Returns `true` when the receiver should start:
         * - [service] is in `{STOPPED, FAILED, UNKNOWN}` (not yet active), AND
         * - no permission dialog is [inFlight] (prevents duplicate dialogs).
         *
         * Deliberately Android-free so it can be exercised by a plain JVM test.
         */
        fun shouldStart(
            service: ReceiverServiceState,
            inFlight: Boolean,
        ): Boolean {
            if (inFlight) return false
            return service == ReceiverServiceState.STOPPED
                || service == ReceiverServiceState.FAILED
                || service == ReceiverServiceState.UNKNOWN
        }
    }
}
